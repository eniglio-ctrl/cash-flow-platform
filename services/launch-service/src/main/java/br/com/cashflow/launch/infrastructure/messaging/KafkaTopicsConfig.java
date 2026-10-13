package br.com.cashflow.launch.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    /** Particionado por merchantId: garante ordem por comerciante e paralelismo entre comerciantes. */
    @Bean
    NewTopic entriesTopic(OutboxProperties props) {
        return TopicBuilder.name(props.topic()).partitions(6).replicas(1).build();
    }
}
