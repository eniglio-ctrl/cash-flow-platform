package br.com.cashflow.consolidation.infrastructure.messaging;

import br.com.cashflow.consolidation.domain.InvalidEventException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    NewTopic entriesTopic(ConsumerProperties props) {
        return TopicBuilder.name(props.topic()).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic(ConsumerProperties props) {
        return TopicBuilder.name(props.deadLetterTopic()).partitions(1).replicas(1).build();
    }

    /**
     * Política de falhas do consumidor:
     * <ul>
     *   <li>evento inválido → DLQ imediatamente (retry não resolve);</li>
     *   <li>banco do consolidado fora → retry indefinido: a partição pausa e nada vai para a DLQ,
     *       o backlog fica retido no Kafka até o banco voltar;</li>
     *   <li>demais erros → retry com backoff exponencial e, esgotado, DLQ.</li>
     * </ul>
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, ConsumerProperties props,
                                          MeterRegistry metrics) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(props.deadLetterTopic(), -1));
        Counter sentToDlq = metrics.counter("cashflow.consolidation.dlq");
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            deadLetter.accept(record, ex);
            sentToDlq.increment();
        };

        ExponentialBackOffWithMaxRetries defaultBackOff = new ExponentialBackOffWithMaxRetries(props.maxRetries());
        defaultBackOff.setInitialInterval(500);
        defaultBackOff.setMultiplier(2.0);
        defaultBackOff.setMaxInterval(5_000);
        BackOff infrastructureBackOff = new FixedBackOff(5_000, FixedBackOff.UNLIMITED_ATTEMPTS);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, defaultBackOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setBackOffFunction((record, ex) -> isInfrastructureFailure(ex) ? infrastructureBackOff : null);
        return handler;
    }

    private static boolean isInfrastructureFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DataAccessResourceFailureException || t instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
