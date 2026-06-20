package com.example.expense.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import com.example.expense.entity.Role;
import com.example.expense.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Access Token (JWT, HS256) の発行・検証。ステートレス (DBアクセスなし)。 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtTokenProvider(
            @Value("${app.auth.jwt-secret}") String secret,
            @Value("${app.auth.access-token-ttl-seconds:900}") long accessTtlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtlSeconds = accessTtlSeconds;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /** 署名・期限を検証して principal を復元する。不正なら null。 */
    public AuthPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get("email", String.class);
            Role role = Role.valueOf(claims.get("role", String.class));
            return new AuthPrincipal(userId, email, role);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }
}
