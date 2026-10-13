package br.com.cashflow.launch;

import br.com.cashflow.launch.support.Containers;
import br.com.cashflow.launch.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure matrix: com o broker inacessível os lançamentos continuam sendo aceitos
 * e os eventos ficam retidos na outbox (PENDING) para publicação posterior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerUnavailableIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        r.add("spring.kafka.producer.properties.max.block.ms", () -> "500");
        r.add("cashflow.outbox.send-timeout", () -> "500ms");
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;

    @Test
    void aceitaLancamentosComBrokerForaDoArERetemEventosNaOutbox() {
        UUID merchant = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.issue(merchant, "lancamentos:write"));
        LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

        for (int i = 0; i < 20; i++) {
            headers.set("Idempotency-Key", "broker-down-" + UUID.randomUUID());
            var response = http.postForEntity("/api/v1/lancamentos", new HttpEntity<>(
                    Map.of("date", today.toString(), "type", "CREDIT", "amount", "1.00", "description", "x"), headers),
                    String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        long pending = jdbc.sql("""
                SELECT count(*) FROM outbox_event o JOIN entry e ON e.id = o.aggregate_id
                 WHERE e.merchant_id = :m AND o.status = 'PENDING'
                """).param("m", merchant).query(Long.class).single();
        assertThat(pending).isEqualTo(20);
    }
}
