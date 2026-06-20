package com.example.expense.web;

import com.example.expense.entity.User;
import com.example.expense.exception.UnauthorizedException;
import com.example.expense.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 現在の操作ユーザーを解決する。
 *
 * <p>Phase 1 の暫定実装: 認証 (Spring Security) は Phase 3 で導入するため、
 * リクエストヘッダ {@code X-User-Id} から操作ユーザーを解決する。
 * Phase 3 で SecurityContext ベースの実装に置き換える。
 */
@Component
public class CurrentUserProvider {

    static final String USER_ID_HEADER = "X-User-Id";

    private final UserRepository userRepository;

    public CurrentUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User require(HttpServletRequest request) {
        String header = request.getHeader(USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            throw new UnauthorizedException(
                    "Missing " + USER_ID_HEADER + " header (Phase 1 placeholder for authentication)");
        }
        long userId;
        try {
            userId = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("Invalid " + USER_ID_HEADER + " header");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Unknown user: " + userId));
    }

    public Long requireId(HttpServletRequest request) {
        return require(request).getId();
    }
}
