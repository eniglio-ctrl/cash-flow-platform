#!/usr/bin/env bash
# Passeio rápido pela API: obtém token, registra lançamentos, estorna e consulta o consolidado.
set -euo pipefail
BASE=${BASE_URL:-http://localhost:8080}
TODAY=$(TZ=America/Sao_Paulo date +%F)

TOKEN=$(curl -sf -X POST "$BASE/dev/token" | jq -r .access_token)
AUTH="Authorization: Bearer $TOKEN"
post() { curl -s -X POST "$BASE$1" -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$RANDOM-$RANDOM" -d "$2"; }

echo "> crédito 500,00";  post /api/v1/lancamentos "{\"date\":\"$TODAY\",\"type\":\"CREDIT\",\"amount\":500.00,\"description\":\"Vendas manhã\"}" | jq .
echo "> débito 120,40";   DEBIT=$(post /api/v1/lancamentos "{\"date\":\"$TODAY\",\"type\":\"DEBIT\",\"amount\":120.40,\"description\":\"Fornecedor\"}")
jq . <<<"$DEBIT"
echo "> estorno do débito"; post "/api/v1/lancamentos/$(jq -r .id <<<"$DEBIT")/estornos" '{"reason":"Lançado em duplicidade"}' | jq .
sleep 3
echo "> consolidado de $TODAY"; curl -s -H "$AUTH" "$BASE/api/v1/consolidados/$TODAY" | jq .
