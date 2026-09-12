#!/bin/bash

# Gate 3: Conservation Under Contention
# Seed wallets with initial balance
# Fire concurrent transfers among them (including A→B and B→A)
# Verify: total balance unchanged, no negative balances

set -e

BASE_URL="${1:-http://localhost:8080}"
USER_ID="test-user-$(date +%s%N)"
TRANSFER_AMOUNT=100
NUM_ROUNDS=50

echo "Testing Gate 3: Conservation Under Contention"
echo "BASE_URL: $BASE_URL"
echo "USER_ID: $USER_ID"
echo "Transfer amount per request: $TRANSFER_AMOUNT paise"
echo "Number of concurrent rounds: $NUM_ROUNDS"
echo ""

# Create 3 wallets (different users to get different wallets)
echo "Creating 3 wallets..."
USER_1="$USER_ID-user1"
USER_2="$USER_ID-user2"
USER_3="$USER_ID-user3"

WALLET_1=$(curl -s -X POST "$BASE_URL/wallets" \
  -H "Authorization: Bearer $USER_1" \
  -H "Content-Type: application/json" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Wallet 1: $WALLET_1"

WALLET_2=$(curl -s -X POST "$BASE_URL/wallets" \
  -H "Authorization: Bearer $USER_2" \
  -H "Content-Type: application/json" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Wallet 2: $WALLET_2"

WALLET_3=$(curl -s -X POST "$BASE_URL/wallets" \
  -H "Authorization: Bearer $USER_3" \
  -H "Content-Type: application/json" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Wallet 3: $WALLET_3"
echo ""

# Fund wallets 1 and 2 with enough balance for transfers
INITIAL_FUND=50000
echo "Funding wallets 1 and 2 with $INITIAL_FUND paise each..."
curl -s -X POST "$BASE_URL/wallets/$WALLET_1/fund?amountPaise=$INITIAL_FUND" \
  -H "Authorization: Bearer $USER_1" > /dev/null
curl -s -X POST "$BASE_URL/wallets/$WALLET_2/fund?amountPaise=$INITIAL_FUND" \
  -H "Authorization: Bearer $USER_2" > /dev/null
echo ""

# Get initial balances
BALANCE_1_BEFORE=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_1" \
  -H "Authorization: Bearer $USER_1" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)
BALANCE_2_BEFORE=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_2" \
  -H "Authorization: Bearer $USER_2" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)
BALANCE_3_BEFORE=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_3" \
  -H "Authorization: Bearer $USER_3" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)

TOTAL_BEFORE=$((BALANCE_1_BEFORE + BALANCE_2_BEFORE + BALANCE_3_BEFORE))

echo "Initial balances:"
echo "  Wallet 1: $BALANCE_1_BEFORE paise"
echo "  Wallet 2: $BALANCE_2_BEFORE paise"
echo "  Wallet 3: $BALANCE_3_BEFORE paise"
echo "  TOTAL: $TOTAL_BEFORE paise"
echo ""

echo "Firing $NUM_ROUNDS concurrent transfer pairs (A→B and B→A simultaneously)..."
echo ""

# Fire concurrent transfers
for i in {1..50}; do
  (
    # A→B
    curl -s -X POST "$BASE_URL/transfers" \
      -H "Authorization: Bearer $USER_1" \
      -H "Content-Type: application/json" \
      -d "{
        \"from\": \"$WALLET_1\",
        \"to\": \"$WALLET_2\",
        \"amountPaise\": $TRANSFER_AMOUNT,
        \"idempotencyKey\": \"$(uuidgen)\"
      }" > /dev/null 2>&1 &
    
    # B→A (reverse direction)
    curl -s -X POST "$BASE_URL/transfers" \
      -H "Authorization: Bearer $USER_2" \
      -H "Content-Type: application/json" \
      -d "{
        \"from\": \"$WALLET_2\",
        \"to\": \"$WALLET_1\",
        \"amountPaise\": $TRANSFER_AMOUNT,
        \"idempotencyKey\": \"$(uuidgen)\"
      }" > /dev/null 2>&1 &
  ) &
done

wait

echo "All transfers completed. Waiting 2 seconds for DB consistency..."
sleep 2

# Get final balances
BALANCE_1_AFTER=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_1" \
  -H "Authorization: Bearer $USER_1" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)
BALANCE_2_AFTER=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_2" \
  -H "Authorization: Bearer $USER_2" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)
BALANCE_3_AFTER=$(curl -s -X GET "$BASE_URL/wallets/$WALLET_3" \
  -H "Authorization: Bearer $USER_3" | grep -o '"balancePaise":[0-9]*' | cut -d':' -f2)

TOTAL_AFTER=$((BALANCE_1_AFTER + BALANCE_2_AFTER + BALANCE_3_AFTER))

echo ""
echo "Final balances:"
echo "  Wallet 1: $BALANCE_1_AFTER paise"
echo "  Wallet 2: $BALANCE_2_AFTER paise"
echo "  Wallet 3: $BALANCE_3_AFTER paise"
echo "  TOTAL: $TOTAL_AFTER paise"
echo ""

# Validation
echo "Results:"
echo "--------"

PASS=true

# Check conservation
if [ "$TOTAL_BEFORE" -eq "$TOTAL_AFTER" ]; then
  echo "✅ PASS: Total balance conserved ($TOTAL_BEFORE = $TOTAL_AFTER paise)"
else
  echo "❌ FAIL: Total balance NOT conserved (before: $TOTAL_BEFORE, after: $TOTAL_AFTER)"
  PASS=false
fi

# Check no negative balances
if [ "$BALANCE_1_AFTER" -ge 0 ] && [ "$BALANCE_2_AFTER" -ge 0 ] && [ "$BALANCE_3_AFTER" -ge 0 ]; then
  echo "✅ PASS: No wallet has negative balance"
else
  echo "❌ FAIL: Negative balance detected!"
  echo "  Wallet 1: $BALANCE_1_AFTER"
  echo "  Wallet 2: $BALANCE_2_AFTER"
  echo "  Wallet 3: $BALANCE_3_AFTER"
  PASS=false
fi

# Check Wallet 3 balance unchanged (it had no transfers)
if [ "$BALANCE_3_BEFORE" -eq "$BALANCE_3_AFTER" ]; then
  echo "✅ PASS: Wallet 3 balance unchanged (no transfers)"
else
  echo "❌ FAIL: Wallet 3 balance changed unexpectedly"
  PASS=false
fi

echo ""

if [ "$PASS" = true ]; then
  echo "✅ Gate 3 PASSED: Conservation under contention verified"
  exit 0
else
  echo "❌ Gate 3 FAILED"
  exit 1
fi
