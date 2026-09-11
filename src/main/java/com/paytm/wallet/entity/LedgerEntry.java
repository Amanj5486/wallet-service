package com.paytm.wallet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries", indexes = {
    @Index(name = "idx_wallet_created", columnList = "wallet_id,created_at"),
    @Index(name = "idx_transfer_id", columnList = "transfer_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID transferId;

    @Column(nullable = false)
    private UUID walletId;

    @Column(nullable = false)
    private Long amountPaise;  // Integer paise, never float

    @Column(nullable = false)
    private String entryType;  // DEBIT or CREDIT

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
