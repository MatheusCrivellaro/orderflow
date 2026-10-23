package com.matheus.orderflow.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.matheus.orderflow.TestFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    @DisplayName("Novo pedido começa PENDING e soma o total dos itens")
    void shouldStartPendingAndCalculateTotal() {
        Order order = Order.create(1L);
        order.addItem(product(1L, "Teclado", "100.00", 10), 2);
        order.addItem(product(2L, "Mouse", "50.50", 10), 1);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("250.50");
        assertThat(order.getItems())
                .hasSize(2)
                .extracting(OrderItem::getProductName)
                .containsExactly("Teclado", "Mouse");
    }

    @Test
    @DisplayName("Fluxo feliz: PENDING -> STOCK_RESERVED -> CONFIRMED")
    void shouldFollowHappyPath() {
        Order order = Order.create(1L);

        assertThat(order.markStockReserved()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.STOCK_RESERVED);

        assertThat(order.confirm()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Pedido cancelado guarda o motivo")
    void shouldCancelWithReason() {
        Order order = Order.create(1L);

        assertThat(order.cancel("Estoque insuficiente")).isTrue();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFailureReason()).isEqualTo("Estoque insuficiente");
    }

    @Test
    @DisplayName("Status final não muda mais (protege contra eventos atrasados)")
    void shouldIgnoreTransitionsAfterFinalStatus() {
        Order order = Order.create(1L);
        order.confirm();

        assertThat(order.cancel("atrasado")).isFalse();
        assertThat(order.markStockReserved()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("Lista de itens não pode ser alterada por fora do agregado")
    void shouldExposeUnmodifiableItems() {
        Order order = Order.create(1L);

        assertThatThrownBy(() -> order.getItems().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
