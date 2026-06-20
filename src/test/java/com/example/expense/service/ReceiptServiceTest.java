package com.example.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import com.example.expense.entity.Receipt;
import com.example.expense.entity.Role;
import com.example.expense.exception.InvalidFileException;
import com.example.expense.exception.InvalidStateTransitionException;
import com.example.expense.exception.ResourceNotFoundException;
import com.example.expense.repository.ExpenseRepository;
import com.example.expense.repository.ReceiptRepository;
import com.example.expense.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long OTHER_USER_ID = 9L;
    private static final Long EXPENSE_ID = 10L;
    private static final Long RECEIPT_ID = 100L;

    // 有効な PNG マジックバイト
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] NOT_IMAGE = "hello world not an image".getBytes();

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private S3StorageService storage;

    @InjectMocks private ReceiptService service;

    private Expense expense(ExpenseStatus status) {
        Expense e = new Expense(USER_ID, "t", "d", new BigDecimal("1"), "transport", LocalDate.now());
        e.setStatus(status);
        return e;
    }

    private AuthPrincipal principal(Long id, Role role) {
        return new AuthPrincipal(id, "e@x.com", role);
    }

    @Test
    void uploadStoresFileAndSavesMetadata() {
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID))
                .thenReturn(Optional.of(expense(ExpenseStatus.DRAFT)));
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(i -> i.getArgument(0));

        Receipt result = service.upload(USER_ID, EXPENSE_ID, PNG, PNG.length, "scan.png");

        assertThat(result.getContentType()).isEqualTo("image/png");
        assertThat(result.getS3Key()).startsWith("receipts/" + EXPENSE_ID + "/").endsWith(".png");
        verify(storage).put(anyString(), any(byte[].class), org.mockito.ArgumentMatchers.eq("image/png"));
    }

    @Test
    void uploadToOthersExpenseIsNotFound_IDOR() {
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(USER_ID, EXPENSE_ID, PNG, PNG.length, "x.png"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void uploadRejectsNonImageByMagicBytes() {
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID))
                .thenReturn(Optional.of(expense(ExpenseStatus.DRAFT)));

        assertThatThrownBy(() -> service.upload(USER_ID, EXPENSE_ID, NOT_IMAGE, NOT_IMAGE.length, "fake.png"))
                .isInstanceOf(InvalidFileException.class);
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void uploadRejectsOversizeFile() {
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID))
                .thenReturn(Optional.of(expense(ExpenseStatus.DRAFT)));
        byte[] big = new byte[(int) ReceiptService.MAX_FILE_SIZE + 1];
        big[0] = (byte) 0x89; big[1] = 0x50; big[2] = 0x4E; big[3] = 0x47;

        assertThatThrownBy(() -> service.upload(USER_ID, EXPENSE_ID, big, big.length, "big.png"))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void uploadBlockedWhenExpensePending() {
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID))
                .thenReturn(Optional.of(expense(ExpenseStatus.PENDING)));

        assertThatThrownBy(() -> service.upload(USER_ID, EXPENSE_ID, PNG, PNG.length, "x.png"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void getForDownloadReturnsPresignedUrlForOwner() {
        Receipt receipt = new Receipt(EXPENSE_ID, "r.png", "receipts/10/abc.png", "image/png", 123);
        when(receiptRepository.findByIdAndExpenseId(RECEIPT_ID, EXPENSE_ID)).thenReturn(Optional.of(receipt));
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID))
                .thenReturn(Optional.of(expense(ExpenseStatus.DRAFT)));
        when(storage.presignedGetUrl("receipts/10/abc.png")).thenReturn("https://signed/url");

        ReceiptService.ReceiptView view = service.getForDownload(principal(USER_ID, Role.USER), EXPENSE_ID, RECEIPT_ID);

        assertThat(view.url()).isEqualTo("https://signed/url");
    }

    @Test
    void getReceiptWithMismatchedExpenseIsNotFound_IDOR() {
        // receipt は存在するが別の expense に属する -> findByIdAndExpenseId が空
        when(receiptRepository.findByIdAndExpenseId(RECEIPT_ID, EXPENSE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForDownload(principal(USER_ID, Role.USER), EXPENSE_ID, RECEIPT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void adminCanReadAnyReceipt() {
        Receipt receipt = new Receipt(EXPENSE_ID, "r.png", "receipts/10/abc.png", "image/png", 123);
        when(receiptRepository.findByIdAndExpenseId(RECEIPT_ID, EXPENSE_ID)).thenReturn(Optional.of(receipt));
        when(expenseRepository.findById(EXPENSE_ID)).thenReturn(Optional.of(expense(ExpenseStatus.PENDING)));
        when(storage.presignedGetUrl(anyString())).thenReturn("https://signed/url");

        ReceiptService.ReceiptView view =
                service.getForDownload(principal(OTHER_USER_ID, Role.ADMIN), EXPENSE_ID, RECEIPT_ID);

        assertThat(view.url()).isEqualTo("https://signed/url");
    }

    @Test
    void deleteRemovesFromS3AndDb() {
        Receipt receipt = new Receipt(EXPENSE_ID, "r.png", "receipts/10/abc.png", "image/png", 123);
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID))
                .thenReturn(Optional.of(expense(ExpenseStatus.DRAFT)));
        when(receiptRepository.findByIdAndExpenseId(RECEIPT_ID, EXPENSE_ID)).thenReturn(Optional.of(receipt));

        service.delete(USER_ID, EXPENSE_ID, RECEIPT_ID);

        verify(storage).delete("receipts/10/abc.png");
        verify(receiptRepository).delete(receipt);
    }
    @Test
    void uploadSanitizesFilenameAndUsesDetectedExtension() {
        when(expenseRepository.findByIdAndUserId(EXPENSE_ID, USER_ID))
                .thenReturn(Optional.of(expense(ExpenseStatus.DRAFT)));
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(i -> i.getArgument(0));

        Receipt result = service.upload(USER_ID, EXPENSE_ID, PNG, PNG.length, "C:\\tmp\\evil.exe\n");

        assertThat(result.getFileName()).isEqualTo("evil.png");
    }
}

