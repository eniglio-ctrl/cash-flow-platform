package br.com.cashflow.consolidation;

import br.com.cashflow.consolidation.application.ConsolidationService;
import br.com.cashflow.consolidation.domain.EntryRegistered;
import br.com.cashflow.consolidation.support.Containers;
import br.com.cashflow.consolidation.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsolidationIT {

    private static final String TOPIC = "cashflow.entries.v1";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", Containers.KAFKA::getBootstrapServers);
        // sem cache nos testes, para observar o read model diretamente
        r.add("spring.cache.caffeine.spec", () -> "maximumSize=0");
    }

    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsolidationService service;
    @Autowired ObjectMapper json;

    private final UUID merchant = UUID.randomUUID();
    private final String token = TestTokens.issue(merchant, "consolidado:read");

    @Test
    void consomeEventosEExpoeSaldoComSaldoInicialAcumulado() throws Exception {
        publish(event(merchant, DAY.minusDays(1), "CREDIT", "1000.00"));
        publish(event(merchant, DAY, "CREDIT", "250.50"));
        publish(event(merchant, DAY, "DEBIT", "100.25"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(day(token, DAY).getBody().get("entriesCount").asInt()).isEqualTo(2));

        JsonNode body = day(token, DAY).getBody();
        assertThat(new BigDecimal(body.get("openingBalance").asText())).isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(body.get("totalCredits").asText())).isEqualByComparingTo("250.50");
        assertThat(new BigDecimal(body.get("totalDebits").asText())).isEqualByComparingTo("100.25");
        assertThat(new BigDecimal(body.get("closingBalance").asText())).isEqualByComparingTo("1150.25");
    }

    @Test
    void eventoDuplicadoNaoAlteraOSaldo() throws Exception {
        EntryRegistered e = event(merchant, DAY, "CREDIT", "10.00");
        publish(e);
        publish(e);
        publish(e);
        EntryRegistered marker = event(merchant, DAY, "CREDIT", "1.00");
        publish(marker);

        // mesma partição (mesma chave): quando o marcador aparece, as duplicatas anteriores já foram processadas
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(day(token, DAY).getBody().get("entriesCount").asInt()).isEqualTo(2));
        assertThat(new BigDecimal(day(token, DAY).getBody().get("totalCredits").asText())).isEqualByComparingTo("11.00");
    }

    @Test
    void eventoInvalidoVaiParaDlqSemBloquearOsDemais() throws Exception {
        String poison = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"quebrado\":";
        kafka.send(TOPIC, merchant.toString(), poison).get();
        publish(event(merchant, DAY, "DEBIT", "5.00"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(day(token, DAY).getBody().get("entriesCount").asInt()).isEqualTo(1));

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(TOPIC + ".dlt"));
            AtomicBoolean found = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> found.compareAndSet(false, poison.equals(r.value())));
                return found.get();
            });
        }
    }

    @Test
    void aplicacaoConcorrenteMantemTotaisExatos() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String type = i % 2 == 0 ? "CREDIT" : "DEBIT";
            EntryRegistered e = event(merchant, DAY, type, "1.01");
            tasks.add(() -> service.apply(e));
            tasks.add(() -> service.apply(e)); // cada evento entregue duas vezes, em paralelo
        }
        pool.invokeAll(tasks);
        pool.shutdown();

        JsonNode body = day(token, DAY).getBody();
        assertThat(body.get("entriesCount").asInt()).isEqualTo(200);
        assertThat(new BigDecimal(body.get("totalCredits").asText())).isEqualByComparingTo("101.00");
        assertThat(new BigDecimal(body.get("totalDebits").asText())).isEqualByComparingTo("101.00");
    }

    @Test
    void intervaloPreencheDiasSemMovimentoEEncadeiaSaldos() {
        service.apply(event(merchant, DAY, "CREDIT", "100.00"));
        service.apply(event(merchant, DAY.plusDays(2), "DEBIT", "30.00"));

        ResponseEntity<JsonNode> r = get(token, "/api/v1/consolidados?from=" + DAY + "&to=" + DAY.plusDays(2));

        assertThat(r.getBody()).hasSize(3);
        assertThat(new BigDecimal(r.getBody().get(1).get("closingBalance").asText())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(r.getBody().get(2).get("closingBalance").asText())).isEqualByComparingTo("70.00");
        assertThat(get(token, "/api/v1/consolidados?from=" + DAY + "&to=" + DAY.plusDays(40)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void isolaComerciantesEExigeEscopo() {
        service.apply(event(merchant, DAY, "CREDIT", "77.00"));

        String other = TestTokens.issue(UUID.randomUUID(), "consolidado:read");
        assertThat(day(other, DAY).getBody().get("entriesCount").asInt()).isZero();
        assertThat(day(null, DAY).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(day(TestTokens.issue(merchant, "lancamentos:read"), DAY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void contratoOpenApiDeclaraRespostasReais() {
        JsonNode api = http.getForObject("/v3/api-docs", JsonNode.class);

        assertThat(api.at("/paths/~1api~1v1~1consolidados~1{date}/get/responses").fieldNames()).toIterable()
                .contains("200", "400", "401", "403", "429");
        assertThat(api.at("/paths/~1api~1v1~1consolidados/get/responses").fieldNames()).toIterable()
                .contains("200", "400", "401", "403", "429");
        assertThat(api.at("/paths/~1api~1v1~1consolidados~1{date}/get/responses/200/headers/X-Consistency").isMissingNode())
                .isFalse();
    }

    // ---------- helpers ----------

    private EntryRegistered event(UUID m, LocalDate date, String type, String amount) {
        return new EntryRegistered(UUID.randomUUID(), "EntryRegistered", 1, UUID.randomUUID(), m, date, type,
                new BigDecimal(amount), null, Instant.now());
    }

    private void publish(EntryRegistered e) throws Exception {
        kafka.send(TOPIC, e.merchantId().toString(), json.writeValueAsString(e)).get();
    }

    private ResponseEntity<JsonNode> day(String bearer, LocalDate date) {
        return get(bearer, "/api/v1/consolidados/" + date);
    }

    private ResponseEntity<JsonNode> get(String bearer, String path) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private KafkaConsumer<String, String> consumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(p);
    }
}
