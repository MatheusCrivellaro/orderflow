package com.matheus.orderflow.messaging;

import com.matheus.orderflow.messaging.idempotency.IdempotencyService;
import com.matheus.orderflow.messaging.idempotency.ProcessedEvent;
import com.matheus.orderflow.messaging.idempotency.ProcessedEventId;
import com.matheus.orderflow.messaging.idempotency.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ProcessedEventRepository repository;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Test
    @DisplayName("Primeira entrega do evento: registra e permite processar")
    void shouldRegisterFirstDelivery() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(new ProcessedEventId(eventId, "payment-service"))).thenReturn(false);

        assertThat(idempotencyService.markAsProcessed(eventId, "payment-service")).isTrue();
        verify(repository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Evento duplicado: não processa de novo")
    void shouldIgnoreDuplicatedDelivery() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(new ProcessedEventId(eventId, "payment-service"))).thenReturn(true);

        assertThat(idempotencyService.markAsProcessed(eventId, "payment-service")).isFalse();
        verify(repository, never()).save(any());
    }
}
