package com.example.expense.config;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.example.expense.logging.RequestLoggingFilter;
import com.example.expense.ratelimit.RateLimitFilter;
import com.example.expense.ratelimit.ReceiptUploadRateLimitFilter;
import com.example.expense.ratelimit.RedisRateLimiter;
import com.example.expense.security.JwtAuthenticationFilter;
import com.example.expense.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;

@WebMvcTest(controllers = SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, SecurityConfigTest.TestBeans.class, SecurityConfigTest.TestController.class})
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilterRegistration;

    @Test
    void writesSecurityHeaders() throws Exception {
        mockMvc.perform(get("/actuator/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("includeSubDomains")))
                .andExpect(header().string("Permissions-Policy",
                        "geolocation=(), microphone=(), camera=()"));
    }

    @Test
    void protectsNonHealthActuatorPaths() throws Exception {
        mockMvc.perform(get("/actuator/env").secure(true))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestLoggingFilterIsNotRegisteredAsServletFilter() {
        org.assertj.core.api.Assertions.assertThat(requestLoggingFilterRegistration.isEnabled()).isFalse();
    }

    @RestController
    public static class TestController {
        @GetMapping("/actuator/health")
        Map<String, String> health() {
            return Map.of("status", "UP");
        }

        @GetMapping("/actuator/env")
        Map<String, String> env() {
            return Map.of("secret", "hidden");
        }
    }

    @TestConfiguration
    public static class TestBeans {
        @Bean
        RedisRateLimiter redisRateLimiter() {
            return mock(RedisRateLimiter.class);
        }

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return mock(JwtTokenProvider.class);
        }

        @Bean
        RateLimitFilter rateLimitFilter(RedisRateLimiter limiter) {
            return new RateLimitFilter(limiter);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
            return new JwtAuthenticationFilter(tokenProvider);
        }

        @Bean
        RequestLoggingFilter requestLoggingFilter() {
            return new RequestLoggingFilter();
        }

        @Bean
        ReceiptUploadRateLimitFilter receiptUploadRateLimitFilter(RedisRateLimiter limiter) {
            return new ReceiptUploadRateLimitFilter(limiter);
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new CorsConfig("").corsConfigurationSource();
        }
    }
}


