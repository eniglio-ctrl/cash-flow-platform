# 2. Requisitos refinados

O enunciado traz dois requisitos de negócio e um único RNF quantitativo. Esta seção transforma esses itens em requisitos verificáveis e torna explícitas as premissas.

## 2.1 Premissas

| # | Premissa | Impacto se estiver errada |
|---|---|---|
| P-01 | Cada comerciante só acessa os próprios dados (multi-tenant lógico). | Modelo de autorização. |
| P-02 | Moeda única (BRL), duas casas decimais. | Adicionar `currency` ao lançamento e ao consolidado. |
| P-03 | A data financeira segue o fuso do comerciante (`America/Sao_Paulo`). | Fuso passa a ser atributo do comerciante. |
| P-04 | "Saldo diário consolidado" = saldo acumulado (inicial + movimento do dia = final), não só o líquido do dia. As duas visões são entregues. | Nenhum: o líquido do dia também é retornado. |
| P-05 | Os 50 req/s em pico são consultas ao consolidado. A "perda de requisições" é a taxa de requisições que falham ou não são atendidas. | Ver §2.4. |
| P-06 | Atraso de alguns segundos entre o registro e o reflexo no consolidado é aceitável. | Se não for, seria preciso consolidar de forma síncrona, o que fere o RNF de independência. |
| P-07 | Lançamentos são imutáveis; correções são feitas por estorno. | Prática contábil/auditoria. |

## 2.2 Requisitos funcionais

Todos os requisitos abaixo estão implementados e cobertos por testes.

| ID | Requisito | Critério de aceite |
|---|---|---|
| RF-01 | Registrar lançamento | `POST /api/v1/lancamentos` com data, tipo (CREDIT/DEBIT), valor e descrição. Retorna 201 com `Location`. |
| RF-02 | Idempotência no registro | A mesma `Idempotency-Key` com o mesmo payload retorna o lançamento original (200, `Idempotent-Replayed: true`). Com payload diferente, 409. Requisições concorrentes geram um único lançamento. |
| RF-03 | Estornar lançamento | `POST /lancamentos/{id}/estornos` cria lançamento oposto na data de hoje. Cada lançamento só pode ser estornado uma vez; um estorno não pode ser estornado. |
| RF-04 | Consultar lançamentos | Por id e por data (paginado). |
| RF-05 | Publicar fato financeiro | Todo lançamento confirmado gera exatamente um evento durável (Outbox), mesmo com o broker fora no momento do registro. |
| RF-06 | Consolidar por dia | Créditos, débitos, líquido, saldo inicial e final; nº de lançamentos. |
| RF-07 | Consultar consolidado | Por dia (`/consolidados/{data}`) e por período de até 31 dias (`/consolidados?from&to`). Dias sem movimento aparecem com saldo encadeado. |
| RF-08 | Reprocessar falhas | Eventos que falham vão para a DLQ; `POST /api/v1/admin/dlq/replay` os devolve ao fluxo. |

## 2.3 Regras de negócio

| ID | Regra |
|---|---|
| RN-01 | O valor é positivo, com no máximo 2 casas decimais e no máximo 999.999.999,99. O tipo define o sinal. |
| RN-02 | A descrição é obrigatória, com até 200 caracteres. |
| RN-03 | Não se aceita lançamento com data futura (no fuso do comerciante). |
| RN-04 | Lançamentos retroativos são aceitos até 365 dias atrás e atualizam o consolidado daquele dia e, por consequência, o saldo dos dias seguintes. |
| RN-05 | A data financeira é calculada no fuso do comerciante, não em UTC. |
| RN-06 | Um lançamento não é alterado nem excluído; a correção é feita por estorno. |
| RN-07 | Um lançamento só pode ser estornado uma vez; um estorno não pode ser estornado. |

## 2.4 Requisitos não funcionais (com metas mensuráveis)

