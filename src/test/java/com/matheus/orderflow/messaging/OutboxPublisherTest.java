package com.matheus.orderflow.messaging;

import com.matheus.orderflow.messaging.outbox.OutboxEvent;
import com.matheus.orderflow.messaging.outbox.OutboxPublisher;
import com.matheus.orderflow.messaging.outbox.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxPublisher outboxPublisher;

    @Test
    @DisplayName("Publica eventos pendentes no Kafka usando o id do pedido como chave")
    void shouldPublishPendingEvents() {
        OutboxEvent first = event("1");
        OutboxEvent second = event("2");
        when(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPending();

        verify(kafkaTemplate).send(Topics.ORDER_CREATED, "1", "{}");
        verify(kafkaTemplate).send(Topics.ORDER_CREATED, "2", "{}");
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Se o Kafka falhar, para e mantém os eventos pendentes para a próxima execução")
    void shouldStopOnFailure() {
        OutboxEvent first = event("1");
        OutboxEvent second = event("2");
        when(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka fora do ar")));

        outboxPublisher.publishPending();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        assertThat(first.getPublishedAt()).isNull();
        assertThat(second.getPublishedAt()).isNull();
    }

    private OutboxEvent event(String orderId) {
        return new OutboxEvent(UUID.randomUUID(), orderId, "OrderCreatedEvent", Topics.ORDER_CREATED, "{}");
    }
}
