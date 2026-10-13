# 7. Estimativa de custos (infraestrutura e licenças)

## 7.1 Premissas

| Item | Valor adotado |
|---|---|
| Nuvem / região | AWS **sa-east-1 (São Paulo)**: dados financeiros de comerciantes brasileiros, latência e LGPD |
| Modelo de preço | On-demand, sem Savings Plans/Reserved (que reduziriam ~30–40%) |
| Carga | Pico de 50 req/s no consolidado; média estimada de ~5 req/s somando as duas APIs (~13 M req/mês) |
| Volume | ~1 M lançamentos/mês; retenção de 7 dias no Kafka; ~20 GB por banco no 1º ano |
| Disponibilidade | Multi-AZ (2 AZs) em todos os componentes stateful |
| Câmbio | Valores em **USD/mês**; converter na data do orçamento |

> ⚠️ **Os valores são de referência, arredondados, para dimensionar a ordem de grandeza** a partir dos preços públicos on-demand conhecidos. Antes de virar orçamento, devem ser validados na [AWS Pricing Calculator](https://calculator.aws/), porque os preços variam por região e mudam com o tempo. A conclusão de arquitetura (§7.4) não depende da precisão de ±20% dos números.

## 7.2 Licenças

**US$ 0.** Toda a stack é open source com licenças permissivas: OpenJDK/Temurin (GPLv2+CE), Spring (Apache 2.0), PostgreSQL (PostgreSQL License), Apache Kafka (Apache 2.0), Prometheus (Apache 2.0), Grafana OSS (AGPLv3, usado sem modificação), Jaeger (Apache 2.0) e nginx (BSD). Os serviços gerenciados AWS cobram pela infraestrutura, não por licença.

## 7.3 Estimativa por ambiente

### Produção (cenário recomendado)

| Componente | Sizing | USD/mês (aprox.) |
|---|---|---|
| ECS Fargate: launch-service | 2 tasks × 0,5 vCPU / 1 GB | 60 – 70 |
| ECS Fargate: consolidation-service | 2 tasks × 0,5 vCPU / 1 GB (autoscaling até 6) | 60 – 70 (base) |
| RDS PostgreSQL: launch | db.t4g.small Multi-AZ, 20 GB gp3 | 80 – 100 |
| RDS PostgreSQL: consolidation | db.t4g.small Multi-AZ, 20 GB gp3 | 80 – 100 |
| Amazon MSK (provisionado) | 2 brokers kafka.t3.small, 50 GB cada | 110 – 140 |
| API Gateway (HTTP API) | ~13 M req/mês | 15 – 25 |
| ALB interno | 1 ALB + LCUs baixos | 25 – 35 |
| NAT Gateway | 1 (ou VPC endpoints) + tráfego | 40 – 55 |
| WAF | 1 Web ACL + regras gerenciadas | 10 – 20 |
| Observabilidade | Managed Prometheus + Managed Grafana (1–2 editores) + CloudWatch Logs | 40 – 80 |
| Secrets Manager, KMS, ECR, backups | — | 10 – 20 |
| **Total** | | **≈ 530 – 715** |

### Homologação

O mesmo desenho em **Single-AZ**, 1 task por serviço, RDS `db.t4g.micro` e MSK com 2 brokers `t3.small` (ou Kafka em container). Ambientes desligados fora do horário comercial: **≈ 150 – 250 USD/mês**.

### Local / CI

Docker Compose e GitHub Actions (repositório público): **US$ 0**.

## 7.4 Leitura de arquitetura dos números

1. **Mensageria é o maior item isolado (~20% do total)**, e a carga atual não a exigiria. Isso foi pesado conscientemente no [ADR-0004](adr/0004-kafka.md): Kafka foi mantido por replay (reconstrução do read model) e por abrir caminho a novos consumidores (analytics, conciliação). Se o custo for a restrição dominante, **SNS + SQS FIFO** (`MessageGroupId = merchantId`) custaria poucos dólares por mês para esse volume. A troca afeta apenas o relay da Outbox e o listener, porque o contrato de evento e a Outbox continuam iguais.
2. **Multi-AZ dobra o custo dos bancos**, e é aí que o RNF de disponibilidade "se paga". Em homologação, Single-AZ.
3. **Compute é barato** porque os serviços são leves: a carga de 50 req/s cabe numa fração de vCPU. Escalar por disponibilidade, e não por capacidade, mantém o custo baixo.
4. **Alavancas de economia:** Savings Plans para Fargate/RDS (−30–40%), Graviton (já considerado: `t4g`), VPC endpoints no lugar do NAT, e desligar homologação à noite.

## 7.5 Custo marginal de crescimento

| Crescimento | O que escala | Impacto aproximado |
|---|---|---|
| 10× consultas (500 req/s) | +2–4 tasks de consolidation; read replica do RDS | +100 – 200 USD/mês |
| 10× lançamentos | RDS launch para `t4g.medium`; MSK `m7g.large` | +250 – 400 USD/mês |
| Retenção Kafka de 30 dias | Storage MSK | +10 – 30 USD/mês |
