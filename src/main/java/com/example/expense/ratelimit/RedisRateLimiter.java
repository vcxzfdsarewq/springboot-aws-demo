package com.example.expense.ratelimit;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis を共有ストアとした固定ウィンドウ方式のレートリミッタ。
 *
 * <p>設計では Bucket4j を想定していたが、bucket4j-redis の配線複雑さを避け、
 * 同等の「全タスク共有・原子的カウント」を Lua スクリプトで実装している。
 * ECS マルチタスクでも制限値がタスク数ぶん緩くならない (インメモリ不使用)。
 */
@Component
public class RedisRateLimiter {

    // INCR して、最初の1回だけ EXPIRE を設定。現在値と残TTLを返す (原子的)。
    @SuppressWarnings("unchecked")
    private static final RedisScript<List<Long>> SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
            + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "return {c, redis.call('TTL', KEYS[1])}",
            (Class<List<Long>>) (Class<?>) List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @param key            レート制限キー (例: "rl:login:1.2.3.4")
     * @param limit          ウィンドウ内の許可回数
     * @param windowSeconds  ウィンドウ長 (秒)
     */
    public Result tryAcquire(String key, int limit, int windowSeconds) {
        List<Long> result = redis.execute(SCRIPT, List.of(key),
                String.valueOf(windowSeconds));
        long count = result.get(0);
        long ttl = result.get(1);
        boolean allowed = count <= limit;
        return new Result(allowed, Math.max(ttl, 0));
    }

    /** 現在のカウント (加算せず参照のみ)。キーが無ければ 0。 */
    public long currentCount(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    /** カウントを1増やし、最初の1回だけウィンドウの TTL を設定する。 */
    public void increment(String key, int windowSeconds) {
        redis.execute(SCRIPT, List.of(key), String.valueOf(windowSeconds));
    }

    /** カウントをリセット (ログイン成功時など)。 */
    public void reset(String key) {
        redis.delete(key);
    }

    /** キーの残 TTL 秒。 */
    public long ttlSeconds(String key) {
        Long ttl = redis.getExpire(key);
        return ttl == null ? 0 : Math.max(ttl, 0);
    }

    /** 許可結果と、超過時の Retry-After 秒数。 */
    public record Result(boolean allowed, long retryAfterSeconds) {
    }
}
