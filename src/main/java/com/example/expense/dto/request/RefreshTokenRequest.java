package com.example.expense.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(

        @NotBlank
        String refreshToken
) {
}
