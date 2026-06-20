package com.example.expense.dto.response;

import java.math.BigDecimal;

/** 月別経費集計レスポンス。 */
public record MonthlyReportResponse(
        int year,
        int month,
        long count,
        BigDecimal totalAmount
) {
}
