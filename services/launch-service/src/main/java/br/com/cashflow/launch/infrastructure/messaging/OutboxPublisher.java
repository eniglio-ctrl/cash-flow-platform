package br.com.cashflow.launch.infrastructure.messaging;

import br.com.cashflow.launch.infrastructure.persistence.OutboxEvent;
import br.com.cashflow.launch.infrastructure.persistence.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relay da Outbox: lê eventos pendentes e publica no Kafka com entrega at-least-once.
 * Em caso de falha do broker o evento continua PENDING e é reenviado no próximo ciclo;
 * o lote é interrompido para preservar a ordem por comerciante.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;
    private final AtomicLong pending = new AtomicLong();

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                           OutboxProperties props, Clock clock, MeterRegistry metrics) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        this.published = metrics.counter("cashflow.outbox.published");
        this.failures = metrics.counter("cashflow.outbox.failures");
        Gauge.builder("cashflow.outbox.pending", pending, AtomicLong::get)
                .description("Eventos aguardando publicação no broker")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${cashflow.outbox.poll-interval:500}")
    public void publishPending() {
        Integer sent;
        do {
            sent = tx.execute(status -> publishBatch());
        } while (sent != null && sent == props.batchSize());
        pending.set(outbox.countPending());
    }

    private int publishBatch() {
        List<OutboxEvent> batch = outbox.lockPendingBatch(props.batchSize());
        int count = 0;
        for (OutboxEvent event : batch) {
            try {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(props.topic(), event.partitionKey(), event.payload());
                record.headers().add("eventId", event.id().toString().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventType", event.eventType().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                outbox.markSent(event.id(), clock.instant());
                published.increment();
                count++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failures.increment();
                outbox.markFailure(event.id(), e.getMessage());
                log.warn("Falha ao publicar evento {} da outbox; nova tentativa no próximo ciclo: {}",
                        event.id(), e.getMessage());
                return -1;
            }
        }
        return count;
    }

    @Scheduled(cron = "${cashflow.outbox.cleanup-cron:0 0 * * * *}")
    public void cleanup() {
        int removed = outbox.deleteSentBefore(clock.instant().minus(props.retention()));
        if (removed > 0) {
            log.info("Outbox: {} eventos publicados removidos", removed);
        }
    }
}
