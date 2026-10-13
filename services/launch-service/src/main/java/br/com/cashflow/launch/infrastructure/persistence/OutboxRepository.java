package br.com.cashflow.launch.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OutboxEvent event) {
        jdbc.sql("""
                INSERT INTO outbox_event (id, aggregate_id, partition_key, event_type, payload, created_at)
                VALUES (:id, :aggregate, :key, :type, CAST(:payload AS JSONB), :createdAt)
                """)
                .param("id", event.id())
                .param("aggregate", event.aggregateId())
                .param("key", event.partitionKey())
                .param("type", event.eventType())
                .param("payload", event.payload())
                .param("createdAt", Timestamp.from(event.createdAt()))
                .update();
    }

    /**
     * Seleciona um lote de pendentes travando as linhas. SKIP LOCKED permite várias
     * instâncias do publicador sem que duas publiquem o mesmo evento ao mesmo tempo.
     */
    public List<OutboxEvent> lockPendingBatch(int size) {
        return jdbc.sql("""
                SELECT id, aggregate_id, partition_key, event_type, payload::text AS payload, created_at
                  FROM outbox_event
                 WHERE status = 'PENDING'
                 ORDER BY created_at
                 LIMIT :size
                 FOR UPDATE SKIP LOCKED
                """)
                .param("size", size)
                .query((rs, i) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("partition_key"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public void markSent(UUID id, Instant when) {
        jdbc.sql("UPDATE outbox_event SET status = 'SENT', published_at = :when, attempts = attempts + 1 WHERE id = :id")
                .param("id", id).param("when", Timestamp.from(when)).update();
    }

    public void markFailure(UUID id, String error) {
        String message = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("id", id).param("error", message).update();
    }

    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE status = 'PENDING'").query(Long.class).single();
    }

    public int deleteSentBefore(Instant limit) {
        return jdbc.sql("DELETE FROM outbox_event WHERE status = 'SENT' AND published_at < :limit")
                .param("limit", Timestamp.from(limit)).update();
    }
}
