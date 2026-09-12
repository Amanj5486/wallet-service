package com.paytm.wallet.service;

import com.paytm.wallet.entity.Wallet;
import com.paytm.wallet.metrics.TransferMetrics;
import com.paytm.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
public class WalletService {
    private final WalletRepository walletRepository;
    private final TransferMetrics metrics;

    public WalletService(WalletRepository walletRepository, TransferMetrics metrics) {
        this.walletRepository = walletRepository;
        this.metrics = metrics;
    }

    @Transactional
    public Wallet getOrCreate(String userId) {
        log.info("Getting or creating wallet for user: {}", userId);

        // Try to insert with ON CONFLICT DO NOTHING
        // This is atomic and race-free at the database level
        UUID walletId = UUID.randomUUID();
        walletRepository.insertOrIgnore(walletId, userId);

        // Fetch the wallet (either the one we just created or the existing one)
        Wallet wallet = walletRepository.findByUserId(userId)
            .orElseThrow(() -> {
                log.error("Wallet not found for user: {}", userId);
                return new RuntimeException("Wallet not found");
            });

        // Check if we created it or it already existed
        if (wallet.getId().equals(walletId)) {
            log.info("Wallet created for user: {}", userId);
            metrics.recordWalletCreated();
        } else {
            log.info("Wallet already exists for user: {}", userId);
            metrics.recordWalletGetOrCreateHit();
        }

        return wallet;
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(UUID walletId) {
        log.debug("Getting wallet: {}", walletId);
        return walletRepository.findById(walletId)
            .orElseThrow(() -> {
                log.warn("Wallet not found: {}", walletId);
                return new RuntimeException("Wallet not found");
            });
    }

    @Transactional(readOnly = true)
    public Long getTotalBalance() {
        Long total = walletRepository.getTotalBalance();
        return total != null ? total : 0L;
    }

    @Transactional
    public Wallet fundWallet(UUID walletId, Long amountPaise) {
        log.info("Funding wallet: {} with {} paise", walletId, amountPaise);
        walletRepository.credit(walletId, amountPaise);
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> {
                log.error("Wallet not found: {}", walletId);
                return new RuntimeException("Wallet not found");
            });
        log.info("Wallet funded: {}, new balance: {} paise", walletId, wallet.getBalancePaise());
        return wallet;
    }
}
