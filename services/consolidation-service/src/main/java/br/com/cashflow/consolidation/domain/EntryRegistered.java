package br.com.cashflow.consolidation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Visão do evento publicado pelo contexto de Lançamentos (contrato v1). */
public record EntryRegistered(
        UUID eventId,
        String eventType,
        int version,
        UUID entryId,
        UUID merchantId,
        LocalDate entryDate,
        String type,
        BigDecimal amount,
        UUID reversalOf,
        Instant occurredAt) {

    public static final String TYPE = "EntryRegistered";

    /** Lança {@link InvalidEventException} (não retentável) se o evento não puder ser aplicado. */
    public void validate() {
        if (eventId == null || entryId == null || merchantId == null || entryDate == null || amount == null) {
            throw new InvalidEventException("Campos obrigatórios ausentes no evento " + eventId);
        }
        if (!TYPE.equals(eventType) || version != 1) {
            throw new InvalidEventException("Tipo/versão de evento não suportado: " + eventType + " v" + version);
        }
        if (!"CREDIT".equals(type) && !"DEBIT".equals(type)) {
            throw new InvalidEventException("Tipo de lançamento inválido: " + type);
        }
        if (amount.signum() <= 0) {
            throw new InvalidEventException("Valor inválido: " + amount);
        }
    }

    public boolean isCredit() {
        return "CREDIT".equals(type);
    }
}
