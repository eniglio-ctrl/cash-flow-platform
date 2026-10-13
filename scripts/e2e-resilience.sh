#!/usr/bin/env bash
# Cenário crítico de aceite (RNF-01): o registro de lançamentos continua funcionando com o
# consolidado FORA DO AR, e nenhum lançamento se perde quando ele volta.
#
#   Dado    o serviço de consolidado indisponível
#   Quando  100 lançamentos são enviados
#   Então   todos são aceitos (201)
#   Quando  o consolidado volta
#   Então   o backlog é processado, o saldo reflete os 100 lançamentos
#   E       reenvios com a mesma Idempotency-Key não alteram o resultado
#
# Pré-requisitos: stack no ar (docker compose up -d --build), curl e jq.
set -euo pipefail

BASE=${BASE_URL:-http://localhost:8080}
N=${N:-100}
TODAY=$(TZ=America/Sao_Paulo date +%F)
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
fail()  { red "FALHOU: $*"; exit 1; }

TOKEN_JSON=$(curl -sf -X POST "$BASE/dev/token")
TOKEN=$(jq -r .access_token <<<"$TOKEN_JSON")
MERCHANT=$(jq -r .merchant_id <<<"$TOKEN_JSON")
AUTH="Authorization: Bearer $TOKEN"
echo "Comerciante de teste: $MERCHANT"

echo "1) Derrubando o serviço de consolidado..."
docker compose stop consolidation-service >/dev/null
status=$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" "$BASE/api/v1/consolidados/$TODAY")
echo "   GET consolidado agora responde HTTP $status (esperado 5xx)"

echo "2) Enviando $N lançamentos (créditos de 10,00 e débitos de 3,00 alternados)..."
accepted=0
for i in $(seq 1 "$N"); do
  if (( i % 2 )); then type=CREDIT; amount=10.00; else type=DEBIT; amount=3.00; fi
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/lancamentos" \
    -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: e2e-$MERCHANT-$i" \
    -d "{\"date\":\"$TODAY\",\"type\":\"$type\",\"amount\":\"$amount\",\"description\":\"e2e $i\"}")
  [[ $code == 201 ]] && accepted=$((accepted + 1))
done
[[ $accepted == "$N" ]] || fail "apenas $accepted/$N lançamentos aceitos com o consolidado fora"
green "   $accepted/$N lançamentos aceitos com o consolidado fora do ar"

echo "3) Reenviando os mesmos $N lançamentos (mesma Idempotency-Key)..."
for i in $(seq 1 "$N"); do
  if (( i % 2 )); then type=CREDIT; amount=10.00; else type=DEBIT; amount=3.00; fi
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/lancamentos" \
    -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: e2e-$MERCHANT-$i" \
    -d "{\"date\":\"$TODAY\",\"type\":\"$type\",\"amount\":\"$amount\",\"description\":\"e2e $i\"}")
  [[ $code == 200 ]] || fail "reenvio $i retornou $code (esperado 200)"
done
green "   reenvios devolveram o lançamento original (200), sem duplicar"

echo "4) Religando o consolidado..."
docker compose start consolidation-service >/dev/null

credits=$(( (N + 1) / 2 * 10 )); debits=$(( N / 2 * 3 ))
expected_net="$((credits - debits)).00"
echo "   esperado: $N lançamentos, saldo líquido do dia $expected_net"
for attempt in $(seq 1 60); do
  body=$(curl -s -H "$AUTH" "$BASE/api/v1/consolidados/$TODAY" || true)
  count=$(jq -r '.entriesCount // empty' <<<"$body" 2>/dev/null || true)
  if [[ $count == "$N" ]]; then
    net=$(jq -r .netAmount <<<"$body")
    [[ $net == "$expected_net" || $net == "${expected_net%.00}" ]] || fail "saldo líquido $net, esperado $expected_net"
    green "   backlog processado em ~$((attempt * 2))s: $count lançamentos, líquido $net"
    jq . <<<"$body"
    green "SUCESSO: nenhum lançamento perdido ou duplicado."
    exit 0
  fi
  sleep 2
done
fail "consolidado não convergiu (último retorno: $body)"
