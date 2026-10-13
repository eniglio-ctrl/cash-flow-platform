# ADR-0002 — Integração assíncrona por eventos

**Status:** Aceito

## Contexto
Se o launch chamasse o consolidado via HTTP, a indisponibilidade do consolidado faria o registro falhar, esperar timeout ou exigir compensação. Isso viola o RNF-01.

## Decisão
O launch publica o fato `EntryRegistered` num broker durável; o consolidado consome no seu ritmo.

## Alternativas
- **REST síncrono com circuit breaker:** o breaker evita a cascata, mas o lançamento ainda precisaria ser "lembrado" para reenviar, o que reinventaria uma fila.
- **Consolidar sob demanda (somar lançamentos na consulta):** acopla a leitura ao banco de escrita e não escala para 50 req/s com histórico grande.

## Consequências
- ➕ O registro não depende do consolidado; o broker absorve picos e quedas (buffer).
- ➖ **Consistência eventual**: o saldo pode ficar alguns segundos atrás (medido em < 1 s em operação normal). Isso é explicitado na API pelo header `X-Consistency: eventual` e monitorado pela métrica de atualização.
