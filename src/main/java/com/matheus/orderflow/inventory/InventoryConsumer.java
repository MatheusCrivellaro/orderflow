package com.matheus.orderflow.inventory;

import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.OrderCreatedEvent;
import com.matheus.orderflow.messaging.event.PaymentResultEvent;
import com.matheus.orderflow.messaging.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class InventoryConsumer {

    static final String CONSUMER = "inventory-service";

    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = Topics.ORDER_CREATED, groupId = CONSUMER)
    @Transactional
    public void onOrderCreated(String payload) {
        OrderCreatedEvent event = jsonMapper.readValue(payload, OrderCreatedEvent.class);
        if (idempotencyService.markAsProcessed(event.eventId(), CONSUMER)) {
            inventoryService.reserve(event);
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_RESULT, groupId = CONSUMER)
    @Transactional
    public void onPaymentResult(String payload) {
        PaymentResultEvent event = jsonMapper.readValue(payload, PaymentResultEvent.class);
        if (!event.approved() && idempotencyService.markAsProcessed(event.eventId(), CONSUMER)) {
            inventoryService.release(event);
        }
    }
}
