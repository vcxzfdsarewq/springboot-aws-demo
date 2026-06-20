package com.example.expense.controller;

import java.net.URI;

import com.example.expense.dto.request.ExpenseRequest;
import com.example.expense.dto.response.ExpenseResponse;
import com.example.expense.dto.response.PagedResponse;
import com.example.expense.entity.Expense;
import com.example.expense.service.ExpenseService;
import com.example.expense.web.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一般ユーザー向け経費 API。操作ユーザーは JWT (SecurityContext) から解決する。
 */
@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;
    private final CurrentUserProvider currentUser;

    public ExpenseController(ExpenseService expenseService, CurrentUserProvider currentUser) {
        this.expenseService = expenseService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public PagedResponse<ExpenseResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        Page<ExpenseResponse> page = expenseService.list(currentUser.requireId(), pageable)
                .map(ExpenseResponse::from);
        return PagedResponse.from(page);
    }

    @GetMapping("/{id}")
    public ExpenseResponse get(@PathVariable Long id) {
        return ExpenseResponse.from(expenseService.get(currentUser.requireId(), id));
    }

    @PostMapping
    public ResponseEntity<ExpenseResponse> create(@Valid @RequestBody ExpenseRequest req) {
        Expense created = expenseService.create(currentUser.requireId(), req);
        return ResponseEntity
                .created(URI.create("/api/expenses/" + created.getId()))
                .body(ExpenseResponse.from(created));
    }

    @PutMapping("/{id}")
    public ExpenseResponse update(@PathVariable Long id, @Valid @RequestBody ExpenseRequest req) {
        return ExpenseResponse.from(expenseService.update(currentUser.requireId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        expenseService.delete(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/submit")
    public ExpenseResponse submit(@PathVariable Long id) {
        return ExpenseResponse.from(expenseService.submit(currentUser.requireId(), id));
    }

    @PostMapping("/{id}/withdraw")
    public ExpenseResponse withdraw(@PathVariable Long id) {
        return ExpenseResponse.from(expenseService.withdraw(currentUser.requireId(), id));
    }
}
