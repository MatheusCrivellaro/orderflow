package com.matheus.orderflow.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record OrderStatusChangedEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        String status,
        String reason,
        Instant occurredAt
) implements Event {
}
