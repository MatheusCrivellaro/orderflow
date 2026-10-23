package com.matheus.orderflow.order;

import com.matheus.orderflow.common.exception.NotFoundException;
import com.matheus.orderflow.config.SecurityConfig;
import com.matheus.orderflow.order.dto.CreateOrderRequest;
import com.matheus.orderflow.order.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste da camada web isolada: só o controller, a segurança e o tratamento de erros.
 * O OrderService é substituído por um mock.
 */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

    private static final String VALID_BODY = """
            { "items": [ { "productId": 1, "quantity": 2 } ] }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    @DisplayName("POST /orders sem token retorna 401")
    void shouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("POST /orders com token cria o pedido e retorna 201 + Location")
    void shouldCreateOrder() throws Exception {
        when(orderService.create(eq(1L), any(CreateOrderRequest.class))).thenReturn(pendingOrder());

        mockMvc.perform(post("/orders")
                        .with(user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/10"))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /orders com dados inválidos retorna 400 com os campos com erro")
    void shouldValidateRequest() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "productId": 1, "quantity": 0 } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Dados inválidos"))
                .andExpect(jsonPath("$.errors['items[0].quantity']").exists());

        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("GET /orders/{id} inexistente retorna 404 no formato Problem Details")
    void shouldReturnNotFound() throws Exception {
        when(orderService.findById(eq(99L), any(CurrentUser.class)))
                .thenThrow(new NotFoundException("Pedido não encontrado: 99"));

        mockMvc.perform(get("/orders/99").with(user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Pedido não encontrado: 99"));
    }

    private RequestPostProcessor user(Long id) {
        return jwt().jwt(token -> token.subject(String.valueOf(id)).claim("roles", List.of("USER")));
    }

    private OrderResponse pendingOrder() {
        Instant now = Instant.now();
        return new OrderResponse(10L, 1L, OrderStatus.PENDING, new BigDecimal("200.00"), null, List.of(), now, now);
    }
}
