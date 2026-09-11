package com.paytm.wallet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transfers", indexes = {
    @Index(name = "idx_idempotency_key", columnList = "idempotency_key"),
    @Index(name = "idx_from_wallet", columnList = "from_wallet_id"),
    @Index(name = "idx_to_wallet", columnList = "to_wallet_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transfer {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(nullable = false)
    private UUID fromWalletId;

    @Column(nullable = false)
    private UUID toWalletId;

    @Column(nullable = false)
    private Long amountPaise;  // Integer paise, never float

    @Column(nullable = false)
    private String status;  // PENDING, COMPLETED, DECLINED

    @Column
    private String reason;  // INSUFFICIENT_FUNDS, etc.

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
