# ADR-0007: Read model pré-agregado e cache local

## Status
Aceito

## Contexto
O consolidado precisa suportar 50 req/s em pico com ≤ 5% de falha.

## Decisão
- Read model `daily_consolidation`: uma linha por comerciante/dia, atualizada incrementalmente pelo consumidor. A consulta lê por PK e não soma lançamentos.
- Cache Caffeine local com TTL de 2 s por `(comerciante, data)`, sem invalidação explícita.
- Réplicas sem estado atrás do gateway, com retry em outra réplica para GET.

## Por que cache local e não Redis
O dado já é eventualmente consistente, então um TTL curto dispensa invalidação. Cada réplica ter o próprio cache é aceitável. O Redis acrescentaria um componente (e um ponto de falha) sem ganho mensurável nessa carga. Passa a fazer sentido com muitas réplicas e hit ratio baixo.

## Resultado medido
A 50 req/s, p95 = 4,5 ms e 0% de erro; a 150 req/s, 0% de erro; com uma réplica morta durante a carga, 0% de erro ([08-testes-e-resultados.md](../08-testes-e-resultados.md)).
