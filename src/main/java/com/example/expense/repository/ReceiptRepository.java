package com.example.expense.repository;

import java.util.List;
import java.util.Optional;

import com.example.expense.entity.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    List<Receipt> findByExpenseId(Long expenseId);

    /** IDOR 対策: receipt が指定 expense に属することを DB レベルでも保証する。 */
    Optional<Receipt> findByIdAndExpenseId(Long id, Long expenseId);
}
