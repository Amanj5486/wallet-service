package com.paytm.wallet.service;

import com.paytm.wallet.entity.Wallet;
import com.paytm.wallet.metrics.TransferMetrics;
import com.paytm.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

        try {
            // Try to insert new wallet
            Wallet wallet = new Wallet();
            wallet.setId(UUID.randomUUID());
            wallet.setUserId(userId);
            wallet.setBalancePaise(0L);

            Wallet created = walletRepository.save(wallet);
            log.info("Wallet created for user: {}", userId,
                "wallet_id", created.getId());
            metrics.recordWalletCreated();
            return created;

        } catch (DataIntegrityViolationException e) {
            // Unique constraint violated, wallet already exists
            log.info("Wallet already exists for user: {}", userId);
            Wallet existing = walletRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    log.error("Wallet not found after constraint violation for user: {}", userId);
                    return new RuntimeException("Wallet not found");
                });
            metrics.recordWalletGetOrCreateHit();
            return existing;
        }
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
}
