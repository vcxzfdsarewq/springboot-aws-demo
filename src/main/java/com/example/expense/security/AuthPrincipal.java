package com.example.expense.security;

import com.example.expense.entity.Role;

/** JWT から復元される認証済みユーザー。SecurityContext の principal として保持する。 */
public record AuthPrincipal(Long userId, String email, Role role) {
}
