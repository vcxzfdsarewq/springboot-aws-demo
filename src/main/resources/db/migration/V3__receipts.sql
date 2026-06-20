-- Phase 4: receipts (領収書)
-- 実体は S3 に保存し、DB にはメタデータと S3 オブジェクトキーのみ保持。

CREATE TABLE receipts (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    expense_id   BIGINT       NOT NULL REFERENCES expenses (id) ON DELETE CASCADE,
    file_name    VARCHAR(255) NOT NULL,
    s3_key       VARCHAR(500) NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    file_size    BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_receipts_expense_id ON receipts (expense_id);
