# Cash Flow Platform

Controle de fluxo de caixa de um comerciante: registro de lançamentos (débitos e créditos) e saldo diário consolidado.

Solução para o desafio de Arquiteto de Soluções. As decisões de arquitetura estão documentadas em [`docs/`](docs/), e o código implementa a arquitetura alvo de forma executável: a stack completa sobe com um único comando.

```mermaid
flowchart LR
    C([Cliente]) --> G[Gateway<br/>rate limit · retry]
    G -->|POST/GET /lancamentos| L[launch-service]
    G -->|GET /consolidados| S[consolidation-service ×2]
    L -->|1 transação:<br/>lançamento + outbox| LDB[(PostgreSQL<br/>launch)]
    L -->|relay da outbox| K{{Kafka}}
    K -->|consumo idempotente| S
    S --> SDB[(PostgreSQL<br/>consolidation)]
```

<sub>Versão em imagem: [00-visao-geral.svg](docs/diagrams/00-visao-geral.svg)</sub>

## Principais decisões

Resumo de como cada requisito central foi tratado. Os detalhes e as alternativas descartadas estão nos [ADRs](docs/adr/).

| Requisito | Decisão | Evidência |
|---|---|---|
| Lançamentos não podem cair se o consolidado cair | Dois serviços com bancos próprios, integração assíncrona e Transactional Outbox. O launch não depende nem do broker. | `e2e-resilience.sh`: 100/100 aceitos com o consolidado fora; saldo exato ao voltar |
| Consolidado com 50 req/s e ≤ 5% de perda | Read model pré-agregado, cache de TTL curto, réplicas sem estado, gateway com retry e rate limit | k6: 0% de erro a 50 req/s (p95 3,6 ms) e a 150 req/s (p95 2,3 ms); 0% com uma réplica morta durante a carga ([execuções versionadas](loadtest/results/)) |
| Dinheiro não pode duplicar nem sumir | Idempotência na API (`Idempotency-Key`) e no consumidor (`event_id` na mesma transação do saldo); `NUMERIC(19,2)` | Testes com 10 POSTs concorrentes e 400 entregas paralelas |
| Correções auditáveis | Lançamentos imutáveis; correção por estorno | `EntryApiIT.estornaUmaUnicaVez` |

## Como rodar

Pré-requisitos: Docker (com Compose v2). Para os scripts: `curl` e `jq`. Para rodar testes fora do Docker: JDK 21 e Maven 3.9.

```bash
git clone https://github.com/eniglio-ctrl/cash-flow-platform.git && cd cash-flow-platform
docker compose up -d --build --wait
```

O primeiro build baixa as dependências Maven dentro do container (alguns minutos).

> Este Compose é exclusivamente para desenvolvimento e demonstração local. Todas as portas são publicadas apenas em `127.0.0.1`, para que nada fique acessível pela rede local, porque o ambiente ativa o emissor de tokens de teste (`/dev/token`) e usa credenciais conhecidas.

Depois disso:

| O quê | URL |
|---|---|
| API (via gateway) | http://localhost:8080 |
| Swagger UI (as duas APIs, com "Try it out") | http://localhost:8080/swagger/ |
| Contratos OpenAPI | http://localhost:8080/openapi/launch.json · http://localhost:8080/openapi/consolidation.json (cópias versionadas em [`docs/api/`](docs/api/)) |
| Grafana (dashboard "Cashflow — Visão geral") | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Jaeger (traces) | http://localhost:16686 |

> Se alguma porta estiver ocupada, todas são configuráveis: `GATEWAY_PORT=18080 GRAFANA_PORT=13000 PROMETHEUS_PORT=19090 JAEGER_PORT=26686 docker compose up -d --build --wait`. Ao mudar a porta do gateway, exporte também `BASE_URL=http://localhost:18080` antes de rodar os scripts.

### Demonstração

```bash
./scripts/demo.sh
```

Esse script obtém um token, registra um crédito e um débito, estorna o débito e mostra o consolidado do dia.

### Cenário crítico (consolidado fora do ar)

```bash
./scripts/e2e-resilience.sh
```

### Teste de carga (50 req/s e 150 req/s)

```bash
docker compose --profile loadtest run --rm k6
```

### Testes automatizados

```bash
mvn verify
```

São 33 testes: unitários, de integração com PostgreSQL e Kafka reais via Testcontainers (requer Docker) e de contrato, que verificam se o OpenAPI gerado declara as validações e os códigos HTTP que a API de fato retorna.

