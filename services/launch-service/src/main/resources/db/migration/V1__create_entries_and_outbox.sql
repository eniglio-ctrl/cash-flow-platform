-- Lançamentos são imutáveis: correções são feitas por estorno (novo lançamento com reversal_of).
CREATE TABLE entry (
    id               UUID          PRIMARY KEY,
    merchant_id      UUID          NOT NULL,
    idempotency_key  VARCHAR(100)  NOT NULL,
    request_hash     CHAR(64)      NOT NULL,
    entry_date       DATE          NOT NULL,
    type             VARCHAR(6)    NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    amount           NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    description      VARCHAR(200)  NOT NULL,
    reversal_of      UUID          REFERENCES entry (id),
    created_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_entry_idempotency UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT uk_entry_reversal UNIQUE (reversal_of)
);

CREATE INDEX ix_entry_merchant_date ON entry (merchant_id, entry_date);

CREATE TABLE outbox_event (
    id             UUID         PRIMARY KEY,
    aggregate_id   UUID         NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT')),
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);

-- Índice parcial: o publicador só varre eventos pendentes.
CREATE INDEX ix_outbox_pending ON outbox_event (created_at) WHERE status = 'PENDING';
