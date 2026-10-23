package com.matheus.orderflow.messaging;

import java.util.List;

public final class Topics {

    public static final String ORDER_CREATED = "orders.created";
    public static final String INVENTORY_RESULT = "inventory.result";
    public static final String PAYMENT_RESULT = "payments.result";
    public static final String ORDER_STATUS_CHANGED = "orders.status-changed";

    public static final List<String> ALL = List.of(ORDER_CREATED, INVENTORY_RESULT, PAYMENT_RESULT, ORDER_STATUS_CHANGED);

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + ".DLT";
    }
}
