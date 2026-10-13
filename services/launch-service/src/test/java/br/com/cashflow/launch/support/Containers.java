package br.com.cashflow.launch.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Containers compartilhados entre as classes de teste (iniciados uma única vez por JVM). */
public final class Containers {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private Containers() {
    }
}
