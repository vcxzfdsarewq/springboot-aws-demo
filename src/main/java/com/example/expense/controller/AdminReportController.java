package com.example.expense.controller;

import java.time.LocalDate;
import java.util.List;

import com.example.expense.dto.response.CategoryReportResponse;
import com.example.expense.dto.response.MonthlyReportResponse;
import com.example.expense.service.ExpenseService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理者向けレポート API。 */
@RestController
@RequestMapping("/api/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminReportController {

    private final ExpenseService expenseService;

    public AdminReportController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @GetMapping("/monthly")
    public MonthlyReportResponse monthly(@RequestParam @Min(1970) int year,
                                         @RequestParam @Min(1) @Max(12) int month,
                                         @RequestParam(required = false) Long userId,
                                         @RequestParam(defaultValue = "APPROVED") String status) {
        return expenseService.monthlyReport(year, month, userId, status);
    }

    @GetMapping("/by-category")
    public List<CategoryReportResponse> byCategory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "APPROVED") String status) {
        return expenseService.categoryReport(from, to, userId, status);
    }
}

