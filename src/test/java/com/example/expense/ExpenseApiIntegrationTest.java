package com.example.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import com.example.expense.repository.ExpenseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 経費 API の統合テスト (PostgreSQL + Redis Testcontainer + Flyway + JWT 認証)。
 * Docker が無い環境では自動的にスキップされる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")  // db/seed の開発用ユーザー (admin@example.com / user@example.com) に依存
@Testcontainers(disabledWithoutDocker = true)
class ExpenseApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    private static final String SEED_PASSWORD = "Password123!";
    private static final Map<String, String> TOKEN_CACHE = new ConcurrentHashMap<>();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExpenseRepository expenseRepository;

    @BeforeEach
    void cleanExpenses() {
        expenseRepository.deleteAll();
    }

    /** seed ユーザーでログインし "Bearer <accessToken>" を返す (Rate Limit 回避のためキャッシュ)。 */
    private String bearer(String email) {
        return TOKEN_CACHE.computeIfAbsent(email, e -> {
            try {
                String body = objectMapper.writeValueAsString(Map.of("email", e, "password", SEED_PASSWORD));
                String json = mockMvc.perform(post("/api/auth/login")
                                .contentType("application/json").content(body))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                JsonNode node = objectMapper.readTree(json);
                return "Bearer " + node.get("accessToken").asText();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private String userBearer() {
        return bearer("user@example.com");
    }

    private String adminBearer() {
        return bearer("admin@example.com");
    }

    private String expenseJson(String title, String amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "title", title,
                "description", "integration test",
                "amount", amount,
                "category", "交通費",
                "expenseDate", LocalDate.now().toString()));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/expenses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_submit_thenBlocksEditingWhilePending() throws Exception {
        String token = userBearer();
        String location = mockMvc.perform(post("/api/expenses")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(expenseJson("Taxi", "1200.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getHeader("Location");
        Long id = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(get("/api/expenses").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post("/api/expenses/{id}/submit", id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(put("/api/expenses/{id}", id)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(expenseJson("Taxi-edited", "1500.00")))
                .andExpect(status().isConflict());

        Expense pending = expenseRepository.findById(id).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(ExpenseStatus.PENDING);
    }

    @Test
    void cannotAccessOthersExpense() throws Exception {
        String location = mockMvc.perform(post("/api/expenses")
                        .header("Authorization", userBearer())
                        .contentType("application/json")
                        .content(expenseJson("Mine", "500.00")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        Long id = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));

        // 別ユーザー(admin)からは所有者スコープ外 -> 404
        mockMvc.perform(get("/api/expenses/{id}", id).header("Authorization", adminBearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationRejectsBadInput() throws Exception {
        String bad = objectMapper.writeValueAsString(Map.of(
                "title", "", "amount", "-5", "category", "",
                "expenseDate", LocalDate.now().plusDays(1).toString()));
        mockMvc.perform(post("/api/expenses")
                        .header("Authorization", userBearer())
                        .contentType("application/json").content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .header("Authorization", userBearer())
                        .contentType("application/json").content("{bad json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optimisticLockingPreventsConcurrentUpdate() {
        Expense saved = expenseRepository.save(new Expense(2L, "Race", "x",
                new BigDecimal("100.00"), "交通費", LocalDate.now()));
        Long id = saved.getId();

        Expense a = expenseRepository.findById(id).orElseThrow();
        Expense b = expenseRepository.findById(id).orElseThrow();

        a.setTitle("updated-by-A");
        expenseRepository.saveAndFlush(a);

        b.setTitle("updated-by-B");
        assertThatThrownBy(() -> expenseRepository.saveAndFlush(b))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
