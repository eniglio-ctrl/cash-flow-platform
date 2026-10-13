package br.com.cashflow.consolidation.infrastructure.persistence;

import br.com.cashflow.consolidation.domain.EntryRegistered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class ConsolidationRepository {

    private final JdbcClient jdbc;

    public ConsolidationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false se o evento (ou o lançamento) já tinha sido processado. */
    public boolean markProcessed(EntryRegistered event, Instant now) {
        return jdbc.sql("""
                INSERT INTO processed_event (event_id, entry_id, processed_at) VALUES (:event, :entry, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("event", event.eventId())
                .param("entry", event.entryId())
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /**
     * Incremento atômico no banco: seguro com múltiplas instâncias e consumidores concorrentes,
     * sem read-modify-write na aplicação.
     */
    public void applyEntry(EntryRegistered event, Instant now) {
        BigDecimal credit = event.isCredit() ? event.amount() : BigDecimal.ZERO;
        BigDecimal debit = event.isCredit() ? BigDecimal.ZERO : event.amount();
        jdbc.sql("""
                INSERT INTO daily_consolidation (merchant_id, balance_date, total_credits, total_debits, entries_count, updated_at)
                VALUES (:merchant, :date, :credit, :debit, 1, :now)
                ON CONFLICT (merchant_id, balance_date) DO UPDATE
                   SET total_credits = daily_consolidation.total_credits + EXCLUDED.total_credits,
                       total_debits  = daily_consolidation.total_debits  + EXCLUDED.total_debits,
                       entries_count = daily_consolidation.entries_count + 1,
                       updated_at    = EXCLUDED.updated_at
                """)
                .param("merchant", event.merchantId())
                .param("date", event.entryDate())
                .param("credit", credit)
                .param("debit", debit)
                .param("now", Timestamp.from(now))
                .update();
    }

    public BigDecimal balanceBefore(UUID merchantId, LocalDate date) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(total_credits - total_debits), 0)
                  FROM daily_consolidation WHERE merchant_id = :m AND balance_date < :d
                """)
                .param("m", merchantId).param("d", date)
                .query(BigDecimal.class).single();
    }

    public List<DayRow> findBetween(UUID merchantId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT balance_date, total_credits, total_debits, entries_count, updated_at
                  FROM daily_consolidation
                 WHERE merchant_id = :m AND balance_date BETWEEN :from AND :to
                 ORDER BY balance_date
                """)
                .param("m", merchantId).param("from", from).param("to", to)
                .query((rs, i) -> new DayRow(
                        rs.getObject("balance_date", LocalDate.class),
                        rs.getBigDecimal("total_credits"),
                        rs.getBigDecimal("total_debits"),
                        rs.getInt("entries_count"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public record DayRow(LocalDate date, BigDecimal credits, BigDecimal debits, int count, Instant updatedAt) {
    }
}
