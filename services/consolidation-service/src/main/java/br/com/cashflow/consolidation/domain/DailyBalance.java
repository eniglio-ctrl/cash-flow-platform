package br.com.cashflow.consolidation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Saldo consolidado de um dia: saldo inicial (acumulado dos dias anteriores) + créditos − débitos.
 */
public record DailyBalance(
        LocalDate date,
        BigDecimal openingBalance,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        BigDecimal netAmount,
        BigDecimal closingBalance,
        int entriesCount,
        Instant lastUpdatedAt) {

    public static DailyBalance of(LocalDate date, BigDecimal opening, BigDecimal credits, BigDecimal debits,
                                  int count, Instant updatedAt) {
        BigDecimal start = opening.setScale(2);
        BigDecimal net = credits.subtract(debits);
        return new DailyBalance(date, start, credits, debits, net, start.add(net), count, updatedAt);
    }
}
