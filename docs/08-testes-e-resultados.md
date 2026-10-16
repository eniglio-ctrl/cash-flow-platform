# 8. Estratégia de testes e resultados medidos

## 8.1 Pirâmide de testes

| Nível | Ferramenta | O que cobre | Onde |
|---|---|---|---|
| Unitário (domínio) | JUnit 5 + AssertJ | Regras RN-01..07, fuso da data financeira, estorno, cálculo do saldo, validação de evento | `*Test.java` |
| Integração | Spring Boot Test + Testcontainers (PostgreSQL 16 e Kafka 3.9 reais) | API completa com JWT real, idempotência (incluindo concorrência), Outbox → Kafka, broker fora, consumo, deduplicação, DLQ, concorrência no UPSERT, isolamento entre comerciantes, 401/403 | `*IT.java` |
| Contrato | Testes sobre o `/v3/api-docs` gerado + checagem no CI de `docs/api/` | Campos obrigatórios, códigos HTTP (201/200/4xx) e headers declarados batem com o comportamento real; cópias versionadas em dia com o código | `contratoOpenApi*` em `EntryApiIT` e `ConsolidationIT`, `scripts/export-openapi.sh` |
| End-to-end / resiliência | Bash + Docker Compose | Cenário crítico: consolidado fora → 100 lançamentos → volta → saldo exato, sem duplicar | `scripts/e2e-resilience.sh` |
| Carga | k6 | RNF de 50 req/s com ≤ 5% de falha; margem de 3x | `loadtest/consolidado-50rps.js` |
| CI | GitHub Actions | Todos os acima a cada push/PR | `.github/workflows/ci.yml` |

Não há mocks de banco ou de broker nos testes de integração. As garantias da solução (constraints, `ON CONFLICT`, `SKIP LOCKED`, offsets) só podem ser verificadas contra a infraestrutura real.

## 8.2 Casos de teste relevantes

| Teste | Garante |
|---|---|
| `EntryApiIT.registraLancamentoEPublicaEventoNoKafkaViaOutbox` | RF-05: evento chega ao Kafka com chave = comerciante e outbox fica `SENT` |
| `IdempotencyRaceIT` (2 testes) | Corrida forçada: a transação A fica aberta com a chave K; B, com a mesma chave, é confirmada como bloqueada no índice único (`pg_stat_activity`); ao liberar A, B devolve o lançamento de A (mesmo conteúdo) ou 409 (conteúdo diferente). Resultado: 1 lançamento e 1 evento na outbox. Validado por mutação: sem a constraint, o teste falha |
| `EntryApiIT.requisicoesConcorrentesComMesmaChaveGeramUmUnicoLancamento` | 10 POSTs paralelos pela API com a mesma chave → 1 lançamento (1×201, 9×200) |
| `EntryApiIT.mesmaChaveComPayloadDiferenteRetorna409` | Reuso indevido da chave é detectado |
| `EntryApiIT.chaveNaoEConsumidaQuandoARequisicaoFalha` | Após um 422, a mesma chave com o payload corrigido registra a operação |
| `EntryApiIT.chaveEUnicaEntreTodasAsEscritasDoComerciante` | Chave de um registro reutilizada num estorno retorna 409 |
| `EntryApiIT.mesmaChaveEmComerciantesDiferentesSaoOperacoesDistintas` | O escopo da chave é o comerciante |
| `EntryApiIT.conteudoEquivalenteAposNormalizacaoEConsideradoOMesmo` | `10` e `10.00`, `Venda` e ` Venda ` são a mesma operação |
| `EntryApiIT.comercianteNaoEnxergaLancamentoDeOutro` | Isolamento multi-tenant |
| `EntryApiIT.estornaUmaUnicaVez` | RN-06/RN-07 |
| `BrokerUnavailableIT` | Com Kafka inacessível: 20/20 lançamentos aceitos e 20 eventos `PENDING` na outbox |
| `ConsolidationIT.eventoDuplicadoNaoAlteraOSaldo` | Mesmo evento 3× → contabilizado 1× |
| `ConsolidationIT.aplicacaoConcorrenteMantemTotaisExatos` | 200 eventos × 2 entregas em 16 threads → totais exatos |
| `ConsolidationIT.eventoInvalidoVaiParaDlqSemBloquearOsDemais` | Poison message na DLQ; o fluxo continua |
| `ConsolidationIT.intervaloPreencheDiasSemMovimentoEEncadeiaSaldos` | Saldo acumulado entre dias |
| `EntryApiIT.contratoOpenApiRefleteValidacoesECodigosReais` | `description` obrigatória no schema; 201/200/400/409/422 declarados no registro; headers `Location` e `Idempotent-Replayed` |
| `ConsolidationIT.contratoOpenApiDeclaraRespostasReais` | 200/400/401/403/429 e header `X-Consistency` no consolidado |

Resultado atual: 33 testes (9 unitários, 22 de integração e 2 de contrato), todos passando (`mvn verify`).

## 8.3 Resultado: cenário crítico de indisponibilidade

```
1) Derrubando o serviço de consolidado...
2) Enviando 100 lançamentos ...
   100/100 lançamentos aceitos com o consolidado fora do ar
3) Reenviando os mesmos 100 lançamentos (mesma Idempotency-Key)...
   reenvios devolveram o lançamento original (200), sem duplicar
4) Religando o consolidado...
   esperado: 100 lançamentos, saldo líquido do dia 350.00
   backlog processado em ~12s: 100 lançamentos, líquido 350.00
SUCESSO: nenhum lançamento perdido ou duplicado.
```

Os ~12 s incluem o boot das duas réplicas do consolidado.

## 8.4 Resultado: teste de carga do consolidado

Ambiente: Docker Desktop num MacBook (Apple Silicon), stack completa via Compose, 2 réplicas do consolidado, 20 comerciantes com 30 dias de histórico, datas aleatórias.

| Cenário | Requisições | Taxa de erro | p95 | Meta |
|---|---|---|---|---|
| 50 req/s por 60 s (requisito) | 3.000 | 0,00% | 4,5 ms | erro < 5% (SLO < 1%), p95 < 200 ms |
| 150 req/s por 60 s (3× margem) | 9.001 | 0,00% | ~3 ms | erro < 5% |
| 50 + 150 req/s com uma réplica morta (`docker kill`) em t=25 s | 12.001 | 0,00% | 3,9 ms | erro < 5% |

O terceiro cenário mostra a combinação de redundância e retry no gateway (`proxy_next_upstream`): a queda de uma instância durante o pico não gerou nenhuma requisição perdida. Saídas completas em `loadtest/results/`.

> Números de laptop, sem rede real entre os componentes. Servem para validar o desenho e a ausência de gargalos, não como benchmark de produção. Em produção, o mesmo script roda contra homologação como gate de release.

## 8.5 Atualização do consolidado (operação normal)

O demo (`scripts/demo.sh`) registrou um lançamento às `21:51:50.157` e o consolidado refletiu às `21:51:50.797`: ~0,6 s, dominados pelo intervalo de polling da Outbox (500 ms). Meta: p95 < 5 s.

## 8.6 Como reproduzir

```bash
mvn verify                                   # unitários + integração (requer Docker)
docker compose up -d --build --wait          # stack
./scripts/e2e-resilience.sh                  # cenário crítico
docker compose --profile loadtest run --rm k6   # carga
```
