package com.matheus.orderflow.order;

import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.InventoryResultEvent;
import com.matheus.orderflow.messaging.event.PaymentResultEvent;
import com.matheus.orderflow.messaging.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Atualiza o status do pedido conforme os resultados de estoque e pagamento.
 */
@Component
@RequiredArgsConstructor
public class OrderEventsConsumer {

    static final String CONSUMER = "order-service";

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = Topics.INVENTORY_RESULT, groupId = CONSUMER)
    @Transactional
    public void onInventoryResult(String payload) {
        InventoryResultEvent event = jsonMapper.readValue(payload, InventoryResultEvent.class);
        if (idempotencyService.markAsProcessed(event.eventId(), CONSUMER)) {
            orderService.handleInventoryResult(event);
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_RESULT, groupId = CONSUMER)
    @Transactional
    public void onPaymentResult(String payload) {
        PaymentResultEvent event = jsonMapper.readValue(payload, PaymentResultEvent.class);
        if (idempotencyService.markAsProcessed(event.eventId(), CONSUMER)) {
            orderService.handlePaymentResult(event);
        }
    }
}
