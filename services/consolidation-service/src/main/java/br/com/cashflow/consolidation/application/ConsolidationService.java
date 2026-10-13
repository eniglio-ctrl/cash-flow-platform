package br.com.cashflow.consolidation.application;

import br.com.cashflow.consolidation.domain.EntryRegistered;
import br.com.cashflow.consolidation.infrastructure.persistence.ConsolidationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Aplica eventos de lançamento ao read model de forma idempotente. */
@Service
public class ConsolidationService {

    private final ConsolidationRepository repository;
    private final Clock clock;
    private final Counter applied;
    private final Counter duplicates;
    private final Timer freshness;

    public ConsolidationService(ConsolidationRepository repository, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.clock = clock;
        this.applied = metrics.counter("cashflow.consolidation.events", "result", "applied");
        this.duplicates = metrics.counter("cashflow.consolidation.events", "result", "duplicate");
        this.freshness = Timer.builder("cashflow.consolidation.freshness")
                .description("Tempo entre o registro do lançamento e sua aplicação no consolidado")
                .publishPercentileHistogram()
                .register(metrics);
    }

    /**
     * Deduplicação e atualização do saldo na MESMA transação: ou ambas acontecem, ou nenhuma.
     *
     * @return true se o evento foi aplicado; false se era duplicado.
     */
    @Transactional
    public boolean apply(EntryRegistered event) {
        event.validate();
        Instant now = clock.instant();
        if (!repository.markProcessed(event, now)) {
            duplicates.increment();
            return false;
        }
        repository.applyEntry(event, now);
        applied.increment();
        if (event.occurredAt() != null) {
            freshness.record(Duration.between(event.occurredAt(), now));
        }
        return true;
    }
}
