package br.com.cashflow.launch.application;

import br.com.cashflow.launch.domain.DomainException;
import br.com.cashflow.launch.domain.Entry;
import br.com.cashflow.launch.infrastructure.persistence.EntryRepository;
import br.com.cashflow.launch.infrastructure.persistence.EntryRepository.StoredEntry;
import br.com.cashflow.launch.infrastructure.persistence.OutboxEvent;
import br.com.cashflow.launch.infrastructure.persistence.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Caso de uso de registro. O lançamento e o evento de integração são gravados na MESMA
 * transação (Transactional Outbox). Nenhuma chamada a outro serviço acontece aqui, por isso
 * a disponibilidade do registro não depende do consolidado nem do broker.
 */
@Service
public class EntryService {

    private final EntryRepository entries;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;

    public EntryService(EntryRepository entries, OutboxRepository outbox, TransactionTemplate tx,
                        ObjectMapper json, Clock clock, MeterRegistry metrics) {
        this.entries = entries;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    public EntryResult register(RegisterEntryCommand cmd) {
        String hash = cmd.fingerprint();
        return idempotent(cmd.merchantId(), cmd.idempotencyKey(), hash, () -> {
            Entry entry = Entry.register(cmd.merchantId(), cmd.entryDate(), cmd.type(), cmd.amount(),
                    cmd.description(), clock);
            persist(entry, cmd.idempotencyKey(), hash);
            return entry;
        });
    }

    public EntryResult reverse(UUID merchantId, UUID entryId, String idempotencyKey, String reason) {
        String hash = RegisterEntryCommand.sha256("REVERSE|" + entryId + "|" + reason);
        return idempotent(merchantId, idempotencyKey, hash, () -> {
            Entry original = entries.findByIdForUpdate(merchantId, entryId)
                    .orElseThrow(() -> new NotFoundException("Lançamento não encontrado"));
            if (entries.hasReversal(entryId)) {
                throw new DomainException("Lançamento já estornado");
            }
            Entry reversal = original.reverse(reason, clock);
            persist(reversal, idempotencyKey, hash);
            return reversal;
        });
    }

    public Entry find(UUID merchantId, UUID id) {
        return entries.findById(merchantId, id).orElseThrow(() -> new NotFoundException("Lançamento não encontrado"));
    }

    public List<Entry> listByDate(UUID merchantId, LocalDate date, int page, int size) {
        return entries.findByDate(merchantId, date, size, page * size);
    }

    private void persist(Entry entry, String idempotencyKey, String hash) {
        entries.insert(entry, idempotencyKey, hash);
        EntryRegisteredEvent event = EntryRegisteredEvent.of(entry);
        outbox.insert(new OutboxEvent(event.eventId(), entry.id(), entry.merchantId().toString(),
                EntryRegisteredEvent.TYPE, toJson(event), entry.createdAt()));
        metrics.counter("cashflow.entries.registered", "type", entry.type().name(),
                "reversal", String.valueOf(entry.isReversal())).increment();
    }

    /**
     * Executa a criação uma única vez por (comerciante, chave). Uma corrida entre duas
     * requisições iguais é resolvida pela constraint única: a perdedora relê o registro vencedor.
     */
    private EntryResult idempotent(UUID merchantId, String key, String hash, Supplier<Entry> creation) {
        Optional<EntryResult> previous = replay(merchantId, key, hash);
        if (previous.isPresent()) {
            return previous.get();
        }
        try {
            return new EntryResult(tx.execute(status -> creation.get()), false);
        } catch (DuplicateKeyException race) {
            return replay(merchantId, key, hash).orElseThrow(() -> race);
        }
    }

    private Optional<EntryResult> replay(UUID merchantId, String key, String hash) {
        Optional<StoredEntry> stored = entries.findByIdempotencyKey(merchantId, key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (!stored.get().requestHash().equals(hash)) {
            throw new IdempotencyConflictException();
        }
        return Optional.of(new EntryResult(stored.get().entry(), true));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
