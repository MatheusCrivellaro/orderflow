package com.matheus.orderflow.messaging.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentResultEvent(
        UUID eventId,
        Long orderId,
        boolean approved,
        String reason,
        List<EventItem> items,
        Instant occurredAt
) implements Event {
}
