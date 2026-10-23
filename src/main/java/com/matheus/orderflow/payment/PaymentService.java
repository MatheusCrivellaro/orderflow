package com.matheus.orderflow.payment;

import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.event.InventoryResultEvent;
import com.matheus.orderflow.messaging.event.PaymentResultEvent;
import com.matheus.orderflow.messaging.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Gateway de pagamento simulado: aprova pedidos até o limite configurado
 * em app.payment.approval-limit e recusa os que passam dele.
 */
@Slf4j
@Service
public class PaymentService {

    static final String REJECTION_REASON = "Pagamento recusado: valor acima do limite de crédito";

    private final OutboxService outboxService;
    private final BigDecimal approvalLimit;

    public PaymentService(OutboxService outboxService,
                          @Value("${app.payment.approval-limit}") BigDecimal approvalLimit) {
        this.outboxService = outboxService;
        this.approvalLimit = approvalLimit;
    }

    @Transactional
    public void process(InventoryResultEvent event) {
        boolean approved = event.totalAmount().compareTo(approvalLimit) <= 0;
        log.info("Pedido {}: pagamento de R$ {} {}", event.orderId(), event.totalAmount(),
                approved ? "aprovado" : "recusado");

        outboxService.save(Topics.PAYMENT_RESULT, event.orderId(), new PaymentResultEvent(
                UUID.randomUUID(),
                event.orderId(),
                approved,
                approved ? null : REJECTION_REASON,
                event.items(),
                Instant.now()));
    }
}
