package com.example.expense.dto.response;

import java.time.OffsetDateTime;

import com.example.expense.entity.Receipt;

public record ReceiptResponse(
        Long id,
        Long expenseId,
        String fileName,
        String contentType,
        long fileSize,
        OffsetDateTime createdAt,
        String url
) {
    /** メタデータのみ (アップロード/一覧用、URL なし)。 */
    public static ReceiptResponse of(Receipt r) {
        return new ReceiptResponse(r.getId(), r.getExpenseId(), r.getFileName(),
                r.getContentType(), r.getFileSize(), r.getCreatedAt(), null);
    }

    /** presigned URL 付き (取得用)。 */
    public static ReceiptResponse withUrl(Receipt r, String url) {
        return new ReceiptResponse(r.getId(), r.getExpenseId(), r.getFileName(),
                r.getContentType(), r.getFileSize(), r.getCreatedAt(), url);
    }
}
