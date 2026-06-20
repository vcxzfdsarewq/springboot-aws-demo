package com.example.expense.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.example.expense.entity.Expense;
import com.example.expense.entity.Receipt;
import com.example.expense.entity.Role;
import com.example.expense.exception.InvalidFileException;
import com.example.expense.exception.InvalidStateTransitionException;
import com.example.expense.exception.ResourceNotFoundException;
import com.example.expense.repository.ExpenseRepository;
import com.example.expense.repository.ReceiptRepository;
import com.example.expense.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 領収書のアップロード・取得 (presigned URL)・削除。
 *
 * <p>IDOR 対策:
 * <ul>
 *   <li>USER は自分が作成した経費 (expense.user_id == userId) の領収書のみ操作可能</li>
 *   <li>receipt.expense_id == パスの expenseId を検証 (パス改ざん防止)。不一致は 404</li>
 *   <li>ADMIN は承認業務のため全領収書を取得可能だが、追加・削除は不可</li>
 * </ul>
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);
    static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10MB

    private final ReceiptRepository receiptRepository;
    private final ExpenseRepository expenseRepository;
    private final S3StorageService storage;

    public ReceiptService(ReceiptRepository receiptRepository, ExpenseRepository expenseRepository,
                          S3StorageService storage) {
        this.receiptRepository = receiptRepository;
        this.expenseRepository = expenseRepository;
        this.storage = storage;
    }

    /** アップロード (所有者のみ・DRAFT/REJECTED のみ)。 */
    @Transactional
    public Receipt upload(Long userId, Long expenseId, byte[] content, long declaredSize,
                          String originalFilename) {
        Expense expense = requireOwnedExpense(userId, expenseId);
        if (!expense.getStatus().isEditableByOwner()) {
            throw InvalidStateTransitionException.of(expense.getStatus(), "add a receipt to");
        }
        if (content == null || content.length == 0) {
            throw new InvalidFileException("Empty file");
        }
        if (content.length > MAX_FILE_SIZE || declaredSize > MAX_FILE_SIZE) {
            throw new InvalidFileException("File exceeds 10MB limit");
        }
        // クライアント申告ではなくマジックバイトで判定
        FileType type = FileType.detect(content);

        String key = "receipts/" + expenseId + "/" + UUID.randomUUID() + "." + type.extension();
        storage.put(key, content, type.contentType());
        deleteFromStorageOnRollback(key);

        String safeName = sanitizeFilename(originalFilename, type.extension());
        Receipt receipt = new Receipt(expenseId, safeName, key, type.contentType(), content.length);
        return receiptRepository.save(receipt);
    }

    /** 取得 (presigned URL)。USER は所有者のみ、ADMIN は全件可。 */
    @Transactional(readOnly = true)
    public ReceiptView getForDownload(AuthPrincipal principal, Long expenseId, Long receiptId) {
        Receipt receipt = requireReceiptInExpense(expenseId, receiptId);
        authorizeRead(principal, expenseId);
        String url = storage.presignedGetUrl(receipt.getS3Key());
        return new ReceiptView(receipt, url);
    }

    /** 一覧 (メタデータのみ)。USER は所有者のみ、ADMIN は全件可。 */
    @Transactional(readOnly = true)
    public List<Receipt> list(AuthPrincipal principal, Long expenseId) {
        authorizeRead(principal, expenseId);
        return receiptRepository.findByExpenseId(expenseId);
    }

    /** 削除 (所有者のみ・DRAFT/REJECTED のみ)。S3 と DB の両方から削除。 */
    @Transactional
    public void delete(Long userId, Long expenseId, Long receiptId) {
        Expense expense = requireOwnedExpense(userId, expenseId);
        if (!expense.getStatus().isEditableByOwner()) {
            throw InvalidStateTransitionException.of(expense.getStatus(), "delete a receipt from");
        }
        Receipt receipt = requireReceiptInExpense(expenseId, receiptId);
        receiptRepository.delete(receipt);
        deleteFromStorageAfterCommit(receipt.getS3Key());
    }

    // --- ヘルパ ---

    private Expense requireOwnedExpense(Long userId, Long expenseId) {
        return expenseRepository.findByIdAndUserId(expenseId, userId)
                .orElseThrow(() -> ResourceNotFoundException.expense(expenseId));
    }

    private Receipt requireReceiptInExpense(Long expenseId, Long receiptId) {
        return receiptRepository.findByIdAndExpenseId(receiptId, expenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found: " + receiptId));
    }

    /** 読み取り認可: ADMIN は全件、USER は所有者のみ。非所有者は存在秘匿のため 404。 */
    private void authorizeRead(AuthPrincipal principal, Long expenseId) {
        if (principal.role() == Role.ADMIN) {
            if (!expenseRepository.findById(expenseId).isPresent()) {
                throw ResourceNotFoundException.expense(expenseId);
            }
            return;
        }
        requireOwnedExpense(principal.userId(), expenseId);
    }

    private String sanitizeFilename(String original, String fallbackExt) {
        if (original == null || original.isBlank()) {
            return "receipt." + fallbackExt;
        }
        String name = original.replaceAll(".*[/\\\\]", "")
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (name.isBlank()) {
            return "receipt." + fallbackExt;
        }

        String extension = "." + fallbackExt;
        if (!name.toLowerCase(Locale.ROOT).endsWith(extension)) {
            name = name.replaceAll("\\.[^.]*$", "");
            if (name.isBlank()) {
                name = "receipt";
            }
            name = name + extension;
        }
        return truncatePreservingExtension(name, extension);
    }

    private String truncatePreservingExtension(String name, String extension) {
        if (name.length() <= 255) {
            return name;
        }
        int baseMaxLength = 255 - extension.length();
        String base = name.substring(0, Math.max(baseMaxLength, 1));
        return base + extension;
    }
    private void deleteFromStorageOnRollback(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("Failed to clean up receipt object after transaction rollback: {}", key, e);
                    }
                }
            }
        });
    }

    private void deleteFromStorageAfterCommit(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storage.delete(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(key);
                } catch (RuntimeException e) {
                    log.warn("Failed to delete receipt object after database commit: {}", key, e);
                }
            }
        });
    }

    /** サービス内部用の戻り値 (Receipt + presigned URL)。 */
    public record ReceiptView(Receipt receipt, String url) {
    }
}

