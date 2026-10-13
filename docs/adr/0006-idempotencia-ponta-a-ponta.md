# ADR-0006 — Idempotência ponta a ponta

**Status:** Aceito

## Contexto
Duplicidade aparece em dois pontos: o **cliente** reenvia após um timeout, e o **broker** reentrega (outbox at-least-once, rebalance).

## Decisão
1. **API:** o header `Idempotency-Key` é obrigatório. `UNIQUE(merchant_id, idempotency_key)` e um `request_hash` (SHA-256 do conteúdo).
   - mesma chave e mesmo conteúdo → 200 com o lançamento original (`Idempotent-Replayed: true`);
   - mesma chave e conteúdo diferente → 409;
   - corrida entre requisições iguais → a constraint decide; a perdedora relê e devolve o vencedor.
2. **Consumidor:** a tabela `processed_event` com PK em `event_id` (e `UNIQUE(entry_id)`) recebe o `INSERT … ON CONFLICT DO NOTHING` **na mesma transação** do UPSERT do saldo. Ou os dois acontecem, ou nenhum.

## Consequências
- ➕ Exactly-once **efetivo** sobre infraestrutura at-least-once.
- ➕ Testado: 10 POSTs concorrentes com a mesma chave geram 1 lançamento; 200 eventos entregues 2× em paralelo resultam em totais exatos.
- ➖ `processed_event` cresce. Evolução: particionar por mês e expurgar além da retenção do tópico.
