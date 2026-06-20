package com.example.expense.exception;

/** 依存サービス (Redis 等) が利用不可で fail-closed する場合 → 503。 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
