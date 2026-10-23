package com.matheus.orderflow;

import com.jayway.jsonpath.JsonPath;
import com.matheus.orderflow.messaging.Topics;
import com.matheus.orderflow.messaging.outbox.OutboxRepository;
import com.matheus.orderflow.order.Order;
import com.matheus.orderflow.order.OrderRepository;
import com.matheus.orderflow.order.OrderStatus;
import com.matheus.orderflow.product.Product;
import com.matheus.orderflow.product.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de ponta a ponta: API -> PostgreSQL -> Outbox -> Kafka -> consumers -> PostgreSQL.
 * Usa containers reais (Testcontainers) e Awaitility para esperar o processamento assíncrono.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderFlowIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Pedido com estoque e pagamento aprovado termina CONFIRMED e baixa o estoque")
    void shouldConfirmOrder() throws Exception {
        Product product = createProduct("100.00", 10);
        String token = registerAndLogin();

        Long orderId = createOrder(token, product.getId(), 2);

        await().atMost(TIMEOUT).untilAsserted(() ->
                mockMvc.perform(get("/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("CONFIRMED")));

        assertThat(stockOf(product)).isEqualTo(8);
    }

    @Test
    @DisplayName("Pagamento recusado cancela o pedido e devolve o estoque (compensação)")
    void shouldCancelAndReleaseStockWhenPaymentIsRejected() throws Exception {
        Product product = createProduct("6000.00", 5);
        String token = registerAndLogin();

        Long orderId = createOrder(token, product.getId(), 1);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getFailureReason()).startsWith("Pagamento recusado");
        });
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(stockOf(product)).isEqualTo(5));
    }

    @Test
    @DisplayName("Estoque insuficiente cancela o pedido sem cobrar")
    void shouldCancelWhenStockIsInsufficient() throws Exception {
        Product product = createProduct("50.00", 1);
        String token = registerAndLogin();

        Long orderId = createOrder(token, product.getId(), 3);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getFailureReason()).startsWith("Estoque insuficiente");
        });
        assertThat(stockOf(product)).isEqualTo(1);
    }

    @Test
    @DisplayName("Evento duplicado no Kafka não baixa o estoque duas vezes (idempotência)")
    void shouldIgnoreDuplicatedEvent() throws Exception {
        Product product = createProduct("100.00", 10);
        String token = registerAndLogin();
        Long orderId = createOrder(token, product.getId(), 4);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED));

        // Reenvia exatamente a mesma mensagem OrderCreated, simulando uma entrega duplicada
        String originalPayload = outboxRepository.findAll().stream()
                .filter(event -> event.getTopic().equals(Topics.ORDER_CREATED))
                .filter(event -> event.getAggregateId().equals(orderId.toString()))
                .findFirst().orElseThrow()
                .getPayload();
        kafkaTemplate.send(Topics.ORDER_CREATED, orderId.toString(), originalPayload).get();

        // O estoque deve continuar 6 durante todo o intervalo
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(stockOf(product)).isEqualTo(6));
    }

    @Test
    @DisplayName("Usuário não vê pedido de outro usuário")
    void shouldNotExposeOrdersOfOtherUsers() throws Exception {
        Product product = createProduct("10.00", 10);
        Long orderId = createOrder(registerAndLogin(), product.getId(), 1);
        String otherUserToken = registerAndLogin();

        mockMvc.perform(get("/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(otherUserToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Somente ADMIN cadastra produtos; listagem de produtos é pública")
    void shouldApplyProductPermissions() throws Exception {
        String body = """
                { "name": "Webcam", "description": "Full HD", "price": 250.00, "stock": 10 }
                """;

        mockMvc.perform(post("/products").header(HttpHeaders.AUTHORIZATION, bearer(registerAndLogin()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/products").header(HttpHeaders.AUTHORIZATION, bearer(login("admin@orderflow.com", "admin123")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Webcam"));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("Interface web é pública")
    void shouldServeWebInterface() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/app.css")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Login com senha errada retorna 401")
    void shouldRejectInvalidLogin() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "admin@orderflow.com", "password": "senha-errada" }
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private Product createProduct(String price, int stock) {
        return productRepository.save(new Product("Produto " + UUID.randomUUID(), null, new BigDecimal(price), stock));
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private String registerAndLogin() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@email.com";
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Cliente Teste", "email": "%s", "password": "123456" }
                                """.formatted(email)))
                .andExpect(status().isCreated());
        return login(email, "123456");
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s", "password": "%s" }
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private Long createOrder(String token, Long productId, int quantity) throws Exception {
        String response = mockMvc.perform(post("/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "productId": %d, "quantity": %d } ] }
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