| ID | Atributo | Meta / SLO | Como é garantido | Como é verificado |
|---|---|---|---|---|
| RNF-01 | Independência de disponibilidade | Lançamentos continuam operando com o consolidado ou o broker fora. | Sem chamada síncrona entre os serviços; Outbox; health do launch não depende do Kafka. | `scripts/e2e-resilience.sh`, `BrokerUnavailableIT` |
| RNF-02 | Carga do consolidado | 50 req/s sustentados em pico, com margem testada de 3x (150 req/s). | Read model pré-calculado, cache de TTL curto, réplicas sem estado, gateway com balanceamento. | k6 (`loadtest/`) |
| RNF-03 | Perda de requisições | Limite de negócio ≤ 5%; SLO interno ≤ 1% de erro no consolidado. | Réplicas e retry no gateway para GETs; rate limit devolve 429 controlado em vez de colapso. | k6 com thresholds e réplica derrubada durante a carga |
| RNF-04 | Latência | p95 < 200 ms no GET consolidado; p95 < 300 ms no POST lançamento. | Consulta por chave primária e cache. | k6 e Grafana |
| RNF-05 | Disponibilidade (produção) | Lançamentos 99,9%/mês; consolidado 99,5%/mês. | Multi-AZ, ≥ 2 réplicas, banco Multi-AZ. | SLO no Grafana/alertas |
| RNF-06 | Atualização do consolidado | p95 < 5 s entre o registro e o reflexo no saldo, em operação normal. | Relay da Outbox a cada 500 ms; consumo paralelo por partição. | Métrica `cashflow_consolidation_freshness` |
| RNF-07 | Durabilidade | RPO = 0 para lançamentos confirmados (201); nenhum evento perdido. | ACID + Outbox + `acks=all` + idempotência do produtor. | Testes de integração e e2e |
| RNF-08 | Recuperação | RTO < 15 min (produção); backlog drenado automaticamente após retorno. | Restart automático, offsets no Kafka, retenção de 7 dias. | e2e: 100 eventos drenados em ~12 s |
| RNF-09 | Integridade | Nenhuma dupla contabilização. | Deduplicação por `event_id` na mesma transação da atualização; UPSERT atômico. | `ConsolidationIT` (400 entregas concorrentes de 200 eventos) |
| RNF-10 | Segurança | Autenticação obrigatória, autorização por escopo, isolamento por comerciante, TLS em trânsito, segredos fora do código. | Ver [04-seguranca.md](04-seguranca.md). | Testes 401/403/isolamento |
| RNF-11 | Observabilidade | Métricas RED, logs estruturados com `traceId`, tracing distribuído, alertas de SLO. | Ver [05-observabilidade.md](05-observabilidade.md). | Dashboard provisionado |
| RNF-12 | Escalabilidade | Escala horizontal sem mudança de código. | Serviços sem estado; partições por comerciante; `SKIP LOCKED` no relay. | Compose com 2 réplicas |

### Interpretação do "no máximo 5% de perda"

Duas leituras são possíveis, e as duas são atendidas:

1. Requisições HTTP ao consolidado que falham (a leitura principal: o enunciado diz que o serviço "recebe 50 requisições por segundo"). A meta é ≤ 1% (folga de 5x sobre o limite). Resultado medido: 0% de erro a 50 req/s e a 150 req/s, inclusive com uma réplica derrubada no meio do teste ([08-testes-e-resultados.md](08-testes-e-resultados.md)).
2. Eventos de lançamento perdidos a caminho do consolidado. A arquitetura não aceita perda nenhuma: Outbox, broker durável e consumo idempotente dão perda 0. Os 5% nunca são usados como "orçamento" para perder dado financeiro.

## 2.5 Fora de escopo (conscientemente)

- Fechamento de caixa/período contábil (bloquear lançamentos em dias fechados). Ver evoluções.
- Múltiplas moedas, conciliação bancária e anexos de comprovante.
- Cadastro de comerciantes (assume-se que o IdP é a fonte).
