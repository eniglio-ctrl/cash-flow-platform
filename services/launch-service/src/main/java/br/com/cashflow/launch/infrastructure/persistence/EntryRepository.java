package br.com.cashflow.launch.infrastructure.persistence;

import br.com.cashflow.launch.domain.Entry;
import br.com.cashflow.launch.domain.EntryType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EntryRepository {

    private static final String COLUMNS =
            "id, merchant_id, entry_date, type, amount, description, reversal_of, created_at, request_hash";

    private static final RowMapper<StoredEntry> MAPPER = (rs, i) -> new StoredEntry(
            new Entry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("merchant_id", UUID.class),
                    rs.getObject("entry_date", LocalDate.class),
                    EntryType.valueOf(rs.getString("type")),
                    rs.getBigDecimal("amount"),
                    rs.getString("description"),
                    rs.getObject("reversal_of", UUID.class),
                    rs.getTimestamp("created_at").toInstant()),
            rs.getString("request_hash"));

    private final JdbcClient jdbc;

    public EntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Entry e, String idempotencyKey, String requestHash) {
        jdbc.sql("""
                INSERT INTO entry (id, merchant_id, idempotency_key, request_hash, entry_date, type,
                                   amount, description, reversal_of, created_at)
                VALUES (:id, :merchant, :key, :hash, :date, :type, :amount, :description, :reversalOf, :createdAt)
                """)
                .param("id", e.id())
                .param("merchant", e.merchantId())
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("date", e.entryDate())
                .param("type", e.type().name())
                .param("amount", e.amount())
                .param("description", e.description())
                .param("reversalOf", e.reversalOf())
                .param("createdAt", Timestamp.from(e.createdAt()))
                .update();
    }

    public Optional<StoredEntry> findByIdempotencyKey(UUID merchantId, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM entry WHERE merchant_id = :m AND idempotency_key = :k")
                .param("m", merchantId).param("k", key)
                .query(MAPPER).optional();
    }

    public Optional<Entry> findById(UUID merchantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM entry WHERE merchant_id = :m AND id = :id")
                .param("m", merchantId).param("id", id)
                .query(MAPPER).optional().map(StoredEntry::entry);
    }

    /** Bloqueia o lançamento original enquanto o estorno é gravado. */
    public Optional<Entry> findByIdForUpdate(UUID merchantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM entry WHERE merchant_id = :m AND id = :id FOR UPDATE")
                .param("m", merchantId).param("id", id)
                .query(MAPPER).optional().map(StoredEntry::entry);
    }

    public boolean hasReversal(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM entry WHERE reversal_of = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    public List<Entry> findByDate(UUID merchantId, LocalDate date, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM entry WHERE merchant_id = :m AND entry_date = :d
                         ORDER BY created_at, id LIMIT :limit OFFSET :offset
                        """)
                .param("m", merchantId).param("d", date)
                .param("limit", limit).param("offset", offset)
                .query(MAPPER).list().stream().map(StoredEntry::entry).toList();
    }

    public record StoredEntry(Entry entry, String requestHash) {
    }
}
