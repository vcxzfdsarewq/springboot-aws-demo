-- Phase 1: users + expenses
-- 日時はすべて TIMESTAMPTZ (UTC 保存)。expense_date は暦日のため DATE。

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE expenses (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id),
    title         VARCHAR(200) NOT NULL,
    description   TEXT,
    amount        DECIMAL(10, 2) NOT NULL,
    category      VARCHAR(50)  NOT NULL,
    expense_date  DATE         NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    reviewer_id   BIGINT       REFERENCES users (id),
    reviewed_at   TIMESTAMPTZ,
    reject_reason TEXT,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_expenses_user_id ON expenses (user_id);
CREATE INDEX idx_expenses_status ON expenses (status);
CREATE INDEX idx_expenses_expense_date ON expenses (expense_date);
