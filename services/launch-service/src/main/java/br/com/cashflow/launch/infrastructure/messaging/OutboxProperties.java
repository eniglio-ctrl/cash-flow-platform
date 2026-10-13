package br.com.cashflow.launch.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "cashflow.outbox")
public record OutboxProperties(String topic, int batchSize, Duration sendTimeout, Duration retention) {
}
