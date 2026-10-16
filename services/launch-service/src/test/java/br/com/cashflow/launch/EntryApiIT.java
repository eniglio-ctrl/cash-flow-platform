package br.com.cashflow.launch;

import br.com.cashflow.launch.support.Containers;
import br.com.cashflow.launch.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EntryApiIT {

    private static final String WRITE_READ = "lancamentos:write lancamentos:read";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", Containers.KAFKA::getBootstrapServers);
        r.add("cashflow.outbox.poll-interval", () -> "200");
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private final UUID merchant = UUID.randomUUID();
    private final String token = TestTokens.issue(merchant, WRITE_READ);

    @Test
    void registraLancamentoEPublicaEventoNoKafkaViaOutbox() throws Exception {
        ResponseEntity<JsonNode> response = post(token, "key-" + UUID.randomUUID(), body("CREDIT", "150.75", TODAY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String entryId = response.getBody().get("id").asText();
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/lancamentos/" + entryId);

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of("cashflow.entries.v1"));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.value().contains(entryId)) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
            ConsumerRecord<String, String> record = found.getFirst();
            assertThat(record.key()).isEqualTo(merchant.toString());
            JsonNode event = json.readTree(record.value());
            assertThat(event.get("eventType").asText()).isEqualTo("EntryRegistered");
            assertThat(event.get("amount").asText()).isEqualTo("150.75");
            assertThat(event.get("type").asText()).isEqualTo("CREDIT");
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> outboxStatus(entryId).equals("SENT"));
    }

    @Test
    void chaveNaoEConsumidaQuandoARequisicaoFalha() {
        String key = "key-" + UUID.randomUUID();
        assertThat(post(token, key, body("CREDIT", "1.00", TODAY.plusDays(1))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // o cliente corrige o payload e reenvia com a mesma chave: a operação é registrada
        assertThat(post(token, key, body("CREDIT", "1.00", TODAY)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void chaveEUnicaEntreTodasAsEscritasDoComerciante() {
        String key = "key-" + UUID.randomUUID();
        String id = post(token, key, body("DEBIT", "8.00", TODAY)).getBody().get("id").asText();

        ResponseEntity<JsonNode> reversalWithSameKey = exchange(HttpMethod.POST, token, key,
                "/api/v1/lancamentos/" + id + "/estornos", Map.of("reason", "x"));
        assertThat(reversalWithSameKey.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mesmaChaveEmComerciantesDiferentesSaoOperacoesDistintas() {
        String key = "key-" + UUID.randomUUID();
        String other = TestTokens.issue(UUID.randomUUID(), WRITE_READ);

        assertThat(post(token, key, body("CREDIT", "3.00", TODAY)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(other, key, body("CREDIT", "3.00", TODAY)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void conteudoEquivalenteAposNormalizacaoEConsideradoOMesmo() {
        String key = "key-" + UUID.randomUUID();
        Map<String, Object> first = Map.of("date", TODAY.toString(), "type", "CREDIT", "amount", "10", "description", "Venda");
        Map<String, Object> retry = Map.of("date", TODAY.toString(), "type", "CREDIT", "amount", "10.00", "description", "  Venda ");

        assertThat(post(token, key, first).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(token, key, retry).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void mesmaChaveEMesmoPayloadRetornaOLancamentoOriginal() {
        String key = "key-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = post(token, key, body("DEBIT", "10.00", TODAY));
        ResponseEntity<JsonNode> second = post(token, key, body("DEBIT", "10", TODAY));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(countEntries()).isEqualTo(1);
    }

    @Test
    void mesmaChaveComPayloadDiferenteRetorna409() {
        String key = "key-" + UUID.randomUUID();
        post(token, key, body("DEBIT", "10.00", TODAY));

        assertThat(post(token, key, body("DEBIT", "99.00", TODAY)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void requisicoesConcorrentesComMesmaChaveGeramUmUnicoLancamento() throws Exception {
        String key = "key-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<HttpStatus>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> HttpStatus.valueOf(post(token, key, body("CREDIT", "5.00", TODAY)).getStatusCode().value()));
        }
        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<HttpStatus> f : pool.invokeAll(calls)) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnly(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
        assertThat(countEntries()).isEqualTo(1);
    }

    @Test
    void validaRegrasDeNegocioEPayload() {
        assertThat(post(token, "key-" + UUID.randomUUID(), body("CREDIT", "1.00", TODAY.plusDays(1))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post(token, "key-" + UUID.randomUUID(), body("CREDIT", "-5", TODAY)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(token, "key-" + UUID.randomUUID(), body("CREDIT", "1.999", TODAY)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(token, "curta", body("CREDIT", "1.00", TODAY)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(token, null, body("CREDIT", "1.00", TODAY)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void exigeAutenticacaoEEscopo() {
        assertThat(post(null, "key-" + UUID.randomUUID(), body("CREDIT", "1.00", TODAY)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String readOnly = TestTokens.issue(merchant, "lancamentos:read");
        assertThat(post(readOnly, "key-" + UUID.randomUUID(), body("CREDIT", "1.00", TODAY)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void comercianteNaoEnxergaLancamentoDeOutro() {
        String id = post(token, "key-" + UUID.randomUUID(), body("CREDIT", "1.00", TODAY)).getBody().get("id").asText();
        String other = TestTokens.issue(UUID.randomUUID(), WRITE_READ);

        assertThat(get(token, "/api/v1/lancamentos/" + id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(other, "/api/v1/lancamentos/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void estornaUmaUnicaVez() {
        String id = post(token, "key-" + UUID.randomUUID(), body("DEBIT", "30.00", TODAY.minusDays(2))).getBody().get("id").asText();

        ResponseEntity<JsonNode> reversal = exchange(HttpMethod.POST, token, "key-" + UUID.randomUUID(),
                "/api/v1/lancamentos/" + id + "/estornos", Map.of("reason", "Digitado errado"));
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reversal.getBody().get("type").asText()).isEqualTo("CREDIT");
        assertThat(reversal.getBody().get("reversalOf").asText()).isEqualTo(id);
        assertThat(reversal.getBody().get("date").asText()).isEqualTo(TODAY.toString());

        ResponseEntity<JsonNode> again = exchange(HttpMethod.POST, token, "key-" + UUID.randomUUID(),
                "/api/v1/lancamentos/" + id + "/estornos", Map.of("reason", "de novo"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void listaLancamentosDaData() {
        LocalDate date = TODAY.minusDays(5);
        post(token, "key-" + UUID.randomUUID(), body("CREDIT", "1.00", date));
        post(token, "key-" + UUID.randomUUID(), body("DEBIT", "2.00", date));

        ResponseEntity<JsonNode> list = get(token, "/api/v1/lancamentos?date=" + date);
        assertThat(list.getBody()).hasSize(2);
    }

    @Test
    void contratoOpenApiRefleteValidacoesECodigosReais() {
        JsonNode api = http.getForObject("/v3/api-docs", JsonNode.class);

        assertThat(api.at("/components/schemas/EntryRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("date", "type", "amount", "description");

        JsonNode register = api.at("/paths/~1api~1v1~1lancamentos/post/responses");
        assertThat(register.fieldNames()).toIterable()
                .contains("201", "200", "400", "401", "403", "409", "422", "429");
        assertThat(register.at("/201/headers/Location").isMissingNode()).isFalse();
        assertThat(register.at("/200/headers/Idempotent-Replayed").isMissingNode()).isFalse();

        assertThat(api.at("/paths/~1api~1v1~1lancamentos~1{id}~1estornos/post/responses").fieldNames()).toIterable()
                .contains("201", "200", "404", "409", "422");
        assertThat(api.at("/servers/0/url").asText()).isEqualTo("/");
    }

    // ---------- helpers ----------

    private Map<String, Object> body(String type, String amount, LocalDate date) {
        return Map.of("date", date.toString(), "type", type, "amount", amount, "description", "teste");
    }

    private ResponseEntity<JsonNode> post(String bearer, String key, Object body) {
        return exchange(HttpMethod.POST, bearer, key, "/api/v1/lancamentos", body);
    }

    private ResponseEntity<JsonNode> get(String bearer, String path) {
        return exchange(HttpMethod.GET, bearer, null, path, null);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String bearer, String key, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private long countEntries() {
        return jdbc.sql("SELECT count(*) FROM entry WHERE merchant_id = :m").param("m", merchant)
                .query(Long.class).single();
    }

    private String outboxStatus(String entryId) {
        return jdbc.sql("SELECT status FROM outbox_event WHERE aggregate_id = :id")
                .param("id", UUID.fromString(entryId)).query(String.class).single();
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
