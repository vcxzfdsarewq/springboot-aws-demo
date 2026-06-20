package com.example.expense.exception;

/** 一意制約違反 (例: 登録済みメールアドレス) → 409 Conflict。 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
