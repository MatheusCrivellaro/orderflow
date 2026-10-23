package com.matheus.orderflow.order;

import com.matheus.orderflow.product.Product;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agregado Pedido. As transições de status ficam encapsuladas aqui:
 * PENDING -> STOCK_RESERVED -> CONFIRMED, ou CANCELLED a partir de qualquer status não final.
 * Os métodos retornam false quando a transição não se aplica (ex.: evento atrasado),
 * o que deixa o processamento dos eventos seguro mesmo fora de ordem.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Order create(Long userId) {
        Order order = new Order();
        order.userId = userId;
        order.status = OrderStatus.PENDING;
        order.totalAmount = BigDecimal.ZERO;
        order.createdAt = Instant.now();
        order.updatedAt = order.createdAt;
        return order;
    }

    public void addItem(Product product, int quantity) {
        OrderItem item = new OrderItem(this, product.getId(), product.getName(), product.getPrice(), quantity);
        items.add(item);
        totalAmount = totalAmount.add(item.getSubtotal());
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public boolean markStockReserved() {
        if (status != OrderStatus.PENDING) {
            return false;
        }
        changeStatus(OrderStatus.STOCK_RESERVED);
        return true;
    }

    public boolean confirm() {
        if (status.isFinal()) {
            return false;
        }
        changeStatus(OrderStatus.CONFIRMED);
        return true;
    }

    public boolean cancel(String reason) {
        if (status.isFinal()) {
            return false;
        }
        this.failureReason = reason;
        changeStatus(OrderStatus.CANCELLED);
        return true;
    }

    private void changeStatus(OrderStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }
}
