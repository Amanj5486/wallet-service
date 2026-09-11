#!/bin/bash

# Gate 1: Concurrent Get-or-Create
# Fire 50 concurrent POST /wallets for a fresh user
# Expect exactly one wallet

set -e

BASE_URL="${1:-http://localhost:8080}"
USER_ID="test-user-$(date +%s%N)"
WALLET_IDS=()

echo "Testing Gate 1: Concurrent Get-or-Create"
echo "BASE_URL: $BASE_URL"
echo "USER_ID: $USER_ID"
echo "Firing 50 concurrent POST /wallets requests..."

# Fire 50 concurrent requests
for i in {1..50}; do
  (
    RESPONSE=$(curl -s -X POST "$BASE_URL/wallets" \
      -H "Authorization: Bearer $USER_ID" \
      -H "Content-Type: application/json")
    
    WALLET_ID=$(echo "$RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "$WALLET_ID"
  ) &
done

# Wait for all requests to complete
wait

# Collect wallet IDs
echo ""
echo "Collecting wallet IDs from responses..."
WALLET_IDS=($(for i in {1..50}; do
  RESPONSE=$(curl -s -X POST "$BASE_URL/wallets" \
    -H "Authorization: Bearer $USER_ID" \
    -H "Content-Type: application/json")
  echo "$RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
done))

# Count unique wallet IDs
UNIQUE_WALLETS=$(printf '%s\n' "${WALLET_IDS[@]}" | sort -u | wc -l)

echo ""
echo "Results:"
echo "--------"
echo "Total requests: 50"
echo "Unique wallets created: $UNIQUE_WALLETS"
echo ""

if [ "$UNIQUE_WALLETS" -eq 1 ]; then
  echo "✅ PASS: Exactly one wallet created"
  exit 0
else
  echo "❌ FAIL: Expected 1 wallet, got $UNIQUE_WALLETS"
  exit 1
fi
