package com.example.expense.ratelimit;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.expense.entity.Role;
import com.example.expense.security.AuthPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ReceiptUploadRateLimitFilterTest {

    @Mock private RedisRateLimiter limiter;
    @Mock private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void appliesUserScopedLimitToReceiptUploads() throws Exception {
        ReceiptUploadRateLimitFilter filter = new ReceiptUploadRateLimitFilter(limiter);
        authenticate(2L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/expenses/10/receipts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(limiter.tryAcquire("rl:receipt-upload:2", 30, 3600))
                .thenReturn(new RedisRateLimiter.Result(true, 3600));

        filter.doFilter(request, response, filterChain);

        verify(limiter).tryAcquire("rl:receipt-upload:2", 30, 3600);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void blocksReceiptUploadsWhenLimitExceeded() throws Exception {
        ReceiptUploadRateLimitFilter filter = new ReceiptUploadRateLimitFilter(limiter);
        authenticate(2L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/expenses/10/receipts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(limiter.tryAcquire("rl:receipt-upload:2", 30, 3600))
                .thenReturn(new RedisRateLimiter.Result(false, 120));

        filter.doFilter(request, response, filterChain);

        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(429);
        org.assertj.core.api.Assertions.assertThat(response.getHeader("Retry-After")).isEqualTo("120");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void skipsNonUploadRequests() throws Exception {
        ReceiptUploadRateLimitFilter filter = new ReceiptUploadRateLimitFilter(limiter);
        authenticate(2L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/expenses/10/receipts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(limiter, never()).tryAcquire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(filterChain).doFilter(request, response);
    }

    private void authenticate(Long userId) {
        AuthPrincipal principal = new AuthPrincipal(userId, "u@example.com", Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }
}
