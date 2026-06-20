package com.example.expense.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExpenseStatusTest {

    @Test
    void draftCanOnlyGoToPending() {
        assertThat(ExpenseStatus.DRAFT.canTransitionTo(ExpenseStatus.PENDING)).isTrue();
        assertThat(ExpenseStatus.DRAFT.canTransitionTo(ExpenseStatus.APPROVED)).isFalse();
        assertThat(ExpenseStatus.DRAFT.canTransitionTo(ExpenseStatus.REJECTED)).isFalse();
    }

    @Test
    void pendingCanGoToDraftApprovedOrRejected() {
        assertThat(ExpenseStatus.PENDING.canTransitionTo(ExpenseStatus.DRAFT)).isTrue();
        assertThat(ExpenseStatus.PENDING.canTransitionTo(ExpenseStatus.APPROVED)).isTrue();
        assertThat(ExpenseStatus.PENDING.canTransitionTo(ExpenseStatus.REJECTED)).isTrue();
    }

    @Test
    void rejectedCanBeResubmitted() {
        assertThat(ExpenseStatus.REJECTED.canTransitionTo(ExpenseStatus.PENDING)).isTrue();
    }

    @Test
    void approvedIsTerminal() {
        assertThat(ExpenseStatus.APPROVED.allowedNext()).isEmpty();
        assertThat(ExpenseStatus.APPROVED.canTransitionTo(ExpenseStatus.PENDING)).isFalse();
    }

    @Test
    void editableAndDeletableRules() {
        assertThat(ExpenseStatus.DRAFT.isEditableByOwner()).isTrue();
        assertThat(ExpenseStatus.REJECTED.isEditableByOwner()).isTrue();
        assertThat(ExpenseStatus.PENDING.isEditableByOwner()).isFalse();
        assertThat(ExpenseStatus.APPROVED.isEditableByOwner()).isFalse();

        assertThat(ExpenseStatus.DRAFT.isDeletableByOwner()).isTrue();
        assertThat(ExpenseStatus.REJECTED.isDeletableByOwner()).isFalse();
    }
}
