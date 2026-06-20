package com.example.expense.dto.response;

import java.math.BigDecimal;

/** カテゴリ別経費集計レスポンス。 */
public record CategoryReportResponse(
        String category,
        long count,
        BigDecimal totalAmount
) {
}
