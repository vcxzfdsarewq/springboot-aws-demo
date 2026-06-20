package com.example.expense.entity;

import java.util.Set;

/**
 * 経費のステータスと、許可される遷移を表す状態機械。
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING ──approve──▶ APPROVED
 *     ▲                 │  │
 *     └──withdraw───────┘  └──reject──▶ REJECTED ──submit(再申請)──▶ PENDING
 * </pre>
 * APPROVED は終端 (変更不可)。
 */
public enum ExpenseStatus {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED;

    /** このステータスから遷移可能なステータス集合。 */
    public Set<ExpenseStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> Set.of(PENDING);
            case PENDING -> Set.of(DRAFT, APPROVED, REJECTED);
            case REJECTED -> Set.of(PENDING);
            case APPROVED -> Set.of();
        };
    }

    public boolean canTransitionTo(ExpenseStatus target) {
        return allowedNext().contains(target);
    }

    /** 所有者(USER)が内容を編集できるステータスか。 */
    public boolean isEditableByOwner() {
        return this == DRAFT || this == REJECTED;
    }

    /** 所有者(USER)が削除できるステータスか。 */
    public boolean isDeletableByOwner() {
        return this == DRAFT;
    }
}
