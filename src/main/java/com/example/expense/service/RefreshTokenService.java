package com.example.expense.service;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.example.expense.dto.response.AuthResponse;
import com.example.expense.entity.RefreshToken;
import com.example.expense.entity.User;
import com.example.expense.exception.UnauthorizedException;
import com.example.expense.repository.RefreshTokenRepository;
import com.example.expense.repository.UserRepository;
import com.example.expense.security.JwtTokenProvider;
import com.example.expense.security.RefreshReplayStore;
import com.example.expense.security.TokenHashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh Token の発行・ローテーション・失効・盗難検知。
 *
 * <p>ローテーション方式 + Redis リプレイキャッシュによる grace window 冪等再送。
 * 設計詳細: requirements.md 3.4 / architecture.md 6.1。
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshReplayStore replayStore;
    private final long refreshTtlSeconds;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               UserRepository userRepository,
                               JwtTokenProvider jwtTokenProvider,
                               RefreshReplayStore replayStore,
                               @Value("${app.auth.refresh-token-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.replayStore = replayStore;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /** ログイン時など: 新しい Access + Refresh ペアを発行する。 */
    @Transactional
    public AuthResponse issue(User user) {
        String plain = TokenHashing.generateOpaqueToken();
        persistNewToken(user.getId(), plain);
        return buildResponse(user, plain);
    }

    /**
     * Refresh Token をローテーションする。
     * <ol>
     *   <li>リプレイキャッシュにヒット → 同じ後継ペアを冪等に返す (grace window 内)</li>
     *   <li>有効なトークン → 旧を失効し新ペアを発行、リプレイキャッシュに保存</li>
     *   <li>失効済みの再提示 (grace 経過) → 盗難とみなしユーザー全トークン失効 → 401</li>
     * </ol>
     */
    @Transactional
    public AuthResponse rotate(String presentedPlain) {
        String hash = TokenHashing.sha256Hex(presentedPlain);

        // 1) grace window 内の冪等再送
        Optional<AuthResponse> replayed = replayStore.get(hash);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        // 2) FOR UPDATE で取得しローテーションを直列化
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (token.isRevoked()) {
            // 並行ローテーションの勝者がリプレイキャッシュを書いた可能性を再確認
            Optional<AuthResponse> raced = replayStore.get(hash);
            if (raced.isPresent()) {
                return raced.get();
            }
            // grace 経過後の失効済み再利用 → 盗難
            int revoked = refreshTokenRepository.revokeAllByUserId(token.getUserId(), OffsetDateTime.now());
            log.warn("Refresh token reuse detected for userId={}, revoked {} tokens", token.getUserId(), revoked);
            throw new UnauthorizedException("Refresh token reuse detected");
        }
        if (token.isExpired()) {
            throw new UnauthorizedException("Refresh token expired");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Unknown user"));

        // 新トークン発行 + 旧トークン失効 (replaced_by でチェーン記録)
        String newPlain = TokenHashing.generateOpaqueToken();
        RefreshToken newToken = persistNewToken(user.getId(), newPlain);
        token.revoke();
        token.setReplacedBy(newToken.getId());

        AuthResponse response = buildResponse(user, newPlain);
        replayStore.put(hash, response);
        return response;
    }

    /** ログアウト: 提示された Refresh Token を失効。permitAll。冪等。 */
    @Transactional
    public void revoke(String presentedPlain) {
        String hash = TokenHashing.sha256Hex(presentedPlain);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.revoke();
            }
        });
    }

    /** 指定ユーザーの有効な Refresh Token をすべて失効 (ロール変更・強制ログアウト時)。 */
    @Transactional
    public int revokeAllForUser(Long userId) {
        return refreshTokenRepository.revokeAllByUserId(userId, OffsetDateTime.now());
    }

    /** 期限切れトークンの物理削除 (日次バッチから呼ぶ)。 */
    @Transactional
    public int deleteExpired() {
        return refreshTokenRepository.deleteExpiredBefore(OffsetDateTime.now());
    }

    private RefreshToken persistNewToken(Long userId, String plain) {
        RefreshToken token = new RefreshToken(userId, TokenHashing.sha256Hex(plain),
                OffsetDateTime.now().plusSeconds(refreshTtlSeconds));
        return refreshTokenRepository.save(token);
    }

    private AuthResponse buildResponse(User user, String refreshPlain) {
        String access = jwtTokenProvider.generateAccessToken(user);
        return AuthResponse.bearer(access, refreshPlain, jwtTokenProvider.getAccessTtlSeconds(),
                user.getId(), user.getRole());
    }
}
