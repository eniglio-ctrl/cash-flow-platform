# ADR-0005 — PostgreSQL e SQL explícito

**Status:** Aceito

## Decisão
PostgreSQL 16 nos dois contextos, com migrations Flyway e acesso via `JdbcClient` (Spring JDBC), **sem ORM**.

## Por quê
- Dinheiro exige `NUMERIC` exato e transações ACID.
- Os recursos de que a solução depende são específicos e visíveis em SQL: `ON CONFLICT DO UPDATE` (incremento atômico), `ON CONFLICT DO NOTHING` (dedup), `FOR UPDATE SKIP LOCKED` (relay), índice parcial (outbox) e `UNIQUE` composto (idempotência).
- Com JPA, essas garantias ficariam escondidas atrás de abstrações (dirty checking, flush order), justamente onde a revisão de código precisa enxergá-las.

## Alternativas
- **MongoDB/DynamoDB:** modelos de documento atenderiam o read model, mas o lado transacional com unicidade composta e outbox na mesma transação fica mais complexo.
- **JPA/Hibernate:** produtivo para CRUD, mas o domínio aqui é pequeno e as consultas são poucas e críticas.

## Consequências
- ➕ SQL revisável, previsível e performático.
- ➖ Mapeamento manual (pequeno: duas tabelas por serviço).
