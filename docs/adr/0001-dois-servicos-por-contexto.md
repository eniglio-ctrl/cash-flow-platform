# ADR-0001 — Dois serviços, um por bounded context

**Status:** Aceito

## Contexto
O RNF exige que o registro de lançamentos **não fique indisponível se o consolidado cair**. As duas capacidades têm perfis opostos: escrita transacional crítica versus leitura derivada com picos.

## Decisão
Dois serviços implantáveis de forma independente (`launch-service` e `consolidation-service`), cada um com **seu próprio banco PostgreSQL**. Nenhum acessa o banco do outro.

## Alternativas
| Alternativa | Por que não |
|---|---|
| **Monólito modular** | Bom para um MVP, mas compartilha processo, pool de conexões, heap e deploy: um pico de leitura no consolidado (ou um vazamento de memória) derruba o registro. O isolamento exigido pelo RNF ficaria só no código. |
| Mais microsserviços (ex.: separar API de leitura e consumer) | Sem requisito que justifique; dobra o custo operacional. Pode ser feito depois sem mudar contratos. |
| Serverless (Lambda) | Viável, mas cold start no caminho de 50 req/s, Outbox menos natural (precisaria de DynamoDB Streams/CDC) e mais lock-in. |
| Banco compartilhado com schemas separados | Reduz custo, mas cria acoplamento de disponibilidade e de capacidade no banco. Aceitável em homologação, não em produção. |

## Consequências
- ➕ Isolamento real de falha, escala e deploy.
- ➖ Consistência eventual entre os contextos e mais infraestrutura (mitigados por ADR-0002, 0003 e 0006).
- ➖ Algum código duplicado (configuração de segurança), aceito para preservar a independência. Uma biblioteca compartilhada só se justificaria com mais serviços.
