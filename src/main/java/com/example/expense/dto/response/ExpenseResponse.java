package com.example.expense.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;

/** 経費レスポンス。 */
public record ExpenseResponse(
        Long id,
        Long userId,
        String title,
        String description,
        BigDecimal amount,
        String category,
        LocalDate expenseDate,
        ExpenseStatus status,
        Long reviewerId,
        OffsetDateTime reviewedAt,
        String rejectReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ExpenseResponse from(Expense e) {
        return new ExpenseResponse(
                e.getId(),
                e.getUserId(),
                e.getTitle(),
                e.getDescription(),
                e.getAmount(),
                e.getCategory(),
                e.getExpenseDate(),
                e.getStatus(),
                e.getReviewerId(),
                e.getReviewedAt(),
                e.getRejectReason(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
