package com.example.expense.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS 設定。許可 origin は環境変数 {@code APP_CORS_ALLOWED_ORIGINS} (カンマ区切り) で注入する。
 * 既定は空 = クロスオリジンを許可しない (ブラウザクライアントを使う環境でのみ origin を設定)。
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins.isBlank()
                ? List.of()
                : Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Location", "Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
