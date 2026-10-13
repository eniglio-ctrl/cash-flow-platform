package br.com.cashflow.launch.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Lançamento financeiro (fato imutável). Não existe update nem delete:
 * um lançamento incorreto é anulado por um estorno ({@link #reverse}).
 */
public record Entry(
        UUID id,
        UUID merchantId,
        LocalDate entryDate,
        EntryType type,
        BigDecimal amount,
        String description,
        UUID reversalOf,
        Instant createdAt) {

    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
    public static final int MAX_DESCRIPTION = 200;
    /** Lançamentos retroativos são aceitos até este limite (RN-04). */
    public static final int MAX_DAYS_IN_PAST = 365;

    public Entry {
        Objects.requireNonNull(id);
        Objects.requireNonNull(merchantId);
        Objects.requireNonNull(entryDate);
        Objects.requireNonNull(type);
        Objects.requireNonNull(createdAt);
        if (amount == null || amount.signum() <= 0) {
            throw new DomainException("O valor deve ser maior que zero");
        }
        if (amount.scale() > 2) {
            throw new DomainException("O valor deve ter no máximo 2 casas decimais");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new DomainException("O valor excede o limite de " + MAX_AMOUNT);
        }
        if (description == null || description.isBlank()) {
            throw new DomainException("A descrição é obrigatória");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new DomainException("A descrição deve ter no máximo " + MAX_DESCRIPTION + " caracteres");
        }
        amount = amount.setScale(2);
        description = description.strip();
    }

    public static Entry register(UUID merchantId, LocalDate entryDate, EntryType type,
                                 BigDecimal amount, String description, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        if (entryDate == null) {
            throw new DomainException("A data do lançamento é obrigatória");
        }
        if (entryDate.isAfter(today)) {
            throw new DomainException("Não é permitido lançamento com data futura");
        }
        if (entryDate.isBefore(today.minusDays(MAX_DAYS_IN_PAST))) {
            throw new DomainException("Lançamentos retroativos são limitados a " + MAX_DAYS_IN_PAST + " dias");
        }
        return new Entry(UUID.randomUUID(), merchantId, entryDate, type, amount, description, null, clock.instant());
    }

    /** Estorno: lançamento de mesmo valor e tipo oposto, na data de hoje, referenciando o original. */
    public Entry reverse(String reason, Clock clock) {
        if (isReversal()) {
            throw new DomainException("Um estorno não pode ser estornado");
        }
        String text = (reason == null || reason.isBlank()) ? "Estorno de " + id : reason;
        return new Entry(UUID.randomUUID(), merchantId, LocalDate.now(clock), type.opposite(),
                amount, text, id, clock.instant());
    }

    public boolean isReversal() {
        return reversalOf != null;
    }
}
