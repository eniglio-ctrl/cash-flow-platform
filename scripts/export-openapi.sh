#!/usr/bin/env bash
# Exporta os contratos OpenAPI gerados pelos serviços em execução para docs/api/.
# O CI roda este script e falha se o resultado divergir do que está versionado,
# garantindo que a documentação acompanhe o código.
# Pré-requisitos: stack no ar (docker compose up -d --wait), curl e jq.
set -euo pipefail

BASE=${BASE_URL:-http://localhost:8080}
DIR="$(cd "$(dirname "$0")/.." && pwd)/docs/api"

for service in launch consolidation; do
  tmp=$(mktemp)
  # tenta por até ~40 s: logo após o "up", o gateway pode ainda não alcançar os serviços
  curl -sf --retry 20 --retry-delay 2 --retry-all-errors "$BASE/openapi/$service.json" | jq -e --sort-keys '.openapi and .paths' > /dev/null
  curl -sf "$BASE/openapi/$service.json" | jq --sort-keys . > "$tmp"
  mv "$tmp" "$DIR/$service-openapi.json"
  echo "docs/api/$service-openapi.json atualizado"
done
