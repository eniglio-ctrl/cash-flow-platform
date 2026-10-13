package br.com.cashflow.launch.application;

import br.com.cashflow.launch.domain.Entry;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Evento de integração publicado no tópico {@code cashflow.entries.v1}.
 * Contrato versionado: mudanças incompatíveis exigem novo tópico/versão (ver ADR-008).
 * O valor trafega como String para preservar a precisão decimal.
 */
public record EntryRegisteredEvent(
        UUID eventId,
        String eventType,
        int version,
        UUID entryId,
        UUID merchantId,
        LocalDate entryDate,
        String type,
        String amount,
        UUID reversalOf,
        Instant occurredAt) {

    public static final String TYPE = "EntryRegistered";

    public static EntryRegisteredEvent of(Entry e) {
        return new EntryRegisteredEvent(UUID.randomUUID(), TYPE, 1, e.id(), e.merchantId(), e.entryDate(),
                e.type().name(), e.amount().toPlainString(), e.reversalOf(), e.createdAt());
    }
}
