# ADR-0008 — Contrato de evento versionado

**Status:** Aceito

## Decisão
O evento `EntryRegistered` carrega `eventType` e `version`, e o tópico tem sufixo `.v1`. As regras de evolução são:
- **Mudanças compatíveis** (novo campo opcional): mesma versão; o consumidor ignora campos desconhecidos (`fail-on-unknown-properties: false`).
- **Mudanças incompatíveis:** nova versão publicada em paralelo (`.v2`) durante a migração dos consumidores.
- O consumidor **valida** tipo, versão e campos e envia à DLQ o que não reconhece, em vez de aplicar errado.
- O valor monetário trafega como string decimal.

## Evolução
Schema Registry (Avro/JSON Schema) com checagem de compatibilidade no CI, quando houver mais de um produtor ou consumidor.
