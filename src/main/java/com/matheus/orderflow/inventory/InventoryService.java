package com.matheus.orderflow.inventory;

import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.EventItem;
import com.matheus.orderflow.messaging.event.InventoryResultEvent;
import com.matheus.orderflow.messaging.event.OrderCreatedEvent;
import com.matheus.orderflow.messaging.event.PaymentResultEvent;
import com.matheus.orderflow.messaging.outbox.OutboxService;
import com.matheus.orderflow.product.Product;
import com.matheus.orderflow.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;
    private final OutboxService outboxService;

    /**
     * Reserva o estoque de todos os itens do pedido (tudo ou nada) e publica o resultado.
     */
    @Transactional
    public void reserve(OrderCreatedEvent event) {
        Map<Long, Product> products = lockProducts(event.items());

        Optional<String> problem = findProblem(event.items(), products);
        if (problem.isPresent()) {
            log.info("Pedido {}: reserva recusada - {}", event.orderId(), problem.get());
            publishResult(event, false, problem.get());
            return;
        }

        event.items().forEach(item -> products.get(item.productId()).decreaseStock(item.quantity()));
        publishResult(event, true, null);
    }

    /**
     * Compensação (Saga): se o pagamento foi recusado, devolve o estoque reservado.
     */
    @Transactional
    public void release(PaymentResultEvent event) {
        Map<Long, Product> products = lockProducts(event.items());
        event.items().forEach(item -> {
            Product product = products.get(item.productId());
            if (product != null) {
                product.increaseStock(item.quantity());
            }
        });
        log.info("Pedido {}: estoque devolvido após pagamento recusado", event.orderId());
    }

    private Map<Long, Product> lockProducts(List<EventItem> items) {
        List<Long> ids = items.stream().map(EventItem::productId).toList();
        return productRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private Optional<String> findProblem(List<EventItem> items, Map<Long, Product> products) {
        for (EventItem item : items) {
            Product product = products.get(item.productId());
            if (product == null) {
                return Optional.of("Produto " + item.productId() + " indisponível");
            }
            if (!product.hasStock(item.quantity())) {
                return Optional.of("Estoque insuficiente para o produto " + product.getName());
            }
        }
        return Optional.empty();
    }

    private void publishResult(OrderCreatedEvent event, boolean reserved, String reason) {
        outboxService.save(Topics.INVENTORY_RESULT, event.orderId(), new InventoryResultEvent(
                UUID.randomUUID(),
                event.orderId(),
                reserved,
                reason,
                event.totalAmount(),
                event.items(),
                Instant.now()));
    }
}
