-- Phase 3: refresh_tokens
-- Refresh Token は平文を保存せず SHA-256 ハッシュのみ保持。ローテーション方式。

CREATE TABLE refresh_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id),
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 hex
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  BIGINT       REFERENCES refresh_tokens (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
