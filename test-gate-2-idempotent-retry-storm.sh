#!/bin/bash

# Gate 2: Idempotent Retry Storm
# Fire the same transfer (same key) 30 times concurrently
# Verify: exactly one transfer created, one debit/credit, identical responses

set -e

BASE_URL="${1:-http://localhost:8080}"
USER_ID="test-user-$(date +%s%N)"
IDEMPOTENCY_KEY=$(uuidgen)
TRANSFER_AMOUNT=1000

echo "Testing Gate 2: Idempotent Retry Storm"
echo "BASE_URL: $BASE_URL"
echo "USER_ID: $USER_ID"
echo "IDEMPOTENCY_KEY: $IDEMPOTENCY_KEY"
echo ""

# Create two wallets (different users to get different wallets)
echo "Creating wallets..."
USER_1="$USER_ID-user1"
USER_2="$USER_ID-user2"

WALLET_1=$(curl -s -X POST "$BASE_URL/wallets" \
  -H "Authorization: Bearer $USER_1" \
  -H "Content-Type: application/json" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Wallet 1 (source): $WALLET_1"

WALLET_2=$(curl -s -X POST "$BASE_URL/wallets" \
  -H "Authorization: Bearer $USER_2" \
  -H "Content-Type: application/json" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Wallet 2 (destination): $WALLET_2"
echo ""

# Fund wallet 1 with enough balance for transfers
INITIAL_FUND=100000
echo "Funding wallet 1 with $INITIAL_FUND paise..."
curl -s -X POST "$BASE_URL/wallets/$WALLET_1/fund?amountPaise=$INITIAL_FUND" \
  -H "Authorization: Bearer $USER_ID" > /dev/null
echo ""

# Get initial balances
BALANCE_1_BEFORE=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_1" \
  -H "Authorization: Bearer $USER_1" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)
BALANCE_2_BEFORE=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_2" \
  -H "Authorization: Bearer $USER_2" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)

echo "Initial balances:"
echo "  Wallet 1: $BALANCE_1_BEFORE paise"
echo "  Wallet 2: $BALANCE_2_BEFORE paise"
echo ""

# Fire 30 concurrent requests with SAME idempotency key
echo "Firing 30 concurrent transfers with SAME idempotency key..."
echo "Transfer amount: $TRANSFER_AMOUNT paise"
echo ""

RESPONSES_FILE="/tmp/gate2_responses_$$.txt"
> "$RESPONSES_FILE"

for i in {1..30}; do
  (
    RESPONSE=$(curl -s -X POST "$BASE_URL/transfers" \
      -H "Authorization: Bearer $USER_1" \
      -H "Content-Type: application/json" \
      -d "{
        \"from\": \"$WALLET_1\",
        \"to\": \"$WALLET_2\",
        \"amountPaise\": $TRANSFER_AMOUNT,
        \"idempotencyKey\": \"$IDEMPOTENCY_KEY\"
      }")
    
    echo "$RESPONSE" >> "$RESPONSES_FILE"
  ) &
done

wait

echo "All 30 requests completed."
echo ""

# Show first response for debugging
echo "Sample response:"
head -1 "$RESPONSES_FILE" | jq . 2>/dev/null || head -1 "$RESPONSES_FILE"
echo ""

# Extract unique transfer IDs
echo "Analyzing responses..."
TRANSFER_ID=$(grep -o '"id":"[^"]*"' "$RESPONSES_FILE" | head -1 | cut -d'"' -f4)
UNIQUE_TRANSFERS=$(grep -o '"id":"[^"]*"' "$RESPONSES_FILE" | sort -u | wc -l)
UNIQUE_STATUSES=$(grep -o '"status":"[^"]*"' "$RESPONSES_FILE" | sort -u | wc -l)
TRANSFER_STATUS=$(grep -o '"status":"[^"]*"' "$RESPONSES_FILE" | head -1 | cut -d'"' -f4)

echo "Transfer ID: $TRANSFER_ID"
echo "Transfer Status: $TRANSFER_STATUS"
echo "Unique transfer IDs: $UNIQUE_TRANSFERS"
echo "Unique statuses: $UNIQUE_STATUSES"
echo ""

# Wait for DB consistency
sleep 2

# Get final balances
BALANCE_1_AFTER=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_1" \
  -H "Authorization: Bearer $USER_1" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)
BALANCE_2_AFTER=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_2" \
  -H "Authorization: Bearer $USER_2" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)

BALANCE_1_CHANGE=$((BALANCE_1_BEFORE - BALANCE_1_AFTER))
BALANCE_2_CHANGE=$((BALANCE_2_AFTER - BALANCE_2_BEFORE))

echo "Final balances:"
echo "  Wallet 1: $BALANCE_1_AFTER paise (change: -$BALANCE_1_CHANGE)"
echo "  Wallet 2: $BALANCE_2_AFTER paise (change: +$BALANCE_2_CHANGE)"
echo ""

# Validation
echo "Results:"
echo "--------"

PASS=true

# Check exactly one transfer created
if [ "$UNIQUE_TRANSFERS" -eq 1 ]; then
  echo "✅ PASS: Exactly one transfer created (idempotency working)"
else
  echo "❌ FAIL: Expected 1 transfer, got $UNIQUE_TRANSFERS"
  PASS=false
fi

# Check exactly one debit
if [ "$BALANCE_1_CHANGE" -eq "$TRANSFER_AMOUNT" ]; then
  echo "✅ PASS: Exactly one debit of $TRANSFER_AMOUNT paise"
else
  echo "❌ FAIL: Expected debit of $TRANSFER_AMOUNT, got $BALANCE_1_CHANGE"
  PASS=false
fi

# Check exactly one credit
if [ "$BALANCE_2_CHANGE" -eq "$TRANSFER_AMOUNT" ]; then
  echo "✅ PASS: Exactly one credit of $TRANSFER_AMOUNT paise"
else
  echo "❌ FAIL: Expected credit of $TRANSFER_AMOUNT, got $BALANCE_2_CHANGE"
  PASS=false
fi

# Check all responses are identical
if [ "$UNIQUE_STATUSES" -eq 1 ]; then
  echo "✅ PASS: All 30 responses have identical status"
else
  echo "❌ FAIL: Responses have different statuses"
  PASS=false
fi

echo ""

rm -f "$RESPONSES_FILE"

if [ "$PASS" = true ]; then
  echo "✅ Gate 2 PASSED: Idempotent retry storm verified"
  exit 0
else
  echo "❌ Gate 2 FAILED"
  exit 1
fi
