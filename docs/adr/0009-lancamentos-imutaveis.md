# ADR-0009: Lançamentos imutáveis, correção por estorno

## Status
Aceito

## Decisão
Não existem PUT nem DELETE de lançamento. A correção é feita por estorno: um novo lançamento de tipo oposto, mesmo valor e na data de hoje, com `reversal_of` apontando o original. Cada lançamento aceita um estorno; um estorno não pode ser estornado.

## Por quê
- É a prática contábil: o histórico é auditável e nada desaparece.
- Simplifica a integração: só existe um tipo de evento, de inserção. O consolidado nunca precisa "desfazer" nada; o estorno é só mais um crédito ou débito.
- Evita condições de corrida entre alterar o lançamento e consolidar.

## Consequências
- O estorno entra na data de hoje: o dia original continua mostrando o que aconteceu naquele dia, e o saldo acumulado fica correto a partir de hoje. Se o negócio preferir estornar na data original, basta trocar a data no domínio, e o consolidado já suporta datas retroativas.
