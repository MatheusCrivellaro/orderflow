package com.matheus.orderflow.messaging.outbox;

import com.matheus.orderflow.messaging.event.Event;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional Outbox: em vez de publicar direto no Kafka, o evento é salvo no banco
 * na mesma transação da regra de negócio. O {@link OutboxPublisher} publica depois.
 * Assim, nunca existe pedido salvo sem evento (nem evento sem pedido).
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(String topic, Object aggregateId, Event event) {
        outboxRepository.save(new OutboxEvent(
                event.eventId(),
                String.valueOf(aggregateId),
                event.getClass().getSimpleName(),
                topic,
                jsonMapper.writeValueAsString(event)));
    }
}
