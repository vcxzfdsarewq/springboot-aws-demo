package com.example.expense.service;

import java.time.OffsetDateTime;

import com.example.expense.dto.request.ExpenseRequest;
import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import com.example.expense.exception.InvalidStateTransitionException;
import com.example.expense.exception.ResourceNotFoundException;
import com.example.expense.repository.ExpenseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 経費のビジネスロジック。
 * ステータス状態機械の遷移をここで強制する ({@link ExpenseStatus})。
 * 全操作は所有者 (userId) スコープで行う。
 */
@Service
@Transactional
public class ExpenseService {

    private final ExpenseRepository expenseRepository;

    public ExpenseService(ExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    @Transactional(readOnly = true)
    public Page<Expense> list(Long userId, Pageable pageable) {
        return expenseRepository.findByUserId(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Expense get(Long userId, Long id) {
        return requireOwned(userId, id);
    }

    public Expense create(Long userId, ExpenseRequest req) {
        Expense expense = new Expense(userId, req.title(), req.description(), req.amount(),
                req.category(), req.expenseDate());
        return expenseRepository.save(expense);
    }

    public Expense update(Long userId, Long id, ExpenseRequest req) {
        Expense expense = requireOwned(userId, id);
        if (!expense.getStatus().isEditableByOwner()) {
            throw InvalidStateTransitionException.of(expense.getStatus(), "update");
        }
        expense.setTitle(req.title());
        expense.setDescription(req.description());
        expense.setAmount(req.amount());
        expense.setCategory(req.category());
        expense.setExpenseDate(req.expenseDate());
        return expense;
    }

    public void delete(Long userId, Long id) {
        Expense expense = requireOwned(userId, id);
        if (!expense.getStatus().isDeletableByOwner()) {
            throw InvalidStateTransitionException.of(expense.getStatus(), "delete");
        }
        expenseRepository.delete(expense);
    }

    /** 申請: DRAFT または REJECTED → PENDING (再申請時は却下情報をクリア)。 */
    public Expense submit(Long userId, Long id) {
        Expense expense = requireOwned(userId, id);
        requireTransition(expense.getStatus(), ExpenseStatus.PENDING, "submit");
        expense.setRejectReason(null);
        expense.setReviewerId(null);
        expense.setReviewedAt(null);
        expense.setStatus(ExpenseStatus.PENDING);
        return expense;
    }

    /** 取り下げ: PENDING → DRAFT。 */
    public Expense withdraw(Long userId, Long id) {
        Expense expense = requireOwned(userId, id);
        requireTransition(expense.getStatus(), ExpenseStatus.DRAFT, "withdraw");
        expense.setStatus(ExpenseStatus.DRAFT);
        return expense;
    }

    /** 承認 (管理者): PENDING → APPROVED。楽観ロックで同時実行を防ぐ。 */
    public Expense approve(Long reviewerId, Long id) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.expense(id));
        requireTransition(expense.getStatus(), ExpenseStatus.APPROVED, "approve");
        expense.setStatus(ExpenseStatus.APPROVED);
        expense.setReviewerId(reviewerId);
        expense.setReviewedAt(OffsetDateTime.now());
        return expense;
    }

    /** 却下 (管理者): PENDING → REJECTED。 */
    public Expense reject(Long reviewerId, Long id, String reason) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.expense(id));
        requireTransition(expense.getStatus(), ExpenseStatus.REJECTED, "reject");
        expense.setStatus(ExpenseStatus.REJECTED);
        expense.setReviewerId(reviewerId);
        expense.setReviewedAt(OffsetDateTime.now());
        expense.setRejectReason(reason);
        return expense;
    }

    private Expense requireOwned(Long userId, Long id) {
        return expenseRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ResourceNotFoundException.expense(id));
    }

    private void requireTransition(ExpenseStatus from, ExpenseStatus to, String action) {
        if (!from.canTransitionTo(to)) {
            throw InvalidStateTransitionException.of(from, action);
        }
    }
}
