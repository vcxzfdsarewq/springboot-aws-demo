package com.example.expense.dto.response;

import com.example.expense.entity.Role;

/** ログイン / リフレッシュのレスポンス。 */
public record AuthResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        Long userId,
        Role role
) {
    public static AuthResponse bearer(String accessToken, String refreshToken,
                                      long expiresInSeconds, Long userId, Role role) {
        return new AuthResponse("Bearer", accessToken, refreshToken, expiresInSeconds, userId, role);
    }
}
