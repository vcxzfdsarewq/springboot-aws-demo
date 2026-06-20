package com.example.expense.repository;

import java.util.Optional;

import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** 所有者スコープでの一覧取得 (自分の経費)。 */
    Page<Expense> findByUserId(Long userId, Pageable pageable);

    /** 所有者スコープでの単一取得。他人の経費は取得できない。 */
    Optional<Expense> findByIdAndUserId(Long id, Long userId);

    /** 管理者向け: ステータスでフィルタした一覧。 */
    Page<Expense> findByStatus(ExpenseStatus status, Pageable pageable);
}
