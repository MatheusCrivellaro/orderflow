package com.matheus.orderflow.inventory;

import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.Event;
import com.matheus.orderflow.messaging.event.EventItem;
import com.matheus.orderflow.messaging.event.InventoryResultEvent;
import com.matheus.orderflow.messaging.event.OrderCreatedEvent;
import com.matheus.orderflow.messaging.event.PaymentResultEvent;
import com.matheus.orderflow.messaging.outbox.OutboxService;
import com.matheus.orderflow.product.Product;
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
import java.util.UUID;

import static com.matheus.orderflow.TestFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private InventoryService inventoryService;

    @Test
    @DisplayName("Reserva o estoque de todos os itens e publica sucesso")
    void shouldReserveStock() {
        Product keyboard = product(1L, "Teclado", "100.00", 10);
        Product mouse = product(2L, "Mouse", "50.00", 5);
        when(productRepository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(keyboard, mouse));

        inventoryService.reserve(orderCreated(new EventItem(1L, 3), new EventItem(2L, 5)));

        assertThat(keyboard.getStock()).isEqualTo(7);
        assertThat(mouse.getStock()).isZero();
        assertThat(publishedResult().reserved()).isTrue();
    }

    @Test
    @DisplayName("Sem estoque suficiente: não reserva nada (tudo ou nada) e publica falha")
    void shouldNotReserveWhenStockIsInsufficient() {
        Product keyboard = product(1L, "Teclado", "100.00", 10);
        Product mouse = product(2L, "Mouse", "50.00", 1);
        when(productRepository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(keyboard, mouse));

        inventoryService.reserve(orderCreated(new EventItem(1L, 3), new EventItem(2L, 2)));

        assertThat(keyboard.getStock()).isEqualTo(10);
        assertThat(mouse.getStock()).isEqualTo(1);
        InventoryResultEvent result = publishedResult();
        assertThat(result.reserved()).isFalse();
        assertThat(result.reason()).isEqualTo("Estoque insuficiente para o produto Mouse");
    }

    @Test
    @DisplayName("Produto removido do catálogo gera falha na reserva")
    void shouldFailWhenProductNoLongerExists() {
        when(productRepository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of());

        inventoryService.reserve(orderCreated(new EventItem(42L, 1)));

        assertThat(publishedResult().reason()).isEqualTo("Produto 42 indisponível");
    }

    @Test
    @DisplayName("Compensação: devolve o estoque quando o pagamento é recusado")
    void shouldReleaseStock() {
        Product keyboard = product(1L, "Teclado", "100.00", 7);
        when(productRepository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(keyboard));
        var event = new PaymentResultEvent(UUID.randomUUID(), 5L, false, "recusado",
                List.of(new EventItem(1L, 3)), Instant.now());

        inventoryService.release(event);

        assertThat(keyboard.getStock()).isEqualTo(10);
    }

    private OrderCreatedEvent orderCreated(EventItem... items) {
        return new OrderCreatedEvent(UUID.randomUUID(), 5L, 1L, new BigDecimal("100.00"), List.of(items), Instant.now());
    }

    private InventoryResultEvent publishedResult() {
        var captor = ArgumentCaptor.forClass(Event.class);
        verify(outboxService).save(eq(Topics.INVENTORY_RESULT), eq(5L), captor.capture());
        return (InventoryResultEvent) captor.getValue();
    }
}
