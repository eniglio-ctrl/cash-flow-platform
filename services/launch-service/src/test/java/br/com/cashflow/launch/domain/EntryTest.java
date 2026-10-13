package br.com.cashflow.launch.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private final UUID merchant = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 10, 13);

    @Test
    void registraLancamentoValidoNormalizandoEscala() {
        Entry e = Entry.register(merchant, today, EntryType.CREDIT, new BigDecimal("10.5"), "  Venda  ", clock);

        assertThat(e.amount()).isEqualByComparingTo("10.50");
        assertThat(e.amount().scale()).isEqualTo(2);
        assertThat(e.description()).isEqualTo("Venda");
        assertThat(e.isReversal()).isFalse();
    }

    @Test
    void usaDataDoFusoDoComercianteENaoUtc() {
        // 01:30 UTC de 14/10 ainda é 13/10 em São Paulo
        Clock lateNight = Clock.fixed(Instant.parse("2026-10-14T01:30:00Z"), ZoneId.of("America/Sao_Paulo"));
        assertThatThrownBy(() -> Entry.register(merchant, LocalDate.of(2026, 10, 14), EntryType.DEBIT,
                BigDecimal.ONE, "x", lateNight)).hasMessageContaining("data futura");
    }

    @Test
    void rejeitaValorZeroNegativoOuComMaisDeDuasCasas() {
        assertThatThrownBy(() -> Entry.register(merchant, today, EntryType.CREDIT, BigDecimal.ZERO, "x", clock))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Entry.register(merchant, today, EntryType.CREDIT, new BigDecimal("-1"), "x", clock))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Entry.register(merchant, today, EntryType.CREDIT, new BigDecimal("1.001"), "x", clock))
                .hasMessageContaining("2 casas");
    }

    @Test
    void rejeitaRetroativoAlemDoLimite() {
        assertThatThrownBy(() -> Entry.register(merchant, today.minusDays(366), EntryType.CREDIT,
                BigDecimal.TEN, "x", clock)).hasMessageContaining("retroativos");
    }

    @Test
    void estornoGeraLancamentoOpostoNaDataDeHoje() {
        Entry original = Entry.register(merchant, today.minusDays(3), EntryType.DEBIT, new BigDecimal("42.00"), "Compra", clock);

        Entry reversal = original.reverse(null, clock);

        assertThat(reversal.type()).isEqualTo(EntryType.CREDIT);
        assertThat(reversal.amount()).isEqualByComparingTo(original.amount());
        assertThat(reversal.entryDate()).isEqualTo(today);
        assertThat(reversal.reversalOf()).isEqualTo(original.id());
    }

    @Test
    void estornoNaoPodeSerEstornado() {
        Entry reversal = Entry.register(merchant, today, EntryType.DEBIT, BigDecimal.TEN, "x", clock).reverse("r", clock);

        assertThatThrownBy(() -> reversal.reverse("de novo", clock)).isInstanceOf(DomainException.class);
    }
}
