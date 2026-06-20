package com.example.expense.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import com.example.expense.dto.request.ExpenseRequest;
import com.example.expense.dto.response.CategoryReportResponse;
import com.example.expense.dto.response.MonthlyReportResponse;
import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import com.example.expense.exception.BadRequestException;
import com.example.expense.exception.InvalidStateTransitionException;
import com.example.expense.exception.ResourceNotFoundException;
import com.example.expense.repository.ExpenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 経費のビジネスロジック。
 * ステータス状態機械の遷移をここで強制する ({@link ExpenseStatus})。
 * 一般ユーザー操作は所有者 (userId) スコープで行い、管理者操作は ADMIN 専用 controller から呼ぶ。
 */
@Service
@Transactional
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private final ExpenseRepository expenseRepository;

    public ExpenseService(ExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    @Transactional(readOnly = true)
    public Page<Expense> list(Long userId, Pageable pageable) {
        return expenseRepository.findByUserId(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Expense> listForAdmin(ExpenseStatus status, Pageable pageable) {
        return status == null ? expenseRepository.findAll(pageable)
                : expenseRepository.findByStatus(status, pageable);
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
        log.info("AUDIT expense-approve: adminId={} expenseId={} ownerId={}",
                reviewerId, id, expense.getUserId());
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
        log.info("AUDIT expense-reject: adminId={} expenseId={} ownerId={}",
                reviewerId, id, expense.getUserId());
        return expense;
    }

    @Transactional(readOnly = true)
    public MonthlyReportResponse monthlyReport(int year, int month, Long userId, String statusParam) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.plusMonths(1);
        ExpenseStatus status = parseReportStatus(statusParam);
        ExpenseRepository.MonthlyExpenseAggregate aggregate =
                expenseRepository.aggregateMonthly(from, to, userId, status);
        return new MonthlyReportResponse(year, month, aggregate.getExpenseCount(),
                zeroIfNull(aggregate.getTotalAmount()));
    }

    @Transactional(readOnly = true)
    public List<CategoryReportResponse> categoryReport(LocalDate from, LocalDate to, Long userId, String statusParam) {
        if (from.isAfter(to)) {
            throw new BadRequestException("from must be on or before to");
        }
        ExpenseStatus status = parseReportStatus(statusParam);
        return expenseRepository.aggregateByCategory(from, to, userId, status).stream()
                .map(row -> new CategoryReportResponse(row.getCategory(), row.getExpenseCount(),
                        zeroIfNull(row.getTotalAmount())))
                .toList();
    }

    private Expense requireOwned(Long userId, Long id) {
        return expenseRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ResourceNotFoundException.expense(id));
    }

    private ExpenseStatus parseReportStatus(String value) {
        if (value == null || value.isBlank() || "APPROVED".equalsIgnoreCase(value)) {
            return ExpenseStatus.APPROVED;
        }
        if ("ALL".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return ExpenseStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid status: " + value);
        }
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void requireTransition(ExpenseStatus from, ExpenseStatus to, String action) {
        if (!from.canTransitionTo(to)) {
            throw InvalidStateTransitionException.of(from, action);
        }
    }
}

