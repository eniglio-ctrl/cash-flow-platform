package br.com.cashflow.launch.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, UUID aggregateId, String partitionKey, String eventType,
                          String payload, Instant createdAt) {
}
