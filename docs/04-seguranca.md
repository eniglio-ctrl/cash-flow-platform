# 4. Segurança

## 4.1 Modelo de ameaças (STRIDE resumido)

| Ameaça | Exemplo | Mitigação |
|---|---|---|
| Spoofing | Chamar a API se passando por outro comerciante | JWT assinado (validação de assinatura, `iss`, `aud`, `exp`); o `merchant_id` vem do `sub` do token, nunca do payload. |
| Tampering | Alterar o valor de um lançamento já registrado | Lançamentos imutáveis (sem PUT/DELETE); correção só por estorno; TLS em trânsito. |
| Repudiation | "Não fui eu que lancei" | Trilha: `created_at`, `traceId`/`X-Request-Id` nos logs, evento imutável no Kafka (retenção) e lançamento imutável no banco. |
| Information disclosure | Ler o saldo de outro comerciante | Todas as consultas filtram por `merchant_id` do token. Um id de outro comerciante devolve 404, sem revelar que o recurso existe (teste `comercianteNaoEnxergaLancamentoDeOutro`). |
| Denial of service | Rajada de requisições | Rate limit por token no gateway (429), payload ≤ 16 KB, timeouts, pool de conexões limitado, paginação com teto (200). |
| Elevation of privilege | Token de leitura gravando lançamento | Escopos por operação: `lancamentos:write`, `lancamentos:read`, `consolidado:read`, `consolidado:admin`; `denyAll()` por padrão. |

## 4.2 Autenticação e autorização

- Padrão: OAuth 2.0 / OIDC. Os serviços são *resource servers* sem estado que validam o JWT localmente (sem chamada ao IdP por requisição).
- Local: tokens HS256 emitidos por `POST /dev/token`, que só existe com o profile `local`. Isso evita exigir um Keycloak para avaliar o projeto.
- Produção: RS256/ES256 validados via JWKS do IdP corporativo (Cognito, Entra ID ou Keycloak). A mudança se limita ao bean `JwtDecoder` (`NimbusJwtDecoder.withJwkSetUri`), sem tocar nos controllers.
- Escopos:

| Escopo | Permite |
|---|---|
| `lancamentos:write` | POST de lançamento e estorno |
| `lancamentos:read` | GET de lançamentos |
| `consolidado:read` | GET de consolidado |
| `consolidado:admin` | Reprocessar DLQ (operação) |

## 4.3 Critérios de segurança para consumo/integração de serviços

| Critério | Norte-sul (cliente → API) | Leste-oeste (serviço ↔ serviço / broker) |
|---|---|---|
| Transporte | TLS 1.2+ terminado no gateway; HSTS | mTLS dentro da malha (service mesh ou ALB interno + TLS) |
| Identidade | JWT de usuário/aplicação (OIDC) | OAuth2 *client credentials* por serviço; Kafka com SASL/SCRAM ou IAM (MSK) |
| Autorização | Escopo por rota + isolamento por `merchant_id` | ACL por tópico: só o `launch-service` produz em `cashflow.entries.v1`; só o grupo `consolidation-service` consome; só a operação lê a DLQ |
| Contrato | OpenAPI versionado (`/v1`) | Evento versionado (`version`, tópico `.v1`); consumidor rejeita versão desconhecida (vai à DLQ) e nunca aplica um contrato que não entende |
| Validação | Bean Validation + regras de domínio + chave de idempotência com padrão restrito | Revalidação do evento no consumidor (não confia cegamente no produtor) |
| Proteção | WAF (OWASP Top 10), rate limit, limite de payload | Quotas de produtor/consumidor no broker |
| Idempotência | `Idempotency-Key` obrigatória nos POSTs | `eventId` deduplicado no consumidor |
| Segredos | Fora do código: Secrets Manager / Vault, injetados como env; rotação | Credenciais do broker e do banco por serviço, com menor privilégio |

## 4.4 Dados

- Em trânsito: TLS em todas as conexões em produção (cliente, banco e broker).
- Em repouso: RDS e MSK com criptografia KMS; backups criptografados.
- Menor privilégio no banco: em produção, um usuário de migração (DDL) separado do de aplicação (DML apenas). O serviço de consolidação não tem nenhum acesso ao banco de lançamentos.
- Logs: sem payload financeiro completo nem tokens; apenas ids (`entryId`, `eventId`, `traceId`).
- Precisão: `NUMERIC(19,2)` e `BigDecimal` de ponta a ponta. O valor trafega como string no evento.

## 4.5 Cadeia de suprimentos e execução

- Imagem base JRE (sem JDK/Maven em runtime), usuário não-root (uid 10001).
- Versões fixadas de dependências e imagens; em produção, scan de imagem (ECR/Trivy) e SCA (Dependabot) no CI.
- `server.shutdown=graceful` para não cortar transações em deploy.

## 4.6 O que é simplificado localmente (e por quê)

| Local | Produção |
|---|---|
| HS256 com segredo compartilhado e `/dev/token` | IdP corporativo, RS256 + JWKS |
| HTTP sem TLS na rede do Compose | TLS na borda + mTLS interno |
| Kafka PLAINTEXT, sem ACL | SASL/IAM + ACLs por tópico |
| Credenciais no `docker-compose.yml` | Secrets Manager |
