package com.matheus.orderflow.notification;

import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.OrderStatusChangedEvent;
import com.matheus.orderflow.messaging.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simula o envio de notificação (e-mail/push) quando o pedido é finalizado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    static final String CONSUMER = "notification-service";

    private final IdempotencyService idempotencyService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = Topics.ORDER_STATUS_CHANGED, groupId = CONSUMER)
    @Transactional
    public void onOrderStatusChanged(String payload) {
        OrderStatusChangedEvent event = jsonMapper.readValue(payload, OrderStatusChangedEvent.class);
        if (!idempotencyService.markAsProcessed(event.eventId(), CONSUMER)) {
            return;
        }

        if ("CONFIRMED".equals(event.status())) {
            log.info("[NOTIFICAÇÃO] Usuário {}: seu pedido #{} foi confirmado!", event.userId(), event.orderId());
        } else {
            log.info("[NOTIFICAÇÃO] Usuário {}: seu pedido #{} foi cancelado. Motivo: {}",
                    event.userId(), event.orderId(), event.reason());
        }
    }
}
