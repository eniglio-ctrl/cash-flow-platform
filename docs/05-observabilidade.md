# 5. Monitoramento e observabilidade

A observabilidade deve responder rapidamente: quantos lançamentos entraram, quantos eventos estão pendentes, qual o atraso do consolidado e onde está a falha.

## 5.1 Os três pilares

| Pilar | Implementação | Acesso local |
|---|---|---|
| Métricas | Micrometer → `/actuator/prometheus` → Prometheus (descobre réplicas via DNS) | http://localhost:9090 |
| Dashboards | Grafana com dashboard provisionado "Cashflow — Visão geral" | http://localhost:3000 |
| Traces | Micrometer Tracing + OpenTelemetry (OTLP) → Jaeger; propagação HTTP e Kafka | http://localhost:16686 |
| Logs | JSON estruturado (formato logstash) com `traceId`/`spanId`; o gateway loga `X-Request-Id` | `docker compose logs` |

> As portas são configuráveis via `GATEWAY_PORT`, `PROMETHEUS_PORT`, `GRAFANA_PORT` e `JAEGER_PORT`.

## 5.2 Métricas de negócio e de plataforma

| Métrica | Tipo | Pergunta que responde |
|---|---|---|
| `cashflow_entries_registered_total{type,reversal}` | counter | Quantos lançamentos entraram? |
| `cashflow_outbox_pending` | gauge | Quantos eventos aguardam o broker? (cresce = broker com problema) |
| `cashflow_outbox_published_total` / `_failures_total` | counter | O relay está publicando? |
| `cashflow_idempotency_races_total` | counter | Quantas requisições com a mesma chave chegaram juntas e foram resolvidas pela constraint única? |
| `cashflow_consolidation_events_total{result=applied\|duplicate}` | counter | Taxa de aplicação e de duplicatas |
| `cashflow_consolidation_freshness_seconds` | histogram | Quanto tempo leva do registro ao saldo? (SLO p95 < 5 s) |
| `cashflow_consolidation_dlq_total` | counter | Algum evento foi para a DLQ? |
| `kafka_consumer_fetch_manager_records_lag_max` | gauge | Backlog do consolidado |
| `http_server_requests_seconds{uri,status}` | histogram | RED: taxa, erros, latência por endpoint |
| `cache_gets_total{result}` | counter | Hit ratio do cache do consolidado |

## 5.3 SLOs e alertas

Definidos em [`infrastructure/prometheus/alerts.yml`](../infrastructure/prometheus/alerts.yml):

| Alerta | Condição | Severidade |
|---|---|---|
| `ConsolidadoTaxaDeErroAcimaDoSLO` | 5xx no GET consolidado > 1% por 5 min | page |
| `ConsolidadoLatenciaP95Alta` | p95 > 200 ms por 10 min | ticket |
| `LancamentosIndisponivel` | `up == 0` por 1 min | page |
| `OutboxAcumulando` | pendentes > 1000 por 5 min | page |
| `ConsolidadoDefasado` | consumer lag > 5000 por 5 min | ticket |
| `MensagensNaDLQ` | qualquer evento na DLQ em 10 min | ticket |

Error budget (produção): lançamentos com 99,9%/mês ≈ 43 min; consolidado com 99,5%/mês ≈ 3,6 h. Estourar o budget congela features em favor de confiabilidade.

## 5.4 Health checks

- `/actuator/health/liveness` e `/actuator/health/readiness` são separados dos endpoints de negócio.
- No `launch-service`, o Kafka não compõe a saúde (`management.health.kafka.enabled=false`). Com o broker fora, o serviço continua *ready* e aceitando lançamentos. Se o health check incluísse o broker, o orquestrador mataria o serviço justamente no cenário em que ele deve continuar de pé.

## 5.5 Runbook resumido

| Sintoma | Diagnóstico | Ação |
|---|---|---|
| `OutboxAcumulando` | Kafka acessível? Ver `cashflow_outbox_failures_total` e o log `Falha ao publicar evento`. | Restabelecer o broker; o relay drena sozinho. |
| `ConsolidadoDefasado` | Réplicas do consolidado de pé? Banco do consolidado OK? | Escalar réplicas (≤ nº de partições) ou corrigir o banco. |
| `MensagensNaDLQ` | Ler a mensagem na DLQ (headers `kafka_dlt-exception-*`). | Corrigir a causa e chamar `POST /api/v1/admin/dlq/replay`. |
| Saldo divergente | Comparar `SUM` dos lançamentos com o consolidado do dia. | Reconstruir o read model (replay do tópico desde o início com um novo consumer group). |
