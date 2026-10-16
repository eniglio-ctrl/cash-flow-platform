package br.com.cashflow.launch;

import br.com.cashflow.launch.application.EntryResult;
import br.com.cashflow.launch.application.EntryService;
import br.com.cashflow.launch.application.IdempotencyConflictException;
import br.com.cashflow.launch.application.RegisterEntryCommand;
import br.com.cashflow.launch.domain.Entry;
import br.com.cashflow.launch.domain.EntryType;
import br.com.cashflow.launch.infrastructure.persistence.EntryRepository;
import br.com.cashflow.launch.support.Containers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Prova determinística de que a constraint UNIQUE(merchant_id, idempotency_key) impede dois
 * lançamentos quando duas requisições com a mesma chave estão em voo ao mesmo tempo.
 *
 * <p>O teste força a pior intercalação possível em vez de depender da sorte do escalonador:
 * <ol>
 *   <li>A transação A grava o lançamento com a chave K e fica aberta, sem commit;</li>
 *   <li>B (mesma chave) passa pela consulta prévia, porque não enxerga A, e tenta inserir;</li>
 *   <li>o PostgreSQL bloqueia B no índice único, o que o teste confirma via pg_stat_activity;</li>
 *   <li>A faz commit; B recebe a violação de unicidade e resolve relendo o registro vencedor.</li>
 * </ol>
 */
@SpringBootTest
class IdempotencyRaceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", Containers.KAFKA::getBootstrapServers);
    }

    @Autowired EntryService service;
    @Autowired EntryRepository entries;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;
    @Autowired MeterRegistry metrics;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final UUID merchant = UUID.randomUUID();
    private final String key = "race-" + UUID.randomUUID();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void requisicaoConcorrenteComMesmaChaveEMesmoConteudoRecebeOLancamentoVencedor() throws Exception {
        RegisterEntryCommand cmd = command("150.75", "Venda balcão");
        CountDownLatch aInserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        double racesBefore = races();

        Future<Entry> a = pool.submit(() -> holdOpenTransaction(cmd, aInserted, releaseA));
        assertThat(aInserted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(countEntries()).as("o lançamento de A ainda não foi confirmado").isZero();

        Future<EntryResult> b = pool.submit(() -> service.register(cmd));
        awaitBlockedOnLock();
        assertThat(b.isDone()).as("B deve estar bloqueada pela transação de A").isFalse();

        releaseA.countDown();
        Entry winner = a.get(10, TimeUnit.SECONDS);
        EntryResult loser = b.get(10, TimeUnit.SECONDS);

        assertThat(loser.replayed()).isTrue();
        assertThat(loser.entry().id()).isEqualTo(winner.id());
        assertThat(countEntries()).isEqualTo(1);
        assertThat(countOutbox()).isEqualTo(1);
        assertThat(races()).isEqualTo(racesBefore + 1);
    }

    @Test
    void requisicaoConcorrenteComMesmaChaveEConteudoDiferenteRecebeConflito() throws Exception {
        CountDownLatch aInserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        double racesBefore = races();

        Future<Entry> a = pool.submit(() -> holdOpenTransaction(command("150.75", "Venda balcão"), aInserted, releaseA));
        assertThat(aInserted.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<EntryResult> b = CompletableFuture.supplyAsync(
                () -> service.register(command("999.00", "Outro conteúdo")), pool);
        awaitBlockedOnLock();

        releaseA.countDown();
        a.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> b.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IdempotencyConflictException.class);
        assertThat(countEntries()).isEqualTo(1);
        assertThat(races()).isEqualTo(racesBefore + 1);
    }

    // ---------- helpers ----------

    private RegisterEntryCommand command(String amount, String description) {
        return new RegisterEntryCommand(merchant, key, LocalDate.now(clock), EntryType.CREDIT,
                new BigDecimal(amount), description);
    }

    /** Transação A: grava o lançamento com a chave e só confirma quando o teste liberar. */
    private Entry holdOpenTransaction(RegisterEntryCommand cmd, CountDownLatch inserted, CountDownLatch release) {
        return tx.execute(status -> {
            Entry entry = Entry.register(cmd.merchantId(), cmd.entryDate(), cmd.type(), cmd.amount(),
                    cmd.description(), clock);
            entries.insert(entry, cmd.idempotencyKey(), cmd.fingerprint());
            jdbc.sql("""
                    INSERT INTO outbox_event (id, aggregate_id, partition_key, event_type, payload, created_at)
                    VALUES (:id, :entry, :key, 'EntryRegistered', '{}'::jsonb, now())
                    """)
                    .param("id", UUID.randomUUID()).param("entry", entry.id()).param("key", merchant.toString())
                    .update();
            inserted.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return entry;
        });
    }

    /** Espera até que exista uma sessão bloqueada aguardando lock ao inserir na tabela entry. */
    private void awaitBlockedOnLock() {
        await("B bloqueada pela constraint UNIQUE(merchant_id, idempotency_key) aguardando a transação de A")
                .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() ->
                jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                         WHERE wait_event_type = 'Lock' AND query ILIKE 'INSERT INTO entry%'
                        """).query(Long.class).single() > 0);
    }

    private long countEntries() {
        return jdbc.sql("SELECT count(*) FROM entry WHERE merchant_id = :m").param("m", merchant)
                .query(Long.class).single();
    }

    private long countOutbox() {
        return jdbc.sql("""
                SELECT count(*) FROM outbox_event o JOIN entry e ON e.id = o.aggregate_id
                 WHERE e.merchant_id = :m
                """).param("m", merchant).query(Long.class).single();
    }

    private double races() {
        var counter = metrics.find("cashflow.idempotency.races").counter();
        return counter == null ? 0 : counter.count();
    }
}
