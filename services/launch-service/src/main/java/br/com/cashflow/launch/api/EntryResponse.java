package br.com.cashflow.launch.api;

import br.com.cashflow.launch.domain.Entry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EntryResponse(UUID id, LocalDate date, String type, BigDecimal amount, String description,
                            UUID reversalOf, Instant createdAt) {

    static EntryResponse from(Entry e) {
        return new EntryResponse(e.id(), e.entryDate(), e.type().name(), e.amount(), e.description(),
                e.reversalOf(), e.createdAt());
    }
}
