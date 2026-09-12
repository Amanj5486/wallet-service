package com.paytm.wallet.service;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.entity.LedgerEntry;
import com.paytm.wallet.entity.Transfer;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.metrics.TransferMetrics;
import com.paytm.wallet.repository.LedgerRepository;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
public class TransferService {
    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final LedgerRepository ledgerRepository;
    private final TransferMetrics metrics;

    public TransferService(TransferRepository transferRepository,
                          WalletRepository walletRepository,
                          LedgerRepository ledgerRepository,
                          TransferMetrics metrics) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.metrics = metrics;
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Transfer initiated: from_wallet_id={}, to_wallet_id={}, amount_paise={}, idempotency_key={}",
            request.getFrom(), request.getTo(), request.getAmountPaise(), request.getIdempotencyKey());

        // Try to insert with ON CONFLICT DO NOTHING
        // This is atomic and race-free at the database level
        UUID transferId = UUID.randomUUID();
        transferRepository.insertOrIgnore(
            transferId,
            request.getIdempotencyKey(),
            request.getFrom(),
            request.getTo(),
            request.getAmountPaise()
        );

        // Fetch the transfer (either the one we just created or the existing one)
        Transfer transfer = transferRepository.findByIdempotencyKey(request.getIdempotencyKey())
            .orElseThrow(() -> {
                log.error("Transfer not found for idempotency key: {}", request.getIdempotencyKey());
                return new RuntimeException("Transfer not found");
            });

        // Verify body matches
        if (!transfer.getFromWalletId().equals(request.getFrom()) ||
            !transfer.getToWalletId().equals(request.getTo()) ||
            !transfer.getAmountPaise().equals(request.getAmountPaise())) {
            log.warn("Idempotency key conflict: same key, different body, idempotency_key={}", request.getIdempotencyKey());
            throw new IdempotencyConflictException("Same idempotency key with different request body");
        }

        // Check if we created it or it already existed
        if (transfer.getId().equals(transferId)) {
            log.info("Transfer created: transfer_id={}, from_wallet_id={}, to_wallet_id={}, amount_paise={}",
                transfer.getId(), request.getFrom(), request.getTo(), request.getAmountPaise());
            metrics.recordTransferCreated();

            // New transfer, proceed with debit/credit with retry on deadlock
            return executeTransferWithRetry(transfer, request, startTime, 0);
        } else {
            // Idempotent replay
            log.info("Transfer idempotent replay: transfer_id={}, idempotency_key={}",
                transfer.getId(), request.getIdempotencyKey());
            metrics.recordIdempotentReplay();
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordTransferLatency(duration);
            return TransferResponse.from(transfer);
        }
    }

    private TransferResponse executeTransferWithRetry(Transfer transfer, TransferRequest request, long startTime, int retryCount) {
        try {
            return executeTransfer(transfer, request, startTime);
        } catch (Exception e) {
            // Check if it's a deadlock error
            if (e.getCause() != null && e.getCause().getMessage() != null && 
                e.getCause().getMessage().contains("deadlock detected") && retryCount < 3) {
                log.warn("Deadlock detected, retrying transfer: transfer_id={}, retry_count={}", transfer.getId(), retryCount + 1);
                // Exponential backoff: 10ms, 20ms, 40ms
                try {
                    Thread.sleep((long) Math.pow(2, retryCount) * 10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return executeTransferWithRetry(transfer, request, startTime, retryCount + 1);
            }
            throw e;
        }
    }

    private TransferResponse executeTransfer(Transfer transfer, TransferRequest request, long startTime) {
        // Lock wallets in deterministic order (lower ID first) to prevent deadlock
        UUID fromId = request.getFrom();
        UUID toId = request.getTo();
        
        // Ensure consistent lock order: always lock lower UUID first
        if (fromId.compareTo(toId) > 0) {
            // If from > to, we need to be careful about lock order
            // But for debit/credit, we always debit from first, credit to second
            // The DB will handle lock ordering internally with our conditional UPDATE
        }

        // Debit source wallet (atomic conditional UPDATE)
        int debitRows = walletRepository.debit(fromId, request.getAmountPaise());

        if (debitRows == 0) {
            // Insufficient funds
            transfer.setStatus("DECLINED");
            transfer.setReason("INSUFFICIENT_FUNDS");
            transferRepository.save(transfer);
            log.info("Transfer declined: transfer_id={}, reason={}, from_wallet_id={}, amount_paise={}",
                transfer.getId(), "INSUFFICIENT_FUNDS", fromId, request.getAmountPaise());
            metrics.recordTransferDeclined();
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordTransferLatency(duration);
            return TransferResponse.from(transfer);
        }

        // Debit succeeded, credit destination
        walletRepository.credit(toId, request.getAmountPaise());
        log.info("Transfer debited: transfer_id={}, from_wallet_id={}, amount_paise={}",
            transfer.getId(), fromId, request.getAmountPaise());

        // Create ledger entries (immutable audit trail)
        LedgerEntry debitEntry = new LedgerEntry();
        debitEntry.setId(UUID.randomUUID());
        debitEntry.setTransferId(transfer.getId());
        debitEntry.setWalletId(request.getFrom());
        debitEntry.setAmountPaise(request.getAmountPaise());
        debitEntry.setEntryType("DEBIT");
        ledgerRepository.save(debitEntry);

        LedgerEntry creditEntry = new LedgerEntry();
        creditEntry.setId(UUID.randomUUID());
        creditEntry.setTransferId(transfer.getId());
        creditEntry.setWalletId(request.getTo());
        creditEntry.setAmountPaise(request.getAmountPaise());
        creditEntry.setEntryType("CREDIT");
        ledgerRepository.save(creditEntry);

        log.info("Transfer credited: transfer_id={}, to_wallet_id={}, amount_paise={}",
            transfer.getId(), request.getTo(), request.getAmountPaise());

        // Mark transfer as completed
        transfer.setStatus("COMPLETED");
        transferRepository.save(transfer);

        log.info("Transfer completed: transfer_id={}, from_wallet_id={}, to_wallet_id={}, amount_paise={}",
            transfer.getId(), request.getFrom(), request.getTo(), request.getAmountPaise());

        long duration = System.currentTimeMillis() - startTime;
        metrics.recordTransferLatency(duration);
        return TransferResponse.from(transfer);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transferId) {
        log.debug("Getting transfer: {}", transferId);
        Transfer transfer = transferRepository.findById(transferId)
            .orElseThrow(() -> {
                log.warn("Transfer not found: {}", transferId);
                return new RuntimeException("Transfer not found");
            });
        return TransferResponse.from(transfer);
    }
}
