package com.example.expense.exception;

/** リクエストパラメータや業務入力の不正 → 400。 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
