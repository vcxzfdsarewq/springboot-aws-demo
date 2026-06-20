package com.example.expense.exception;

/** アップロードファイルが不正 (空・非対応形式・マジックバイト不一致) → 400。 */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
