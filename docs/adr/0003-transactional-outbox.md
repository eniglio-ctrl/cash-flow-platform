# ADR-0003 — Transactional Outbox

**Status:** Aceito

## Contexto
"Gravar no banco e publicar no broker" são duas operações sem transação comum (*dual write*). Se o processo cair entre as duas, perde-se o evento (saldo errado para sempre) ou publica-se algo que não foi gravado.

## Decisão
O lançamento e o evento são gravados na **mesma transação** (`entry` + `outbox_event`). Um relay no próprio launch-service lê os pendentes a cada 500 ms com `SELECT … FOR UPDATE SKIP LOCKED`, publica com `acks=all` + produtor idempotente e marca como `SENT`. Os eventos `SENT` são removidos após 7 dias.

## Alternativas
- **CDC com Debezium** lendo o WAL: menor latência e sem polling, mas exige Kafka Connect (mais um componente para operar). É a evolução natural se o volume crescer; o formato da tabela outbox já é compatível com o *Outbox Event Router* do Debezium.
- **Publicar após o commit, sem outbox:** perde eventos em crash.
- **Transação distribuída (XA):** complexa, lenta e não suportada de forma prática pelo Kafka.

## Consequências
- ➕ Nenhum evento perdido; o launch aceita lançamentos mesmo com o broker fora (`BrokerUnavailableIT`).
- ➕ `SKIP LOCKED` permite várias instâncias do relay sem coordenação.
- ➖ Entrega **at-least-once**: pode haver duplicata (por exemplo, timeout após o broker ter recebido). Resolvido pelo ADR-0006.
- ➖ Ordem garantida por comerciante apenas enquanto o lote é interrompido na primeira falha (implementado assim).
