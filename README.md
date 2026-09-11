# Wallet Transfer Service

A race-free, idempotent P2P wallet transfer service built with Java Spring Boot and PostgreSQL.

## Architecture

- **Language:** Java Spring Boot 3.2
- **Database:** PostgreSQL 16
- **Auth:** Bearer token (simple)
- **Money Format:** Integer paise (BIGINT, never float)
- **Ledger:** Double-entry (immutable, append-only)
- **Consistency:** Strong (CP in CAP)

## Key Features

✅ **Race-Free Get-or-Create:** Unique constraint on `user_id` prevents duplicate wallets
✅ **Idempotent Transfers:** Unique constraint on `idempotency_key` ensures exactly-once execution
✅ **Conservation:** Double-entry ledger + atomic conditional UPDATE ensures money is never created/destroyed
✅ **No Overdraft:** Conditional UPDATE prevents negative balances
✅ **Structured Logging:** JSON logs with correlation IDs for request tracing
✅ **Metrics:** Prometheus metrics for p99 latency and domain counters

## Quick Start

### Prerequisites
- Docker and Docker Compose
- Java 21 (for local development)
- Gradle 8.4+ (for local development, or use included gradlew)

### Run with Docker Compose

```bash
docker-compose up --build
```

The service will be available at `http://localhost:8080`

### Run Locally

```bash
# Start PostgreSQL
docker run -d \
  --name postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=wallets \
  -p 5432:5432 \
  postgres:16-alpine

# Initialize database
psql -h localhost -U postgres -d wallets -f init.sql

# Build and run (using Gradle wrapper)
./gradlew clean bootJar
java -jar build/libs/wallet-service-1.0.0.jar
```

## API Endpoints

### Create Wallet (Get or Create)
```bash
curl -X POST http://localhost:8080/wallets \
  -H "Authorization: Bearer user123" \
  -H "Content-Type: application/json"
```

Response:
```json
{
  "id": "uuid",
  "userId": "user123",
  "balancePaise": 0,
  "balanceRupees": "0.00",
  "createdAt": "2026-09-11T10:00:00"
}
```

### Get Wallet
```bash
curl -X GET http://localhost:8080/wallets/{id} \
  -H "Authorization: Bearer user123"
```

### Create Transfer
```bash
curl -X POST http://localhost:8080/transfers \
  -H "Authorization: Bearer user123" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "wallet-id-1",
    "to": "wallet-id-2",
    "amountPaise": 1000,
    "idempotencyKey": "uuid"
  }'
```

Response:
```json
{
  "id": "transfer-uuid",
  "from": "wallet-id-1",
  "to": "wallet-id-2",
  "amountPaise": 1000,
  "status": "COMPLETED",
  "createdAt": "2026-09-11T10:00:00"
}
```

### Get Transfer
```bash
curl -X GET http://localhost:8080/transfers/{id} \
  -H "Authorization: Bearer user123"
```

## Metrics

Access Prometheus metrics at:
```
http://localhost:8080/actuator/metrics
http://localhost:8080/actuator/prometheus
```

Key metrics:
- `transfers.created.total` - Total transfers created
- `transfers.declined.insufficient_funds.total` - Transfers declined due to insufficient funds
- `transfers.idempotent_replays.total` - Idempotent transfer replays
- `wallets.created.total` - Total wallets created
- `wallets.get_or_create_hits.total` - Get-or-create hits (existing wallet returned)
- `transfers.latency.ms` - Transfer operation latency
- `http.server.requests` - HTTP request metrics with p99 latency

## Logs

Structured JSON logs are written to:
- Console (stdout)
- File: `logs/wallet-service.log`

Each log entry includes:
- `timestamp` - ISO 8601 timestamp
- `level` - Log level (INFO, DEBUG, WARN, ERROR)
- `correlation_id` - Request correlation ID for tracing
- `message` - Log message
- `logger_name` - Logger class name
- Custom fields (service, environment, etc.)

Example log entry:
```json
{
  "timestamp": "2026-09-11T10:00:00.123Z",
  "level": "INFO",
  "correlation_id": "req-uuid-12345",
  "message": "Transfer completed",
  "transfer_id": "transfer-uuid",
  "from_wallet_id": "wallet-1",
  "to_wallet_id": "wallet-2",
  "amount_paise": 1000,
  "service": "wallet-service"
}
```

## Testing

### Gate 1: Concurrent Get-or-Create
```bash
./test-gate-1-concurrent-get-or-create.sh http://localhost:8080
```

Fires 50 concurrent requests to create a wallet for the same user. Expects exactly one wallet.

### Gate 2: Idempotent Retry Storm
```bash
./test-gate-2-idempotent-retry-storm.sh http://localhost:8080
```

Fires 30 concurrent requests with the same idempotency key. Expects exactly one transfer.

### Gate 3: Conservation Under Contention
```bash
./test-gate-3-conservation-under-contention.sh http://localhost:8080
```

Fires concurrent transfers in both directions (A→B and B→A). Verifies conservation.

## Data Model

### Wallets Table
```sql
CREATE TABLE wallets (
  id UUID PRIMARY KEY,
  user_id VARCHAR(255) NOT NULL UNIQUE,
  balance_paise BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
```

### Transfers Table
```sql
CREATE TABLE transfers (
  id UUID PRIMARY KEY,
  idempotency_key UUID NOT NULL UNIQUE,
  from_wallet_id UUID NOT NULL REFERENCES wallets(id),
  to_wallet_id UUID NOT NULL REFERENCES wallets(id),
  amount_paise BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(255),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
```

### Ledger Entries Table (Immutable Audit Trail)
```sql
CREATE TABLE ledger_entries (
  id UUID PRIMARY KEY,
  transfer_id UUID NOT NULL REFERENCES transfers(id),
  wallet_id UUID NOT NULL REFERENCES wallets(id),
  amount_paise BIGINT NOT NULL,
  entry_type VARCHAR(10) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
```

## Design Decisions

### Money Movement: Conditional UPDATE
We use atomic conditional UPDATE to prevent overdrafts and deadlocks:
```sql
UPDATE wallets 
SET balance = balance - amount 
WHERE id = wallet_id AND balance >= amount
```

**Why:**
- Simplest correct mechanism
- No deadlock risk (single row lock)
- Atomic check + update prevents race conditions
- Proven at Paytm's scale

### Idempotency: Unique Constraint + Same Transaction
Idempotency key uniqueness is enforced by a DB constraint and committed in the same transaction as the ledger movement.

**Why:**
- Atomic at DB level
- Prevents TOCTOU (time-of-check-time-of-use) races
- Concurrent duplicates: one succeeds, others get exception
- Same key + different body returns 409 Conflict

### Consistency: Strong (CP)
We chose strong consistency over availability.

**Why:**
- Money requires correctness
- Stale balance reads could lead to overdrafts
- "Best-effort" is not acceptable for financial systems

## Deployment

### Docker Image
```bash
docker build -t wallet-service:latest .
docker run -d \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/wallets \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=postgres \
  wallet-service:latest
```

### Health Check
```bash
curl http://localhost:8080/actuator/health
```

## Troubleshooting

### Database Connection Issues
```bash
# Check PostgreSQL is running
docker ps | grep postgres

# Check logs
docker logs <container-id>
```

### Build Failures
```bash
# Clean build
./gradlew clean bootJar

# Check Java version
java -version  # Should be 21+
```

### Test Script Issues
```bash
# Make scripts executable
chmod +x test-gate-*.sh

# Run with verbose output
bash -x test-gate-1-concurrent-get-or-create.sh
```


