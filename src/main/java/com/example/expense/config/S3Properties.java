package com.example.expense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 設定。
 * 本番: endpoint 未指定で実 S3 + IAM タスクロール (DefaultCredentialsProvider)。
 * ローカル: endpoint=MinIO, path-style=true, accessKey/secretKey を指定。
 */
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        String bucket,
        String region,
        String endpoint,
        boolean pathStyleAccess,
        String accessKey,
        String secretKey,
        long presignExpirySeconds
) {
    public S3Properties {
        if (region == null || region.isBlank()) {
            region = "ap-northeast-1";
        }
        if (presignExpirySeconds <= 0) {
            presignExpirySeconds = 300; // 5分
        }
    }

    public boolean hasStaticCredentials() {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    public boolean hasCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
