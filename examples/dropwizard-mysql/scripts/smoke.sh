#!/usr/bin/env bash
# Boots the service, drives an order through the REST API and the admin UI, and exits non-zero on any miss.
#
#   scripts/smoke.sh config-sqlite.yml   # no database needed
#   scripts/smoke.sh config.yml          # against the MySQL that MYSQL_URL (default localhost:3306) points at
set -euo pipefail
cd "$(dirname "$0")/.."
CONFIG=${1:-config.yml}
APP=http://localhost:8080
ADMIN=http://localhost:8081

rm -f orders.db
./gradlew -q run --args="server $CONFIG" > smoke-server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true; wait $SERVER 2>/dev/null || true' EXIT

echo "waiting for the server..."
for _ in $(seq 1 90); do
  curl -fsS "$ADMIN/healthcheck" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS "$ADMIN/healthcheck" >/dev/null || { echo "server did not come up"; tail -50 smoke-server.log; exit 1; }

expect() { # expect <label> <json> <substring>
  case "$2" in *"$3"*) echo "ok   $1";; *) echo "FAIL $1: expected '$3' in: $2"; tail -40 smoke-server.log; exit 1;; esac
}

RUN=$(date +%s)
SMALL="smoke-$RUN-small"; LARGE="smoke-$RUN-large"
SMALL_JSON='{"orderId":"'$SMALL'","customerId":"alice","sku":"BOOK-1","quantity":2,"amountCents":3900}'
LARGE_JSON='{"orderId":"'$LARGE'","customerId":"bob","sku":"LAPTOP-9","quantity":1,"amountCents":149900}'

post() { curl -sS -X POST "$APP/orders" -H 'Content-Type: application/json' -d "$1"; }

placed=$(post "$SMALL_JSON")
expect "place small order" "$placed" "\"orderId\":\"$SMALL\""

await() { # await <orderId> <instanceStatus>
  for _ in $(seq 1 60); do
    body=$(curl -fsS "$APP/orders/$1")
    case "$body" in *"\"instanceStatus\":\"$2\""*) echo "$body"; return 0;; esac
    sleep 0.5
  done
  echo "FAIL $1 never reached $2; last: $body"; exit 1
}

expect "small order fulfilled" "$(await "$SMALL" COMPLETED)" '"status":"FULFILLED"'

post "$LARGE_JSON" >/dev/null
expect "large order parked" "$(await "$LARGE" WAITING)" '"stage":"AWAITING_APPROVAL"'
curl -fsS -X POST "$APP/orders/$LARGE/approve?decision=true" >/dev/null
expect "large order fulfilled after approval" "$(await "$LARGE" COMPLETED)" '"status":"FULFILLED"'

expect "unknown order is 404" "$(curl -sS -o /dev/null -w '%{http_code}' "$APP/orders/nope")" 404

# The admin UI: HTML shell plus the JSON API behind it, mounted on Jersey with no extra configuration.
expect "admin UI html" "$(curl -fsS "$APP/skipper/admin/")" "<html"
expect "admin dashboard stats" "$(curl -fsS "$APP/skipper/admin/dashboard/stats")" "{"
expect "admin lists the instance" "$(curl -fsS "$APP/skipper/admin/workflows/$LARGE")" "$LARGE"
expect "admin lists the persisted approval signal" "$(curl -fsS "$APP/skipper/admin/workflows/$LARGE/signals")" "approve"

echo "smoke passed against $CONFIG"
