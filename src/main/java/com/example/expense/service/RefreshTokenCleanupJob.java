package com.example.expense.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 期限切れ Refresh Token の日次クリーンアップ。 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenService refreshTokenService;

    public RefreshTokenCleanupJob(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    // 毎日 03:15 (UTC) に実行
    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    public void purgeExpired() {
        int deleted = refreshTokenService.deleteExpired();
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }
}
