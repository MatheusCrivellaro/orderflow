package com.matheus.orderflow.messaging.event;

import java.util.UUID;

/**
 * Todo evento tem um id único, usado para garantir idempotência nos consumers.
 */
public interface Event {

    UUID eventId();
}
