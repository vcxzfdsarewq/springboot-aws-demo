package com.example.expense.service;

import com.example.expense.exception.InvalidFileException;

/**
 * 許可する領収書ファイル形式と、マジックバイトによる判定。
 * クライアント申告の Content-Type は信用せず、先頭バイトで実体を判定する。
 */
public enum FileType {

    JPEG("image/jpeg", "jpg", new int[]{0xFF, 0xD8, 0xFF}),
    PNG("image/png", "png", new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
    PDF("application/pdf", "pdf", new int[]{0x25, 0x50, 0x44, 0x46});  // %PDF

    private final String contentType;
    private final String extension;
    private final int[] magic;

    FileType(String contentType, String extension, int[] magic) {
        this.contentType = contentType;
        this.extension = extension;
        this.magic = magic;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    private boolean matches(byte[] content) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((content[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** 先頭バイトから形式を判定。対応外なら例外。 */
    public static FileType detect(byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidFileException("Empty file");
        }
        for (FileType type : values()) {
            if (type.matches(content)) {
                return type;
            }
        }
        throw new InvalidFileException("Unsupported file type (allowed: JPEG, PNG, PDF)");
    }
}
