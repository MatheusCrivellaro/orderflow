package com.matheus.orderflow.messaging.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lê periodicamente os eventos pendentes da tabela outbox e publica no Kafka.
 * A chave da mensagem é o id do pedido, garantindo ordem por pedido dentro da partição.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${app.outbox.publish-interval-ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);
                event.markAsPublished();
                log.debug("Evento {} publicado em {}", event.getEventType(), event.getTopic());
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Para no primeiro erro para manter a ordem; tenta de novo no próximo ciclo
                log.warn("Falha ao publicar evento {}: {}", event.getId(), ex.getMessage());
                break;
            }
        }
    }
}
