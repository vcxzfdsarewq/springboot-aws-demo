package com.example.expense.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.example.expense.entity.Expense;
import com.example.expense.entity.ExpenseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** 所有者スコープでの一覧取得 (自分の経費)。 */
    Page<Expense> findByUserId(Long userId, Pageable pageable);

    /** 所有者スコープでの単一取得。他人の経費は取得できない。 */
    Optional<Expense> findByIdAndUserId(Long id, Long userId);

    /** 管理者向け: ステータスでフィルタした一覧。 */
    Page<Expense> findByStatus(ExpenseStatus status, Pageable pageable);

    @Query("""
            select count(e) as expenseCount, sum(e.amount) as totalAmount
            from Expense e
            where e.expenseDate >= :from and e.expenseDate < :to
              and (:userId is null or e.userId = :userId)
              and (:status is null or e.status = :status)
            """)
    MonthlyExpenseAggregate aggregateMonthly(@Param("from") LocalDate from,
                                             @Param("to") LocalDate to,
                                             @Param("userId") Long userId,
                                             @Param("status") ExpenseStatus status);

    @Query("""
            select e.category as category, count(e) as expenseCount,
                   sum(e.amount) as totalAmount
            from Expense e
            where e.expenseDate >= :from and e.expenseDate <= :to
              and (:userId is null or e.userId = :userId)
              and (:status is null or e.status = :status)
            group by e.category
            order by sum(e.amount) desc, e.category asc
            """)
    List<CategoryExpenseAggregate> aggregateByCategory(@Param("from") LocalDate from,
                                                       @Param("to") LocalDate to,
                                                       @Param("userId") Long userId,
                                                       @Param("status") ExpenseStatus status);

    interface MonthlyExpenseAggregate {
        long getExpenseCount();

        BigDecimal getTotalAmount();
    }

    interface CategoryExpenseAggregate {
        String getCategory();

        long getExpenseCount();

        BigDecimal getTotalAmount();
    }
}

