package com.example.expense.ratelimit;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * リフレッシュ系の Rate Limiting (Redis 共有ストア)。
 *
 * <p>refresh は IP 単位の全試行カウント (60回/時)。
 * login はボディの email が必要かつ「失敗時のみ」加算するため、
 * フィルタではなく {@code AuthService} 側で IP+email キーで制御する。
 *
 * <p>Redis 不通時は fail-closed (503) とする (認証系は Redis を必須依存とみなす)。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int REFRESH_LIMIT = 60;     // 60回 / 時
    private static final int REFRESH_WINDOW = 3600;

    private final RedisRateLimiter limiter;

    public RateLimitFilter(RedisRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isRefresh(request)) {
            String ip = clientIp(request);
            RedisRateLimiter.Result result;
            try {
                result = limiter.tryAcquire("rl:refresh:" + ip, REFRESH_LIMIT, REFRESH_WINDOW);
            } catch (DataAccessException e) {
                writeError(response, HttpStatus.SERVICE_UNAVAILABLE,
                        "Rate limiter unavailable", 0);
                return;
            }
            if (!result.allowed()) {
                writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                        "Rate limit exceeded", result.retryAfterSeconds());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isRefresh(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().endsWith("/api/auth/refresh");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message,
                            long retryAfter) throws IOException {
        response.setStatus(status.value());
        if (retryAfter > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfter));
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(String.format(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}",
                status.value(), status.getReasonPhrase(), message));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
