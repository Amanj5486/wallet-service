package com.paytm.wallet.repository;

import com.paytm.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    Optional<Wallet> findByUserId(String userId);

    @Modifying
    @Query(value = """
        INSERT INTO wallets (id, user_id, balance_paise, created_at, updated_at) 
        VALUES (:id, :userId, 0, NOW(), NOW())
        ON CONFLICT (user_id) DO NOTHING
    """, nativeQuery = true)
    void insertOrIgnore(@Param("id") UUID id, @Param("userId") String userId);

    @Modifying
    @Query("""
        UPDATE Wallet w 
        SET w.balancePaise = w.balancePaise - :amount 
        WHERE w.id = :walletId AND w.balancePaise >= :amount
    """)
    int debit(@Param("walletId") UUID walletId, @Param("amount") Long amountPaise);

    @Modifying
    @Query("""
        UPDATE Wallet w 
        SET w.balancePaise = w.balancePaise + :amount 
        WHERE w.id = :walletId
    """)
    int credit(@Param("walletId") UUID walletId, @Param("amount") Long amountPaise);

    @Query("""
        SELECT SUM(w.balancePaise) 
        FROM Wallet w
    """)
    Long getTotalBalance();
}
