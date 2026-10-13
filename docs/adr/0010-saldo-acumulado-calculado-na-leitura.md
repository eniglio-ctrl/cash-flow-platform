# ADR-0010: Saldo inicial calculado na leitura

## Status
Aceito

## Contexto
O saldo final de um dia depende de todos os dias anteriores. Lançamentos retroativos (RN-04) mudam o saldo de todos os dias seguintes.

## Decisão
Persistir apenas o movimento do dia (créditos, débitos e contagem). O saldo inicial é `SUM(credits − debits)` dos dias anteriores, calculado na leitura sobre a PK `(merchant_id, balance_date)`.

## Alternativas
- Persistir o saldo acumulado por dia: a leitura fica O(1), mas um lançamento retroativo exige atualizar N linhas (todos os dias seguintes), com risco de contenção e de inconsistência parcial.

## Consequências
Positivas:

- Escrita O(1) e sempre correta, inclusive com retroativos e eventos fora de ordem entre dias.

Negativas:

- A leitura é O(dias com movimento). Com ~365 linhas/ano por comerciante, é desprezível e coberto pelo cache. Evolução: snapshot mensal de saldo (fechamento), reduzindo a soma a no máximo ~31 linhas.
