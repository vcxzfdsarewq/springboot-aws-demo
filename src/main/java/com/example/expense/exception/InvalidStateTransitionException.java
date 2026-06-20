package com.example.expense.exception;

import com.example.expense.entity.ExpenseStatus;

/** 経費ステータスの不正な遷移要求 (409 Conflict)。 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }

    public static InvalidStateTransitionException of(ExpenseStatus from, String action) {
        return new InvalidStateTransitionException(
                "Cannot %s an expense in %s state".formatted(action, from));
    }
}
