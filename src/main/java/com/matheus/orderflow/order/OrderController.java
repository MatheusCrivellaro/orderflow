package com.matheus.orderflow.order;

import com.matheus.orderflow.common.dto.PageResponse;
import com.matheus.orderflow.order.dto.CreateOrderRequest;
import com.matheus.orderflow.order.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Pedidos")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Cria um pedido",
            description = "O pedido é criado como PENDING e processado de forma assíncrona (estoque -> pagamento).")
    public ResponseEntity<OrderResponse> create(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.create(CurrentUser.from(jwt).id(), request);
        return ResponseEntity.created(URI.create("/orders/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta um pedido (o dono ou ADMIN)")
    public OrderResponse findById(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                  @PathVariable Long id) {
        return orderService.findById(id, CurrentUser.from(jwt));
    }

    @GetMapping
    @Operation(summary = "Lista os pedidos do usuário (ADMIN vê todos)")
    public PageResponse<OrderResponse> findAll(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @ParameterObject @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return orderService.findAll(CurrentUser.from(jwt), pageable);
    }
}
