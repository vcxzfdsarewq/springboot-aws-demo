package com.example.expense.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.example.expense.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** ローテーションを直列化するため SELECT ... FOR UPDATE で取得する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String hash);

    /** 盗難検知時: 当該ユーザーの有効な Refresh Token をすべて失効。 */
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now "
            + "where r.userId = :userId and r.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    /** 期限切れ・失効済みの古いレコードを物理削除 (日次バッチ)。 */
    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") OffsetDateTime threshold);
}
