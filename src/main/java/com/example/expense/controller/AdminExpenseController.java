package com.example.expense.controller;

import com.example.expense.dto.request.RejectRequest;
import com.example.expense.dto.response.ExpenseResponse;
import com.example.expense.dto.response.PagedResponse;
import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import com.example.expense.service.ExpenseService;
import com.example.expense.web.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理者向け経費承認 API。 */
@RestController
@RequestMapping("/api/admin/expenses")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExpenseController {

    private final ExpenseService expenseService;
    private final CurrentUserProvider currentUser;

    public AdminExpenseController(ExpenseService expenseService, CurrentUserProvider currentUser) {
        this.expenseService = expenseService;
        this.currentUser = currentUser;
    }

    /** 申請一覧。status 未指定なら PENDING。 */
    @GetMapping
    public PagedResponse<ExpenseResponse> list(@RequestParam(defaultValue = "PENDING") ExpenseStatus status,
                                               @PageableDefault(size = 20) Pageable pageable) {
        Page<ExpenseResponse> page = expenseService.listForAdmin(status, pageable)
                .map(ExpenseResponse::from);
        return PagedResponse.from(page);
    }

    /** 承認: PENDING → APPROVED。 */
    @PostMapping("/{id}/approve")
    public ExpenseResponse approve(@PathVariable Long id) {
        Expense expense = expenseService.approve(currentUser.requireId(), id);
        return ExpenseResponse.from(expense);
    }

    /** 却下: PENDING → REJECTED。 */
    @PostMapping("/{id}/reject")
    public ExpenseResponse reject(@PathVariable Long id, @Valid @RequestBody RejectRequest req) {
        Expense expense = expenseService.reject(currentUser.requireId(), id, req.reason());
        return ExpenseResponse.from(expense);
    }
}

