package br.com.cashflow.consolidation.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cashflow.consumer")
public record ConsumerProperties(String topic, int maxRetries) {

    public String deadLetterTopic() {
        return topic + ".dlt";
    }
}
