package com.example.expense.exception;

/** 認証コンテキストが解決できない (401)。Phase 3 で Spring Security に置き換わる。 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
