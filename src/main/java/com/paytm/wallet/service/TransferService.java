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

        UUID fromId = request.getFrom();
        UUID toId = request.getTo();

        // ── Step 1: Idempotency via ON CONFLICT DO NOTHING ──
        // Insert is in the SAME transaction as debit/credit,
        // so idempotency key uniqueness and ledger movement are atomic.
        UUID transferId = UUID.randomUUID();
        transferRepository.insertOrIgnore(
            transferId,
            request.getIdempotencyKey(),
            fromId,
            toId,
            request.getAmountPaise()
        );

        // Fetch the transfer (the one we just created, or the existing one)
        Transfer transfer = transferRepository.findByIdempotencyKey(request.getIdempotencyKey())
            .orElseThrow(() -> {
                log.error("Transfer not found for idempotency key: {}", request.getIdempotencyKey());
                return new RuntimeException("Transfer not found");
            });

        // Same key + different body → 409
        if (!transfer.getFromWalletId().equals(fromId) ||
            !transfer.getToWalletId().equals(toId) ||
            !transfer.getAmountPaise().equals(request.getAmountPaise())) {
            log.warn("Idempotency key conflict: same key, different body, idempotency_key={}", request.getIdempotencyKey());
            throw new IdempotencyConflictException("Same idempotency key with different request body");
        }

        // Idempotent replay — transfer already executed
        if (!transfer.getId().equals(transferId)) {
            log.info("Transfer idempotent replay: transfer_id={}, idempotency_key={}",
                transfer.getId(), request.getIdempotencyKey());
            metrics.recordIdempotentReplay();
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordTransferLatency(duration);
            return TransferResponse.from(transfer);
        }

        // ── Step 2: Conditional debit ──
        // UPDATE wallets SET balance = balance - amount WHERE id = ? AND balance >= amount
        // Atomic at DB level: no overdraft possible, no explicit locks needed.
        // PostgreSQL acquires implicit row-level locks during UPDATE.
        // Rare deadlock on A→B + B→A is handled by retry loop in the controller.
        log.info("Transfer created: transfer_id={}, from_wallet_id={}, to_wallet_id={}, amount_paise={}",
            transfer.getId(), fromId, toId, request.getAmountPaise());
        metrics.recordTransferCreated();

        int debitRows = walletRepository.debit(fromId, request.getAmountPaise());

        if (debitRows == 0) {
            transfer.setStatus("DECLINED");
            transfer.setReason("INSUFFICIENT_FUNDS");
            transferRepository.save(transfer);
            log.info("Transfer declined: transfer_id={}, reason=INSUFFICIENT_FUNDS, from_wallet_id={}, amount_paise={}",
                transfer.getId(), fromId, request.getAmountPaise());
            metrics.recordTransferDeclined();
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordTransferLatency(duration);
            return TransferResponse.from(transfer);
        }

        // ── Step 3: Credit destination ──
        int creditRows = walletRepository.credit(toId, request.getAmountPaise());
        if (creditRows == 0) {
            throw new RuntimeException("Destination wallet not found: " + toId);
        }

        // ── Step 4: Ledger entries (immutable audit trail) ──
        LedgerEntry debitEntry = new LedgerEntry();
        debitEntry.setId(UUID.randomUUID());
        debitEntry.setTransferId(transfer.getId());
        debitEntry.setWalletId(fromId);
        debitEntry.setAmountPaise(request.getAmountPaise());
        debitEntry.setEntryType("DEBIT");
        ledgerRepository.save(debitEntry);

        LedgerEntry creditEntry = new LedgerEntry();
        creditEntry.setId(UUID.randomUUID());
        creditEntry.setTransferId(transfer.getId());
        creditEntry.setWalletId(toId);
        creditEntry.setAmountPaise(request.getAmountPaise());
        creditEntry.setEntryType("CREDIT");
        ledgerRepository.save(creditEntry);

        // ── Step 5: Mark completed ──
        transfer.setStatus("COMPLETED");
        transferRepository.save(transfer);

        log.info("Transfer completed: transfer_id={}, from_wallet_id={}, to_wallet_id={}, amount_paise={}",
            transfer.getId(), fromId, toId, request.getAmountPaise());

        long duration = System.currentTimeMillis() - startTime;
        metrics.recordTransferLatency(duration);
        return TransferResponse.from(transfer);
    }

    // Creates a FAILED transfer record in a NEW transaction for audit.
    // The original transaction was rolled back (deadlock), so no record exists.
    // This inserts a fresh record with status=FAILED using ON CONFLICT DO NOTHING
    // so it's safe if the idempotency key was somehow committed by another thread.
    @Transactional
    public void markTransferFailed(TransferRequest request, String reason) {
        UUID transferId = UUID.randomUUID();
        transferRepository.insertOrIgnore(
            transferId,
            request.getIdempotencyKey(),
            request.getFrom(),
            request.getTo(),
            request.getAmountPaise()
        );

        transferRepository.findByIdempotencyKey(request.getIdempotencyKey()).ifPresent(transfer -> {
            if ("PENDING".equals(transfer.getStatus())) {
                transfer.setStatus("FAILED");
                transfer.setReason(reason);
                transferRepository.save(transfer);
                log.info("Transfer audit: transfer_id={}, status=FAILED, reason={}, from_wallet_id={}, to_wallet_id={}, amount_paise={}",
                    transfer.getId(), reason, request.getFrom(), request.getTo(), request.getAmountPaise());
            }
        });
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
