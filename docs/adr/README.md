# Architecture Decision Records

Formato: contexto → decisão → alternativas → consequências. Uma decisão substituída não é apagada: o ADR antigo é marcado como *Substituído por*.

| ADR | Decisão | Status |
|---|---|---|
| [0001](0001-dois-servicos-por-contexto.md) | Dois serviços, um por bounded context, com banco próprio | Aceito |
| [0002](0002-integracao-assincrona.md) | Integração assíncrona por eventos | Aceito |
| [0003](0003-transactional-outbox.md) | Transactional Outbox com relay por polling + SKIP LOCKED | Aceito |
| [0004](0004-kafka.md) | Apache Kafka como broker | Aceito |
| [0005](0005-postgresql.md) | PostgreSQL + SQL explícito (JdbcClient), sem ORM | Aceito |
| [0006](0006-idempotencia-ponta-a-ponta.md) | Idempotência na API e no consumidor | Aceito |
| [0007](0007-read-model-e-cache.md) | Read model pré-agregado + cache local de TTL curto | Aceito |
| [0008](0008-contrato-de-eventos-versionado.md) | Contrato de evento versionado (Published Language) | Aceito |
| [0009](0009-lancamentos-imutaveis.md) | Lançamentos imutáveis; correção por estorno | Aceito |
| [0010](0010-saldo-acumulado-calculado-na-leitura.md) | Saldo inicial calculado na leitura | Aceito |
