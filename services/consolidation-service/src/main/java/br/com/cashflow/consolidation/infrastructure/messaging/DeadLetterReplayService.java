package br.com.cashflow.consolidation.infrastructure.messaging;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RF-06: reprocessamento. Devolve mensagens da DLQ para o tópico principal após a causa
 * ter sido corrigida. É seguro reenviar porque o consumidor é idempotente.
 */
@Service
public class DeadLetterReplayService {

    private static final String GROUP = "consolidation-dlq-replay";

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> kafka;
    private final ConsumerProperties props;

    public DeadLetterReplayService(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka,
                                   ConsumerProperties props) {
        this.consumers = consumers;
        this.kafka = kafka;
        this.props = props;
    }

    public synchronized int replay(int max) throws Exception {
        int replayed = 0;
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "dlq-replay", null)) {
            consumer.subscribe(List.of(props.deadLetterTopic()));
            int emptyPolls = 0;
            while (replayed < max && emptyPolls < 3) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                for (ConsumerRecord<String, String> r : records) {
                    ProducerRecord<String, String> out = new ProducerRecord<>(props.topic(), r.key(), r.value());
                    for (Header h : r.headers()) {
                        if (!h.key().startsWith("kafka_dlt")) {
                            out.headers().add(h);
                        }
                    }
                    kafka.send(out).get(5, TimeUnit.SECONDS);
                    replayed++;
                }
                consumer.commitSync();
            }
        }
        return replayed;
    }
}
