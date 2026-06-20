package com.example.expense.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.expense.dto.response.CategoryReportResponse;
import com.example.expense.dto.response.MonthlyReportResponse;
import com.example.expense.service.ExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminReportControllerTest {

    @Mock private ExpenseService expenseService;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminReportController(expenseService)).build();
    }

    @Test
    void monthlyPassesFiltersToService() throws Exception {
        when(expenseService.monthlyReport(2026, 6, 2L, "APPROVED"))
                .thenReturn(new MonthlyReportResponse(2026, 6, 3L, new BigDecimal("4500.00")));

        mockMvc.perform(get("/api/admin/reports/monthly")
                        .param("year", "2026")
                        .param("month", "6")
                        .param("userId", "2")
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(6))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.totalAmount").value(4500.00));

        verify(expenseService).monthlyReport(2026, 6, 2L, "APPROVED");
    }

    @Test
    void byCategoryPassesUserIdAndStatusToService() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        when(expenseService.categoryReport(from, to, 2L, "ALL"))
                .thenReturn(List.of(new CategoryReportResponse("交通費", 2L, new BigDecimal("5000.00"))));

        mockMvc.perform(get("/api/admin/reports/by-category")
                        .param("from", "2026-01-01")
                        .param("to", "2026-06-30")
                        .param("userId", "2")
                        .param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("交通費"))
                .andExpect(jsonPath("$[0].count").value(2))
                .andExpect(jsonPath("$[0].totalAmount").value(5000.00));

        verify(expenseService).categoryReport(from, to, 2L, "ALL");
    }
}
