package com.example.expense.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import com.example.expense.service.ExpenseService;
import com.example.expense.web.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminExpenseControllerTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long EXPENSE_ID = 10L;

    @Mock private ExpenseService expenseService;
    @Mock private CurrentUserProvider currentUser;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AdminExpenseController(expenseService, currentUser))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void listDefaultsToPendingStatus() throws Exception {
        when(expenseService.listForAdmin(eq(ExpenseStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(expense(ExpenseStatus.PENDING))));

        mockMvc.perform(get("/api/admin/expenses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(expenseService).listForAdmin(eq(ExpenseStatus.PENDING), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void approveUsesCurrentAdminId() throws Exception {
        when(currentUser.requireId()).thenReturn(ADMIN_ID);
        when(expenseService.approve(ADMIN_ID, EXPENSE_ID)).thenReturn(expense(ExpenseStatus.APPROVED));

        mockMvc.perform(post("/api/admin/expenses/{id}/approve", EXPENSE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        verify(expenseService).approve(ADMIN_ID, EXPENSE_ID);
    }

    @Test
    void rejectPassesReasonAndCurrentAdminId() throws Exception {
        when(currentUser.requireId()).thenReturn(ADMIN_ID);
        Expense rejected = expense(ExpenseStatus.REJECTED);
        rejected.setRejectReason("missing receipt");
        when(expenseService.reject(ADMIN_ID, EXPENSE_ID, "missing receipt")).thenReturn(rejected);

        mockMvc.perform(post("/api/admin/expenses/{id}/reject", EXPENSE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "reason", "missing receipt"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("missing receipt"));

        verify(expenseService).reject(ADMIN_ID, EXPENSE_ID, "missing receipt");
    }

    private Expense expense(ExpenseStatus status) {
        Expense expense = new Expense(2L, "Taxi", "client visit",
                new BigDecimal("1200.00"), "交通費", LocalDate.of(2026, 6, 1));
        expense.setStatus(status);
        return expense;
    }
}

