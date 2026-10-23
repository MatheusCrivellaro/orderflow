package com.matheus.orderflow.payment;

import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.InventoryResultEvent;
import com.matheus.orderflow.messaging.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class PaymentConsumer {

    static final String CONSUMER = "payment-service";

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final JsonMapper jsonMapper;

    /**
     * Só cobra pedidos cujo estoque foi reservado com sucesso.
     */
    @KafkaListener(topics = Topics.INVENTORY_RESULT, groupId = CONSUMER)
    @Transactional
    public void onInventoryResult(String payload) {
        InventoryResultEvent event = jsonMapper.readValue(payload, InventoryResultEvent.class);
        if (event.reserved() && idempotencyService.markAsProcessed(event.eventId(), CONSUMER)) {
            paymentService.process(event);
        }
    }
}
