package com.example.expense.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.expense.entity.Role;
import com.example.expense.entity.User;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-32+";

    private User user(long id, String email, Role role) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getEmail()).thenReturn(email);
        when(u.getRole()).thenReturn(role);
        return u;
    }

    @Test
    void generateAndParseRoundTrip() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 900);
        String token = provider.generateAccessToken(user(42L, "a@b.com", Role.ADMIN));

        AuthPrincipal principal = provider.parse(token);

        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.email()).isEqualTo("a@b.com");
        assertThat(principal.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 900);
        String token = provider.generateAccessToken(user(1L, "x@y.com", Role.USER));

        assertThat(provider.parse(token + "tampered")).isNull();
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        String token = new JwtTokenProvider(SECRET, 900)
                .generateAccessToken(user(1L, "x@y.com", Role.USER));
        JwtTokenProvider other = new JwtTokenProvider("another-secret-key-also-long-enough-32+", 900);

        assertThat(other.parse(token)).isNull();
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 0); // 即時失効
        String token = provider.generateAccessToken(user(1L, "x@y.com", Role.USER));
        Thread.sleep(1000);

        assertThat(provider.parse(token)).isNull();
    }

    @Test
    void shortSecretIsRejected() {
        try {
            new JwtTokenProvider("too-short", 900);
            assertThat(false).as("should have thrown").isTrue();
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("32 bytes");
        }
    }
}
