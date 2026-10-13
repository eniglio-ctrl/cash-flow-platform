# Diagramas

Exportados a partir dos blocos Mermaid dos documentos, em SVG (vetorial) e PNG.

| Diagrama | Documento de origem |
|---|---|
| [00-visao-geral](00-visao-geral.svg) | [README](../../README.md) |
| [01-mapa-de-capacidades](01-mapa-de-capacidades.svg) | [01 Domínios e capacidades](../01-dominio-e-capacidades.md) |
| [02-context-map](02-context-map.svg) | [01 Domínios e capacidades](../01-dominio-e-capacidades.md) |
| [03-c4-contexto](03-c4-contexto.svg) | [03 Arquitetura alvo](../03-arquitetura-alvo.md) |
| [04-c4-containers](04-c4-containers.svg) | [03 Arquitetura alvo](../03-arquitetura-alvo.md) |
| [05-seq-registro-lancamento](05-seq-registro-lancamento.svg) | [03 Arquitetura alvo](../03-arquitetura-alvo.md) |
| [06-seq-consolidacao](06-seq-consolidacao.svg) | [03 Arquitetura alvo](../03-arquitetura-alvo.md) |
| [07-seq-consulta-consolidado](07-seq-consulta-consolidado.svg) | [03 Arquitetura alvo](../03-arquitetura-alvo.md) |
| [08-seq-consolidado-indisponivel](08-seq-consolidado-indisponivel.svg) | [03 Arquitetura alvo](../03-arquitetura-alvo.md) |
| [09-implantacao-aws](09-implantacao-aws.svg) | [03 Arquitetura alvo](../03-arquitetura-alvo.md) |
| [10-transicao-legado](10-transicao-legado.svg) | [06 Arquitetura de transição](../06-arquitetura-de-transicao.md) |

Para regenerar após alterar um diagrama:

```bash
npx -y @mermaid-js/mermaid-cli -i diagrama.mmd -o diagrama.svg
```
