package com.matheus.orderflow.order;

import com.matheus.orderflow.common.exception.NotFoundException;
import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.Event;
import com.matheus.orderflow.messaging.event.EventItem;
import com.matheus.orderflow.messaging.event.InventoryResultEvent;
import com.matheus.orderflow.messaging.event.OrderCreatedEvent;
import com.matheus.orderflow.messaging.event.OrderStatusChangedEvent;
import com.matheus.orderflow.messaging.event.PaymentResultEvent;
import com.matheus.orderflow.messaging.outbox.OutboxService;
import com.matheus.orderflow.order.dto.CreateOrderRequest;
import com.matheus.orderflow.order.dto.OrderItemRequest;
import com.matheus.orderflow.order.dto.OrderResponse;
import com.matheus.orderflow.product.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.matheus.orderflow.TestFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("Cria pedido PENDING e registra o evento OrderCreated no outbox")
    void shouldCreateOrderAndSaveEvent() {
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(
                product(1L, "Teclado", "100.00", 10),
                product(2L, "Mouse", "50.00", 10)));
        var request = new CreateOrderRequest(List.of(
                new OrderItemRequest(1L, 1),
                new OrderItemRequest(2L, 2),
                new OrderItemRequest(1L, 1))); // produto repetido: quantidades somadas

        OrderResponse response = orderService.create(7L, request);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.totalAmount()).isEqualByComparingTo("300.00");
        assertThat(response.items()).hasSize(2);
        verify(orderRepository).save(any(Order.class));

        var eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(outboxService).save(eq(Topics.ORDER_CREATED), any(), eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOfSatisfying(OrderCreatedEvent.class, event -> {
                    assertThat(event.userId()).isEqualTo(7L);
                    assertThat(event.items()).containsExactly(new EventItem(1L, 2), new EventItem(2L, 2));
                });
    }

    @Test
    @DisplayName("Lança NotFound quando um produto do pedido não existe")
    void shouldFailWhenProductDoesNotExist() {
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of());
        var request = new CreateOrderRequest(List.of(new OrderItemRequest(99L, 1)));

        assertThatThrownBy(() -> orderService.create(1L, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("Usuário comum não acessa pedido de outro usuário")
    void shouldHideOrderFromOtherUser() {
        when(orderRepository.findWithItemsById(1L)).thenReturn(Optional.of(Order.create(10L)));

        assertThatThrownBy(() -> orderService.findById(1L, new CurrentUser(20L, false)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Admin acessa pedido de qualquer usuário")
    void shouldAllowAdminToSeeAnyOrder() {
        when(orderRepository.findWithItemsById(1L)).thenReturn(Optional.of(Order.create(10L)));

        OrderResponse response = orderService.findById(1L, new CurrentUser(20L, true));

        assertThat(response.userId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Estoque reservado muda o status para STOCK_RESERVED sem notificar")
    void shouldMarkStockReserved() {
        Order order = Order.create(1L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        orderService.handleInventoryResult(inventoryResult(true, null));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.STOCK_RESERVED);
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("Falha de estoque cancela o pedido e publica OrderStatusChanged")
    void shouldCancelWhenStockFails() {
        Order order = Order.create(1L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        orderService.handleInventoryResult(inventoryResult(false, "Estoque insuficiente"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFailureReason()).isEqualTo("Estoque insuficiente");
        verify(outboxService).save(eq(Topics.ORDER_STATUS_CHANGED), any(), any(OrderStatusChangedEvent.class));
    }

    @Test
    @DisplayName("Pagamento aprovado confirma o pedido")
    void shouldConfirmWhenPaymentApproved() {
        Order order = Order.create(1L);
        order.markStockReserved();
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        orderService.handlePaymentResult(paymentResult(true, null));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outboxService).save(eq(Topics.ORDER_STATUS_CHANGED), any(), any(OrderStatusChangedEvent.class));
    }

    @Test
    @DisplayName("Pagamento recusado cancela o pedido")
    void shouldCancelWhenPaymentRejected() {
        Order order = Order.create(1L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        orderService.handlePaymentResult(paymentResult(false, "Limite excedido"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFailureReason()).isEqualTo("Limite excedido");
    }

    @Test
    @DisplayName("Evento para pedido já finalizado não gera nova notificação")
    void shouldNotPublishWhenOrderAlreadyFinal() {
        Order order = Order.create(1L);
        order.cancel("motivo anterior");
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        orderService.handlePaymentResult(paymentResult(true, null));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(outboxService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("Evento de pedido inexistente lança NotFound (vai para retry/DLT)")
    void shouldFailWhenOrderNotFound() {
        when(orderRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.handlePaymentResult(paymentResult(true, null)))
                .isInstanceOf(NotFoundException.class);
    }

    private InventoryResultEvent inventoryResult(boolean reserved, String reason) {
        return new InventoryResultEvent(UUID.randomUUID(), 5L, reserved, reason,
                new BigDecimal("100.00"), List.of(new EventItem(1L, 1)), Instant.now());
    }

    private PaymentResultEvent paymentResult(boolean approved, String reason) {
        return new PaymentResultEvent(UUID.randomUUID(), 5L, approved, reason,
                List.of(new EventItem(1L, 1)), Instant.now());
    }
}
