package com.example.expense.ratelimit;

import java.io.IOException;

import com.example.expense.security.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 領収書アップロードの Rate Limiting (userId 単位で 30回/時)。 */
@Component
public class ReceiptUploadRateLimitFilter extends OncePerRequestFilter {

    private static final int UPLOAD_LIMIT = 30;
    private static final int UPLOAD_WINDOW_SECONDS = 3600;
    private static final String RECEIPT_UPLOAD_PATH = "^/api/expenses/[^/]+/receipts/?$";

    private final RedisRateLimiter limiter;

    public ReceiptUploadRateLimitFilter(RedisRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isReceiptUpload(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        RedisRateLimiter.Result result;
        try {
            result = limiter.tryAcquire("rl:receipt-upload:" + principal.userId(),
                    UPLOAD_LIMIT, UPLOAD_WINDOW_SECONDS);
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

        filterChain.doFilter(request, response);
    }

    private boolean isReceiptUpload(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().matches(RECEIPT_UPLOAD_PATH);
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
}
