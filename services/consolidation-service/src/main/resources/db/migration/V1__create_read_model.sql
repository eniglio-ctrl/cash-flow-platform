-- Read model do consolidado: uma linha por comerciante e dia, atualizada de forma incremental.
CREATE TABLE daily_consolidation (
    merchant_id    UUID          NOT NULL,
    balance_date   DATE          NOT NULL,
    total_credits  NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_debits   NUMERIC(19,2) NOT NULL DEFAULT 0,
    entries_count  INTEGER       NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (merchant_id, balance_date)
);

-- Deduplicação: um evento só é aplicado uma vez (entrega do broker é at-least-once).
CREATE TABLE processed_event (
    event_id      UUID        PRIMARY KEY,
    entry_id      UUID        NOT NULL UNIQUE,
    processed_at  TIMESTAMPTZ NOT NULL
);
