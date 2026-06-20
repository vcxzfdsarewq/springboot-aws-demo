package com.example.expense.security;

import java.time.Duration;
import java.util.Optional;

import com.example.expense.dto.response.AuthResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refresh Token ローテーションの冪等リプレイキャッシュ (Redis)。
 *
 * <p>ローテーション成功時に「旧トークンのハッシュ → 新しい Access+Refresh ペア (平文)」を
 * TTL 10秒で保持する。grace window 内の正規リトライ (二重送信) を盗難と誤判定せず、
 * 同じ後継ペアを冪等に返すために使う。平文は Redis 上に短命に保持し DB には残さない。
 */
@Component
public class RefreshReplayStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshReplayStore.class);
    private static final String KEY_PREFIX = "replay:refresh:";
    private static final Duration GRACE = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RefreshReplayStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 後継ペアを保持する。リプレイキャッシュは冪等再送のための best-effort。
     * Redis 障害時もローテーション自体は継続させるため、ここでの失敗は握り潰す (fail-open)。
     */
    public void put(String oldTokenHash, AuthResponse response) {
        try {
            redis.opsForValue().set(KEY_PREFIX + oldTokenHash,
                    objectMapper.writeValueAsString(response), GRACE);
        } catch (JsonProcessingException | DataAccessException e) {
            log.warn("Failed to write refresh replay cache (degraded idempotency): {}", e.toString());
        }
    }

    /** Redis 障害・JSON 不整合時は「ミス」として扱う (fail-open)。 */
    public Optional<AuthResponse> get(String oldTokenHash) {
        String json;
        try {
            json = redis.opsForValue().get(KEY_PREFIX + oldTokenHash);
        } catch (DataAccessException e) {
            log.warn("Failed to read refresh replay cache (degraded idempotency): {}", e.toString());
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, AuthResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse refresh replay cache");
            return Optional.empty();
        }
    }
}
