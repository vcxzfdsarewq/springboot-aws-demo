package com.example.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

import com.example.expense.dto.request.ExpenseRequest;
import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import com.example.expense.exception.InvalidStateTransitionException;
import com.example.expense.exception.ResourceNotFoundException;
import com.example.expense.repository.ExpenseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long ADMIN_ID = 1L;
    private static final Long EXPENSE_ID = 10L;

    @Mock
    private ExpenseRepository expenseRepository;

    @InjectMocks
    private ExpenseService service;

    private Expense expenseWithStatus(ExpenseStatus status) {
        Expense e = new Expense(USER_ID, "Taxi", "client visit",
                new BigDecimal("1200.00"), "交通費", LocalDate.now());
        e.setStatus(status);
        return e;
    }

    private ExpenseRequest sampleRequest() {
        return new ExpenseRequest("Hotel", "trip", new BigDecimal("8000.00"),
                "宿泊費", LocalDate.now());
    }

    @Test
    void createStartsAsDraft() {
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

        Expense result = service.create(USER_ID, sampleRequest());

        assertThat(result.getStatus()).isEqualTo(ExpenseStatus.DRAFT);
        assertThat(result.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void getOnOthersExpenseReturns404() {
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID, EXPENSE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateAllowedOnDraft() {
        Expense draft = expenseWithStatus(ExpenseStatus.DRAFT);
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID)).thenReturn(Optional.of(draft));

        Expense result = service.update(USER_ID, EXPENSE_ID, sampleRequest());

        assertThat(result.getTitle()).isEqualTo("Hotel");
        assertThat(result.getAmount()).isEqualByComparingTo("8000.00");
    }

    @Test
    void updateRejectedOnPending() {
        Expense pending = expenseWithStatus(ExpenseStatus.PENDING);
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.update(USER_ID, EXPENSE_ID, sampleRequest()))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void deleteOnlyOnDraft() {
        Expense pending = expenseWithStatus(ExpenseStatus.PENDING);
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.delete(USER_ID, EXPENSE_ID))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(expenseRepository, never()).delete(any());
    }

    @Test
    void submitMovesDraftToPending() {
        Expense draft = expenseWithStatus(ExpenseStatus.DRAFT);
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID)).thenReturn(Optional.of(draft));

        Expense result = service.submit(USER_ID, EXPENSE_ID);

        assertThat(result.getStatus()).isEqualTo(ExpenseStatus.PENDING);
    }

    @Test
    void resubmitClearsRejectionInfo() {
        Expense rejected = expenseWithStatus(ExpenseStatus.REJECTED);
        rejected.setRejectReason("missing receipt");
        rejected.setReviewerId(ADMIN_ID);
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID)).thenReturn(Optional.of(rejected));

        Expense result = service.submit(USER_ID, EXPENSE_ID);

        assertThat(result.getStatus()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(result.getRejectReason()).isNull();
        assertThat(result.getReviewerId()).isNull();
    }

    @Test
    void withdrawMovesPendingToDraft() {
        Expense pending = expenseWithStatus(ExpenseStatus.PENDING);
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID)).thenReturn(Optional.of(pending));

        Expense result = service.withdraw(USER_ID, EXPENSE_ID);

        assertThat(result.getStatus()).isEqualTo(ExpenseStatus.DRAFT);
    }

    @Test
    void approveMovesPendingToApproved() {
        Expense pending = expenseWithStatus(ExpenseStatus.PENDING);
        when(expenseRepository.findById(EXPENSE_ID)).thenReturn(Optional.of(pending));

        Expense result = service.approve(ADMIN_ID, EXPENSE_ID);

        assertThat(result.getStatus()).isEqualTo(ExpenseStatus.APPROVED);
        assertThat(result.getReviewerId()).isEqualTo(ADMIN_ID);
        assertThat(result.getReviewedAt()).isNotNull();
    }

    @Test
    void approveOnDraftIsRejected() {
        Expense draft = expenseWithStatus(ExpenseStatus.DRAFT);
        when(expenseRepository.findById(EXPENSE_ID)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.approve(ADMIN_ID, EXPENSE_ID))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectMovesPendingToRejectedWithReason() {
        Expense pending = expenseWithStatus(ExpenseStatus.PENDING);
        when(expenseRepository.findById(EXPENSE_ID)).thenReturn(Optional.of(pending));

        Expense result = service.reject(ADMIN_ID, EXPENSE_ID, "out of policy");

        assertThat(result.getStatus()).isEqualTo(ExpenseStatus.REJECTED);
        assertThat(result.getRejectReason()).isEqualTo("out of policy");
    }

    @Test
    void approveMissingExpenseReturns404() {
        when(expenseRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(ADMIN_ID, EXPENSE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
    @Test
    void adminListCanFilterByStatus() {
        Expense pending = expenseWithStatus(ExpenseStatus.PENDING);
        when(expenseRepository.findByStatus(ExpenseStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(pending)));

        var page = service.listForAdmin(ExpenseStatus.PENDING, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(ExpenseStatus.PENDING);
    }

    @Test
    void monthlyReportDefaultsToApprovedStatus() {
        when(expenseRepository.aggregateMonthly(LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 7, 1), null, ExpenseStatus.APPROVED))
                .thenReturn(new MonthlyAggregateStub(2L, new BigDecimal("3000.00")));

        var result = service.monthlyReport(2026, 6, null, null);

        assertThat(result.year()).isEqualTo(2026);
        assertThat(result.month()).isEqualTo(6);
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.totalAmount()).isEqualByComparingTo("3000.00");
    }

    @Test
    void monthlyReportSupportsAllStatuses() {
        when(expenseRepository.aggregateMonthly(LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 7, 1), USER_ID, null))
                .thenReturn(new MonthlyAggregateStub(0L, null));

        var result = service.monthlyReport(2026, 6, USER_ID, "ALL");

        assertThat(result.count()).isZero();
        assertThat(result.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void categoryReportReturnsAmountDescendingRowsFromRepository() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        when(expenseRepository.aggregateByCategory(from, to, USER_ID, ExpenseStatus.APPROVED))
                .thenReturn(List.of(new CategoryAggregateStub("交通費", 2L, new BigDecimal("5000.00"))));

        var result = service.categoryReport(from, to, USER_ID, "APPROVED");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).category()).isEqualTo("交通費");
        assertThat(result.get(0).count()).isEqualTo(2);
        assertThat(result.get(0).totalAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void categoryReportRejectsInvalidRange() {
        assertThatThrownBy(() -> service.categoryReport(LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 6, 30), null, "APPROVED"))
                .isInstanceOf(com.example.expense.exception.BadRequestException.class);
    }

    private record MonthlyAggregateStub(long expenseCount, BigDecimal totalAmount)
            implements ExpenseRepository.MonthlyExpenseAggregate {
        @Override
        public long getExpenseCount() {
            return expenseCount;
        }

        @Override
        public BigDecimal getTotalAmount() {
            return totalAmount;
        }
    }

    private record CategoryAggregateStub(String category, long expenseCount, BigDecimal totalAmount)
            implements ExpenseRepository.CategoryExpenseAggregate {
        @Override
        public String getCategory() {
            return category;
        }

        @Override
        public long getExpenseCount() {
            return expenseCount;
        }

        @Override
        public BigDecimal getTotalAmount() {
            return totalAmount;
        }
    }
}

