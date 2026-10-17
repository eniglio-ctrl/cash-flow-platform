# 9. Evoluções futuras e o que eu faria com mais tempo

Ordenado por valor/risco.

## Curto prazo (próximas iterações)
1. IdP real no ambiente local (Keycloak no Compose) e RS256/JWKS, removendo o `/dev/token`.
2. Fechamento diário/mensal: bloquear lançamentos em períodos fechados e gravar um snapshot de saldo, o que limita a soma do saldo inicial a ~31 linhas (ADR-0010).
3. Snapshot + replay do read model: job para reconstruir o consolidado a partir do tópico (com compactação ou retenção longa) e conciliação automática *lançamentos × consolidado*.
4. Expurgo de `processed_event` particionado por mês.
5. Testes de contrato do evento (JSON Schema) no CI dos dois serviços.
6. Teste de carga também do POST de lançamentos e caos contínuo (Toxiproxy: latência no banco/broker).

## Médio prazo
7. CDC (Debezium) no lugar do polling da outbox, se o volume crescer (menor latência e menos carga no banco).
8. Schema Registry com checagem de compatibilidade.
9. Infraestrutura como código (Terraform) para o desenho AWS de [03-arquitetura-alvo.md](03-arquitetura-alvo.md#310-arquitetura-de-implantação-produção-aws), com pipeline de deploy blue/green.
10. SLOs formais com burn-rate alerts (multi-window) e error budget policy.
11. Multi-moeda e fuso por comerciante.

## Longo prazo / novos consumidores do mesmo evento
12. Conciliação bancária (Open Finance), projeções de fluxo de caixa e analytics, todos como novos consumer groups sem tocar no launch-service, o que é o retorno do ADR-0004.
13. Auditoria imutável (ledger append-only com hash encadeado) se houver exigência regulatória.

## Decisão consciente: sem portas e adaptadores na camada de aplicação
Os serviços de aplicação (`EntryService`, `ConsolidationService`) dependem de classes concretas de repositório (`EntryRepository`, `OutboxRepository`, `ConsolidationRepository`), que por sua vez usam `JdbcClient`. Os serviços não usam `JdbcClient` diretamente: o SQL fica isolado nos repositórios. Em DDD/arquitetura hexagonal, os serviços dependeriam de interfaces (portas) definidas pela aplicação e implementadas pela infraestrutura (adaptadores).

Não fiz isso porque, neste escopo, os limites que importam já estão nas fronteiras de processo e de banco (um serviço e um banco por contexto). As garantias da solução também vivem no SQL (constraints, `ON CONFLICT`, `SKIP LOCKED`), e por isso são testadas contra o PostgreSQL real, não contra dublês. Introduziria as portas quando houver um segundo adaptador real (outro banco ou broker) ou necessidade de testar casos de uso sem infraestrutura. Pela mesma razão, não há sidecars: cada componente precisa resolver um problema concreto.

## Decisões que eu revisitaria com dados reais
- Kafka × SQS: se em 6 meses não surgir um segundo consumidor nem necessidade de replay, migrar para SNS/SQS reduz cerca de um quarto do custo estimado de infraestrutura.
- Cache local × Redis: se o hit ratio por réplica cair com muitas réplicas.
- Dois bancos × um cluster com dois databases em homologação, para reduzir custo.
