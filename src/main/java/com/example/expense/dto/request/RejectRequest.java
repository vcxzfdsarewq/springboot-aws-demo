package com.example.expense.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 経費却下リクエスト (管理者)。 */
public record RejectRequest(

        @NotBlank
        @Size(max = 1000)
        String reason
) {
}
