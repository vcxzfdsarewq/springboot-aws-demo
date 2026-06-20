package com.example.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import com.example.expense.repository.ExpenseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 経費 API の統合テスト (PostgreSQL Testcontainer + Flyway)。
 * Docker が無い環境では自動的にスキップされる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")  // db/seed の開発用ユーザー (id=1 ADMIN, id=2 USER) に依存
@Testcontainers(disabledWithoutDocker = true)
class ExpenseApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String USER = "2";   // V2 seed の Demo User
    private static final String ADMIN = "1";  // V2 seed の Initial Admin

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExpenseRepository expenseRepository;

    @BeforeEach
    void cleanExpenses() {
        expenseRepository.deleteAll();
    }

    private String expenseJson(String title, String amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "title", title,
                "description", "integration test",
                "amount", amount,
                "category", "交通費",
                "expenseDate", LocalDate.now().toString()
        ));
    }

    @Test
    void requiresUserHeader() throws Exception {
        mockMvc.perform(get("/api/expenses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_submit_thenBlocksEditingWhilePending() throws Exception {
        // 登録 -> 201, DRAFT
        String location = mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", USER)
                        .contentType("application/json")
                        .content(expenseJson("Taxi", "1200.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn().getResponse().getHeader("Location");

        Long id = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));

        // 一覧に出る
        mockMvc.perform(get("/api/expenses").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // 申請 -> PENDING
        mockMvc.perform(post("/api/expenses/{id}/submit", id).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // PENDING 中の更新は 409 (状態機械で編集をブロック)
        mockMvc.perform(put("/api/expenses/{id}", id)
                        .header("X-User-Id", USER)
                        .contentType("application/json")
                        .content(expenseJson("Taxi-edited", "1500.00")))
                .andExpect(status().isConflict());

        // この時点ではまだ PENDING のまま (承認エンドポイントは Phase 5)
        Expense pending = expenseRepository.findById(id).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(ExpenseStatus.PENDING);
    }

    @Test
    void cannotAccessOthersExpense() throws Exception {
        String location = mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", USER)
                        .contentType("application/json")
                        .content(expenseJson("Mine", "500.00")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        Long id = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));

        // 別ユーザー(ADMINユーザーid)からは所有者スコープ外 -> 404
        mockMvc.perform(get("/api/expenses/{id}", id).header("X-User-Id", ADMIN))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationRejectsBadInput() throws Exception {
        String bad = objectMapper.writeValueAsString(Map.of(
                "title", "",
                "amount", "-5",
                "category", "",
                "expenseDate", LocalDate.now().plusDays(1).toString()
        ));
        mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", USER)
                        .contentType("application/json")
                        .content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", USER)
                        .contentType("application/json")
                        .content("{bad json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optimisticLockingPreventsConcurrentUpdate() {
        Expense saved = expenseRepository.save(new Expense(2L, "Race", "x",
                new BigDecimal("100.00"), "交通費", LocalDate.now()));
        Long id = saved.getId();

        // 2つのコピーを別トランザクションで取得 (どちらも version=0)
        Expense a = expenseRepository.findById(id).orElseThrow();
        Expense b = expenseRepository.findById(id).orElseThrow();

        a.setTitle("updated-by-A");
        expenseRepository.saveAndFlush(a); // version 0 -> 1

        b.setTitle("updated-by-B");
        assertThatThrownBy(() -> expenseRepository.saveAndFlush(b))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
