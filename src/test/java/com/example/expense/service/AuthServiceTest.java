package com.example.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.example.expense.dto.request.LoginRequest;
import com.example.expense.dto.request.SignupRequest;
import com.example.expense.entity.Role;
import com.example.expense.entity.User;
import com.example.expense.exception.DuplicateResourceException;
import com.example.expense.exception.TooManyRequestsException;
import com.example.expense.exception.UnauthorizedException;
import com.example.expense.ratelimit.RedisRateLimiter;
import com.example.expense.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String IP = "1.2.3.4";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private RedisRateLimiter rateLimiter;

    private AuthService authService() {
        return new AuthService(userRepository, passwordEncoder, refreshTokenService, rateLimiter);
    }

    @Test
    void signupForcesUserRoleAndHashesPassword() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        authService().signup(new SignupRequest("a@b.com", "Password123!", "Alice"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

        assertThatThrownBy(() -> authService()
                .signup(new SignupRequest("a@b.com", "Password123!", "Alice")))
                .isInstanceOf(DuplicateResourceException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginWithBadPasswordIncrementsFailCounterAndIs401() {
        User user = new User("a@b.com", "hashed", "Alice", Role.USER);
        when(rateLimiter.currentCount(anyString())).thenReturn(0L);
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService().login(new LoginRequest("a@b.com", "wrong"), IP))
                .isInstanceOf(UnauthorizedException.class);

        verify(rateLimiter).increment(anyString(), anyInt());   // 失敗時のみ加算
        verify(refreshTokenService, never()).issue(any());
    }

    @Test
    void loginSuccessResetsFailCounter() {
        User user = new User("a@b.com", "hashed", "Alice", Role.USER);
        when(rateLimiter.currentCount(anyString())).thenReturn(2L);
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true);

        authService().login(new LoginRequest("a@b.com", "Password123!"), IP);

        verify(rateLimiter).reset(anyString());                 // 成功でリセット
        verify(rateLimiter, never()).increment(anyString(), anyInt());
        verify(refreshTokenService).issue(user);
    }

    @Test
    void loginIsBlockedWhenFailLimitReached() {
        when(rateLimiter.currentCount(anyString())).thenReturn(5L);   // 上限到達

        assertThatThrownBy(() -> authService().login(new LoginRequest("a@b.com", "x"), IP))
                .isInstanceOf(TooManyRequestsException.class);

        verify(userRepository, never()).findByEmail(anyString());     // 認証処理に進まない
    }

    @Test
    void loginWithUnknownEmailIsUnauthorized() {
        when(rateLimiter.currentCount(anyString())).thenReturn(0L);
        when(userRepository.findByEmail("none@b.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService().login(new LoginRequest("none@b.com", "x"), IP))
                .isInstanceOf(UnauthorizedException.class);
        verify(rateLimiter).increment(anyString(), anyInt());
    }
}
