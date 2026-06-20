package com.example.expense.exception;

/** リソースが存在しない / アクセス権がない (存在秘匿のため 404)。 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException expense(Long id) {
        return new ResourceNotFoundException("Expense not found: " + id);
    }
}
