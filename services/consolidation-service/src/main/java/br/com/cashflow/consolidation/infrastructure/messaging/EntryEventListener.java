package br.com.cashflow.consolidation.infrastructure.messaging;

import br.com.cashflow.consolidation.application.ConsolidationService;
import br.com.cashflow.consolidation.domain.EntryRegistered;
import br.com.cashflow.consolidation.domain.InvalidEventException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EntryEventListener {

    private static final Logger log = LoggerFactory.getLogger(EntryEventListener.class);

    private final ConsolidationService service;
    private final ObjectMapper json;

    public EntryEventListener(ConsolidationService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(topics = "${cashflow.consumer.topic}", concurrency = "${cashflow.consumer.concurrency:3}")
    public void onEntryRegistered(ConsumerRecord<String, String> record) {
        EntryRegistered event = parse(record.value());
        boolean applied = service.apply(event);
        log.debug("Evento {} do lançamento {} {}", event.eventId(), event.entryId(), applied ? "aplicado" : "ignorado (duplicado)");
    }

    private EntryRegistered parse(String payload) {
        try {
            return json.readValue(payload, EntryRegistered.class);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Payload ilegível: " + e.getOriginalMessage());
        }
    }
}
