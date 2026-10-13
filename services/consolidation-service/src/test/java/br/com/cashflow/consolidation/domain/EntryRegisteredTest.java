package br.com.cashflow.consolidation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryRegisteredTest {

    private EntryRegistered event(String eventType, int version, String type, String amount) {
        return new EntryRegistered(UUID.randomUUID(), eventType, version, UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 10, 13), type, amount == null ? null : new BigDecimal(amount), null, Instant.now());
    }

    @Test
    void aceitaEventoValido() {
        assertThatCode(() -> event("EntryRegistered", 1, "DEBIT", "10.00").validate()).doesNotThrowAnyException();
    }

    @Test
    void rejeitaEventosQueNaoPodemSerAplicados() {
        assertThatThrownBy(() -> event("Outro", 1, "CREDIT", "1").validate()).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> event("EntryRegistered", 2, "CREDIT", "1").validate()).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> event("EntryRegistered", 1, "PIX", "1").validate()).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> event("EntryRegistered", 1, "CREDIT", "0").validate()).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> event("EntryRegistered", 1, "CREDIT", null).validate()).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void saldoDiarioSomaSaldoInicialComMovimentoDoDia() {
        DailyBalance b = DailyBalance.of(LocalDate.of(2026, 10, 13), new BigDecimal("100"),
                new BigDecimal("50.00"), new BigDecimal("80.25"), 3, null);

        assertThat(b.netAmount()).isEqualByComparingTo("-30.25");
        assertThat(b.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(b.closingBalance()).isEqualByComparingTo("69.75");
    }
}
