# ADR-0004: Apache Kafka como broker

## Status
Aceito (com alternativa de custo documentada)

## Contexto
É preciso um broker durável entre os contextos. A carga é modesta (dezenas de eventos/s).

## Decisão
Apache Kafka (KRaft), tópico `cashflow.entries.v1` com 6 partições e chave `merchantId`, mais o tópico de DLQ `cashflow.entries.v1.dlt`. Em produção, Amazon MSK.

## Por quê
- Replay: o read model do consolidado é descartável; dá para reconstruí-lo relendo o tópico com um novo consumer group (exige retenção adequada ou compactação/snapshot).
- Ordem por comerciante via partição, com paralelismo entre comerciantes.
- Fan-out para consumidores futuros (analytics, conciliação, notificações) sem alterar o produtor.

## Alternativas
| Alternativa | Avaliação |
|---|---|
| RabbitMQ | Também atenderia. Mensagem consumida sai da fila, então não há replay nativo. Operação mais simples. |
| SNS + SQS FIFO | Muito mais barato para essa carga (~poucos USD/mês contra ~120 USD/mês do MSK) e totalmente gerenciado. Sem replay de longo prazo. É a opção preferida se custo for a restrição dominante. |
| Kafka gerenciado serverless (MSK Serverless/Confluent) | Elimina a operação, mas tem custo fixo maior que o provisionado pequeno para essa carga. |

## Consequências
Positivas:

- Trocar de broker é barato: só o relay da Outbox e o listener conhecem o Kafka. O contrato, a Outbox e a idempotência continuam iguais.

Negativas:

- Kafka é mais do que a carga atual pede, e a decisão admite isso. O custo foi quantificado em [07-custos.md](../07-custos.md).