### Encerrar

```bash
docker compose down -v
```

## API

Todas as rotas exigem `Authorization: Bearer <jwt>`. Localmente, o token é obtido em `POST /dev/token`, que existe só no profile `local` e substitui o IdP. O comerciante é sempre o `sub` do token.

```bash
TOKEN=$(curl -s -X POST localhost:8080/dev/token | jq -r .access_token)

# Registrar (Idempotency-Key obrigatória: reenviar a mesma chave não duplica)
curl -X POST localhost:8080/api/v1/lancamentos \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: venda-0001-2026-10-13' \
  -d '{"date":"2026-10-13","type":"CREDIT","amount":150.75,"description":"Venda balcão"}'

# Consolidado do dia
curl localhost:8080/api/v1/consolidados/2026-10-13 -H "Authorization: Bearer $TOKEN"
```

| Método | Rota | Escopo | Descrição |
|---|---|---|---|
| POST | `/api/v1/lancamentos` | `lancamentos:write` | Registra crédito/débito. 201 novo · 200 reenvio idempotente · 409 chave reutilizada com outro payload · 422 regra de negócio |
| POST | `/api/v1/lancamentos/{id}/estornos` | `lancamentos:write` | Estorna (lançamento inverso na data de hoje). 201 · 200 idempotente · 404 · 409 · 422 já estornado |
| GET | `/api/v1/lancamentos/{id}` | `lancamentos:read` | Consulta lançamento. 200 · 404 |
| GET | `/api/v1/lancamentos?date=AAAA-MM-DD&page&size` | `lancamentos:read` | Lista por data. 200 · 400 |
| GET | `/api/v1/consolidados/{data}` | `consolidado:read` | Saldo inicial, créditos, débitos, líquido, saldo final. 200 · 400 |
| GET | `/api/v1/consolidados?from&to` | `consolidado:read` | Período (máx. 31 dias). 200 · 400 |
| POST | `/api/v1/admin/dlq/replay?max=100` | `consolidado:admin` | Reprocessa eventos da DLQ. 200 |

Em todas as rotas protegidas também podem ocorrer 401 (token ausente ou inválido), 403 (escopo insuficiente) e 429 (rate limit do gateway). Limites aplicados pelo gateway (nginx `limit_req` com `nodelay`):

| Rota | Chave do limite | Taxa sustentada | Rajada extra (burst) |
|---|---|---|---|
| `/api/v1/lancamentos` | token | 100 req/s | 50 |
| `/api/v1/consolidados` | token | 200 req/s | 100 |
| `/api/v1/admin/` | token | 1 req/s | 2 |
| `/dev/token` | IP | 5 req/s | 20 |

O burst é a quantidade de requisições que pode passar de uma vez acima da taxa; com `nodelay`, elas são atendidas imediatamente, sem fila. Assim, numa rajada simultânea à rota administrativa, até 3 chamadas são aceitas (1 da taxa + 2 de burst) e as demais recebem 429. A capacidade de burst se recompõe na taxa sustentada. Os erros seguem o formato RFC 9457 (`application/problem+json`). Campos obrigatórios no registro: `date`, `type`, `amount` e `description`.

Exemplo de resposta do consolidado:

```json
{
  "date": "2026-10-13",
  "openingBalance": 0.00,
  "totalCredits": 620.40,
  "totalDebits": 120.40,
  "netAmount": 500.00,
  "closingBalance": 500.00,
  "entriesCount": 3,
  "lastUpdatedAt": "2026-10-13T21:51:50.797375Z"
}
```

### Contrato de idempotência

Toda escrita (`POST /lancamentos` e `POST /lancamentos/{id}/estornos`) exige o header `Idempotency-Key`. Ele protege contra lançamentos duplicados quando o cliente não sabe se a requisição anterior foi processada (timeout, queda de rede, 5xx).

