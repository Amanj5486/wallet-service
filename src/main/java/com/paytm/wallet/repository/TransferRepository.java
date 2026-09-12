package com.paytm.wallet.repository;

import com.paytm.wallet.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {
    Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey);

    @Modifying
    @Query(value = """
        INSERT INTO transfers (id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, status, created_at, updated_at) 
        VALUES (:id, :idempotencyKey, :fromWalletId, :toWalletId, :amountPaise, 'PENDING', NOW(), NOW())
        ON CONFLICT (idempotency_key) DO NOTHING
    """, nativeQuery = true)
    void insertOrIgnore(
        @Param("id") UUID id,
        @Param("idempotencyKey") UUID idempotencyKey,
        @Param("fromWalletId") UUID fromWalletId,
        @Param("toWalletId") UUID toWalletId,
        @Param("amountPaise") Long amountPaise
    );
}
