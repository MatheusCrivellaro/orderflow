package com.matheus.orderflow.payment;

import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.Event;
import com.matheus.orderflow.messaging.event.EventItem;
import com.matheus.orderflow.messaging.event.InventoryResultEvent;
import com.matheus.orderflow.messaging.event.PaymentResultEvent;
import com.matheus.orderflow.messaging.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentServiceTest {

    private OutboxService outboxService;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        outboxService = mock(OutboxService.class);
        paymentService = new PaymentService(outboxService, new BigDecimal("5000.00"));
    }

    @ParameterizedTest(name = "R$ {0} -> aprovado = {1}")
    @CsvSource({
            "100.00,  true",
            "5000.00, true",
            "5000.01, false",
            "7499.00, false"
    })
    @DisplayName("Aprova pagamentos até o limite e recusa acima dele")
    void shouldApproveUpToLimit(String amount, boolean expectedApproved) {
        var event = new InventoryResultEvent(UUID.randomUUID(), 5L, true, null,
                new BigDecimal(amount), List.of(new EventItem(1L, 1)), Instant.now());

        paymentService.process(event);

        var captor = ArgumentCaptor.forClass(Event.class);
        verify(outboxService).save(eq(Topics.PAYMENT_RESULT), eq(5L), captor.capture());
        PaymentResultEvent result = (PaymentResultEvent) captor.getValue();
        assertThat(result.approved()).isEqualTo(expectedApproved);
        assertThat(result.items()).isEqualTo(event.items());
        if (!expectedApproved) {
            assertThat(result.reason()).isEqualTo(PaymentService.REJECTION_REASON);
        }
    }
}