| Regra | Comportamento |
|---|---|
| Quem gera | O cliente (consumidor da API). O servidor nunca gera nem substitui a chave. |
| Formato | 8 a 100 caracteres: letras, dígitos, `_ - : .`. Recomendado: UUID v4. |
| Nova operação de negócio | Nova chave. |
| Retry da mesma operação | Reutilizar a mesma chave, com o mesmo conteúdo. |
| Escopo | Única por comerciante (`UNIQUE(merchant_id, idempotency_key)`), compartilhada entre registro e estorno. Comerciantes diferentes podem usar o mesmo valor. |
| Mesma chave, mesmo conteúdo | `200 OK` com o lançamento original e o header `Idempotent-Replayed: true`. Nada é gravado de novo e nenhum evento novo é publicado. |
| Mesma chave, conteúdo diferente | `409 Conflict`. |
| Comparação de conteúdo | Hash SHA-256 do conteúdo normalizado: `10` e `10.00` são o mesmo valor, e espaços nas pontas da descrição são ignorados. |
| Requisição rejeitada (400, 401, 403, 422) | A chave não é registrada: o cliente pode corrigir o payload e reenviar com a mesma chave. |
| Requisições simultâneas com a mesma chave | Uma retorna 201; as demais, 200 com o mesmo lançamento (ou 409, se o conteúdo for diferente). Quem decide é a constraint única no banco, não a aplicação. |
| Validade | A chave não expira: fica gravada junto com o lançamento. |

Fluxo recomendado no cliente:

```text
1. Gerar uma chave nova para a operação (ex.: UUID v4) e guardá-la junto com a intenção.
2. Enviar o POST.
3. Timeout, erro de rede ou 5xx  → reenviar com a MESMA chave e o MESMO conteúdo.
4. 201 ou 200                    → operação concluída; descartar a chave.
5. 409                           → bug no cliente: a chave foi reutilizada para outra operação.
6. 400 ou 422                    → corrigir o payload; pode reenviar com a mesma chave.
```

Cada regra acima é verificada por testes de integração (`EntryApiIT`). A concorrência tem um teste determinístico (`IdempotencyRaceIT`): ele mantém aberta a transação de uma requisição, confirma pelo `pg_stat_activity` que a segunda está bloqueada no índice único e só então libera a primeira. O teste falha se a constraint for removida. Os detalhes de implementação estão no [ADR-0006](docs/adr/0006-idempotencia-ponta-a-ponta.md).

### Testando pelo navegador (Swagger UI)

Acesse http://localhost:8080/swagger/. A página já abre autorizada: no ambiente local ela obtém sozinha um token de teste em `POST /dev/token` e o reaproveita até expirar. O seletor no topo alterna entre as APIs de Lançamentos e de Consolidado diário.

1. Em Lançamentos, abra `POST /api/v1/lancamentos` e clique em Execute. O corpo e a `Idempotency-Key` já vêm preenchidos com exemplos. Executar de novo com a mesma chave devolve 200 com o lançamento original, o que demonstra a idempotência.
2. Em Consolidado diário, execute `GET /api/v1/consolidados/{date}` com a data do lançamento.

Para testar outro comerciante, use Authorize > Logout e recarregue a página.

As chamadas passam pelo gateway, com o mesmo roteamento e rate limit do uso real.

### Contratos OpenAPI

Os contratos são gerados a partir do código (springdoc) e servidos pelo gateway em `/openapi/launch.json` e `/openapi/consolidation.json`. Cópias versionadas ficam em [`docs/api/`](docs/api/). Cada contrato declara dois servidores: o relativo `/`, usado pela Swagger UI, e `http://localhost:8080`, para quem abre o arquivo fora do gateway (ex.: Swagger Editor).

Para atualizar as cópias após mudar a API (com a stack no ar):

```bash
./scripts/export-openapi.sh
```

O CI executa esse script e falha se o resultado divergir do que está versionado, e os testes de contrato falham se o OpenAPI deixar de refletir as validações e os códigos HTTP reais.

## Limites do escopo

O que foi entregue é a arquitetura alvo completa, documentada, e uma implementação executável dela em ambiente local. Alguns pontos são deliberadamente simplificados para que a avaliação rode com um único comando:

