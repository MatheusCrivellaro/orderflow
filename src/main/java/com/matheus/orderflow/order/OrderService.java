package com.matheus.orderflow.order;

import com.matheus.orderflow.common.dto.PageResponse;
import com.matheus.orderflow.common.exception.NotFoundException;
import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.EventItem;
import com.matheus.orderflow.messaging.event.InventoryResultEvent;
import com.matheus.orderflow.messaging.event.OrderCreatedEvent;
import com.matheus.orderflow.messaging.event.OrderStatusChangedEvent;
import com.matheus.orderflow.messaging.event.PaymentResultEvent;
import com.matheus.orderflow.messaging.outbox.OutboxService;
import com.matheus.orderflow.order.dto.CreateOrderRequest;
import com.matheus.orderflow.order.dto.OrderItemRequest;
import com.matheus.orderflow.order.dto.OrderResponse;
import com.matheus.orderflow.product.Product;
import com.matheus.orderflow.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OutboxService outboxService;

    /**
     * Cria o pedido como PENDING e registra o evento OrderCreated.
     * A reserva de estoque e o pagamento acontecem de forma assíncrona.
     */
    @Transactional
    public OrderResponse create(Long userId, CreateOrderRequest request) {
        // Soma as quantidades caso o mesmo produto apareça mais de uma vez
        Map<Long, Integer> quantities = request.items().stream()
                .collect(Collectors.toMap(OrderItemRequest::productId, OrderItemRequest::quantity,
                        Integer::sum, LinkedHashMap::new));

        Map<Long, Product> products = productRepository.findAllById(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));

        Order order = Order.create(userId);
        quantities.forEach((productId, quantity) -> {
            Product product = products.get(productId);
            if (product == null) {
                throw new NotFoundException("Produto não encontrado: " + productId);
            }
            order.addItem(product, quantity);
        });
        orderRepository.save(order);

        outboxService.save(Topics.ORDER_CREATED, order.getId(), new OrderCreatedEvent(
                UUID.randomUUID(),
                order.getId(),
                userId,
                order.getTotalAmount(),
                toEventItems(order),
                Instant.now()));

        log.info("Pedido {} criado com status {}", order.getId(), order.getStatus());
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long orderId, CurrentUser user) {
        return orderRepository.findWithItemsById(orderId)
                .filter(order -> user.admin() || order.getUserId().equals(user.id()))
                .map(OrderResponse::from)
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado: " + orderId));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> findAll(CurrentUser user, Pageable pageable) {
        Page<Order> orders = user.admin()
                ? orderRepository.findAll(pageable)
                : orderRepository.findByUserId(user.id(), pageable);
        return PageResponse.from(orders.map(OrderResponse::from));
    }

    @Transactional
    public void handleInventoryResult(InventoryResultEvent event) {
        Order order = getOrder(event.orderId());
        if (event.reserved()) {
            order.markStockReserved();
            log.info("Pedido {}: estoque reservado", order.getId());
        } else if (order.cancel(event.reason())) {
            publishStatusChanged(order);
        }
    }

    @Transactional
    public void handlePaymentResult(PaymentResultEvent event) {
        Order order = getOrder(event.orderId());
        boolean changed = event.approved() ? order.confirm() : order.cancel(event.reason());
        if (changed) {
            publishStatusChanged(order);
        }
    }

    private void publishStatusChanged(Order order) {
        log.info("Pedido {} finalizado com status {}", order.getId(), order.getStatus());
        outboxService.save(Topics.ORDER_STATUS_CHANGED, order.getId(), new OrderStatusChangedEvent(
                UUID.randomUUID(),
                order.getId(),
                order.getUserId(),
                order.getStatus().name(),
                order.getFailureReason(),
                Instant.now()));
    }

    private Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado: " + orderId));
    }

    private List<EventItem> toEventItems(Order order) {
        return order.getItems().stream()
                .map(item -> new EventItem(item.getProductId(), item.getQuantity()))
                .toList();
    }
}
