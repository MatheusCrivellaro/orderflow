package com.matheus.orderflow.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryResultEvent(
        UUID eventId,
        Long orderId,
        boolean reserved,
        String reason,
        BigDecimal totalAmount,
        List<EventItem> items,
        Instant occurredAt
) implements Event {
}
