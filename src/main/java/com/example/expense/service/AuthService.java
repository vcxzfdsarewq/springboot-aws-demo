package com.example.expense.service;

import java.util.Locale;

import com.example.expense.dto.request.LoginRequest;
import com.example.expense.dto.request.SignupRequest;
import com.example.expense.dto.response.AuthResponse;
import com.example.expense.entity.Role;
import com.example.expense.entity.User;
import com.example.expense.exception.DuplicateResourceException;
import com.example.expense.exception.ServiceUnavailableException;
import com.example.expense.exception.TooManyRequestsException;
import com.example.expense.exception.UnauthorizedException;
import com.example.expense.ratelimit.RedisRateLimiter;
import com.example.expense.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** サインアップ・ログイン。 */
@Service
public class AuthService {

    // 要件: IP + email 単位で「失敗時」5回 / 5分。成功でリセット。
    private static final int LOGIN_FAIL_LIMIT = 5;
    private static final int LOGIN_WINDOW_SECONDS = 300;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final RedisRateLimiter rateLimiter;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       RefreshTokenService refreshTokenService, RedisRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.rateLimiter = rateLimiter;
    }

    /** 登録。ロールは常に USER を強制 (リクエストの role 指定は受け付けない)。 */
    @Transactional
    public User signup(SignupRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new DuplicateResourceException("Email already registered");
        }
        User user = new User(req.email(), passwordEncoder.encode(req.password()),
                req.name(), Role.USER);
        return userRepository.save(user);
    }

    /**
     * ログイン。資格情報が不正なら 401 (ユーザー有無は秘匿)。
     * IP + email 単位で失敗回数を数え、上限超過は 429。Redis 障害時は fail-closed (503)。
     */
    @Transactional
    public AuthResponse login(LoginRequest req, String clientIp) {
        String key = "rl:login:" + clientIp + ":" + req.email().toLowerCase(Locale.ROOT);

        // 事前チェック (失敗カウントの参照)。Redis 不通なら認証を通さず 503。
        long fails;
        try {
            fails = rateLimiter.currentCount(key);
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException("Authentication temporarily unavailable");
        }
        if (fails >= LOGIN_FAIL_LIMIT) {
            throw new TooManyRequestsException("Too many failed login attempts",
                    rateLimiter.ttlSeconds(key));
        }

        User user = userRepository.findByEmail(req.email()).orElse(null);
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // 失敗時のみ加算 (best-effort: 記録失敗は致命的でない)
            try {
                rateLimiter.increment(key, LOGIN_WINDOW_SECONDS);
            } catch (DataAccessException ignored) {
                // noop
            }
            throw new UnauthorizedException("Invalid email or password");
        }

        // 成功でカウンタをクリア
        try {
            rateLimiter.reset(key);
        } catch (DataAccessException ignored) {
            // noop
        }
        return refreshTokenService.issue(user);
    }
}
