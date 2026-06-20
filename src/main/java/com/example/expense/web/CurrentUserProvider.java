package com.example.expense.web;

import com.example.expense.exception.UnauthorizedException;
import com.example.expense.security.AuthPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 現在の認証済みユーザーを SecurityContext から解決する。
 * (Phase 1 の X-User-Id ヘッダ方式は Phase 3 で JWT + SecurityContext に置き換えた)
 */
@Component
public class CurrentUserProvider {

    public AuthPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new UnauthorizedException("Authentication required");
        }
        return principal;
    }

    public Long requireId() {
        return require().userId();
    }
}