- O alvo de produção (AWS: ECS Fargate, RDS Multi-AZ, MSK, API Gateway e WAF) está desenhado em [docs/03 §3.10](docs/03-arquitetura-alvo.md#310-arquitetura-de-implantação-produção-aws) e custeado em [docs/07](docs/07-custos.md), mas não foi demonstrado por uma implantação real em nuvem. Não há código de infraestrutura (Terraform) neste repositório.
- A autenticação local usa um segredo HS256 conhecido, publicado no repositório, e o `POST /dev/token` emite tokens para qualquer comerciante e com os escopos que forem pedidos. Esse endpoint só existe no profile `local` e não deve ser exposto fora de uma máquina de desenvolvimento.
- Por isso o `docker-compose.yml` publica todas as portas apenas em `127.0.0.1` e se declara, no cabeçalho, como ambiente exclusivo de desenvolvimento.
- Kafka roda com um único broker, sem TLS, sem autenticação e sem ACLs, e as credenciais dos bancos estão no `docker-compose.yml`. Os controles equivalentes de produção estão descritos em [docs/04 §4.6](docs/04-seguranca.md#46-o-que-é-simplificado-localmente-e-por-quê).
- A garantia de não perder lançamentos confirmados vale para falhas do broker, do consolidado e da aplicação. Frente à perda do próprio banco de lançamentos, o RPO depende da replicação do RDS Multi-AZ em produção (desenhada, não implantada); localmente não há proteção contra a perda do volume. Detalhes em [docs/02 RNF-07](docs/02-requisitos.md#escopo-da-garantia-de-durabilidade-rnf-07).
- Os resultados de carga e resiliência foram medidos num laptop, com todos os componentes no mesmo host. Validam o desenho e a ausência de gargalos, não substituem um teste de capacidade no ambiente de produção.
- Os valores de custo são estimativas a partir de preços públicos, não uma fatura real.

O que eu faria a seguir está em [docs/09](docs/09-evolucoes-futuras.md).

## Documentação de arquitetura

| # | Documento | Conteúdo |
|---|---|---|
| 1 | [Domínios e capacidades](docs/01-dominio-e-capacidades.md) | Mapa de capacidades, subdomínios (core/genérico), linguagem ubíqua, context map |
| 2 | [Requisitos](docs/02-requisitos.md) | Premissas, RF, regras de negócio, RNF com metas mensuráveis, interpretação dos 5% |
| 3 | [Arquitetura alvo](docs/03-arquitetura-alvo.md) | C4 contexto/containers, sequências, modelo de dados, contrato do evento, escalabilidade, matriz de falhas, stack, implantação AWS |
| 4 | [Segurança](docs/04-seguranca.md) | STRIDE, authN/authZ, critérios de segurança para integração de serviços |
| 5 | [Observabilidade](docs/05-observabilidade.md) | Métricas, SLOs, alertas, health checks, runbook |
| 6 | [Arquitetura de transição](docs/06-arquitetura-de-transicao.md) | Migração de legado: Strangler Fig + ACL + CDC em fases |
| 7 | [Custos](docs/07-custos.md) | Estimativa AWS sa-east-1 por ambiente, licenças, alavancas de economia |
| 8 | [Testes e resultados](docs/08-testes-e-resultados.md) | Pirâmide, casos relevantes, resultados de carga e resiliência |
| 9 | [Evoluções futuras](docs/09-evolucoes-futuras.md) | Roadmap e decisões a revisitar |
| — | [ADRs](docs/adr/) | 10 decisões com contexto, alternativas e consequências |
| — | [Diagramas](docs/diagrams/) | Todos os diagramas exportados em SVG e PNG |
| — | [Contratos OpenAPI](docs/api/) | Especificação das duas APIs |

## Estrutura do repositório

```
cash-flow-platform/
├── services/
│   ├── launch-service/          # Contexto Gestão de Lançamentos (API + Outbox relay)
│   └── consolidation-service/   # Contexto Consolidação (consumer + read model + API de leitura)
├── infrastructure/
│   ├── gateway/                 # nginx: roteamento, rate limit, retry, Swagger UI
│   ├── prometheus/              # scrape + regras de alerta (SLOs)
│   └── grafana/                 # datasource e dashboard provisionados
├── loadtest/                    # k6 (50 e 150 req/s) + resultados
├── scripts/                     # demo, cenário e2e de resiliência e exportação dos contratos OpenAPI
├── docs/                        # arquitetura, requisitos, ADRs, custos...
├── docker-compose.yml
├── Dockerfile                   # build multi-stage (SERVICE=launch-service|consolidation-service)
└── .github/workflows/ci.yml     # build, testes, e2e e carga no CI
```

Cada serviço segue camadas simples: `domain` (regras, sem framework) → `application` (casos de uso, transações) → `api` / `infrastructure` (HTTP, JDBC, Kafka, segurança).

## Stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Apache Kafka 3.9 (KRaft) · Flyway · Caffeine · nginx · Micrometer/Prometheus/Grafana · OpenTelemetry/Jaeger · JUnit 5 · Testcontainers · k6 · Docker Compose · GitHub Actions. Justificativas em [docs/03 §3.9](docs/03-arquitetura-alvo.md#39-stack-e-justificativas) e nos [ADRs](docs/adr/).
