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
import org.springframework.dao.DataIntegrityViolationException;
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
        log.info("Transfer initiated",
            "from_wallet_id", request.getFrom(),
            "to_wallet_id", request.getTo(),
            "amount_paise", request.getAmountPaise(),
            "idempotency_key", request.getIdempotencyKey());

        try {
            // Try to insert new transfer
            Transfer transfer = new Transfer();
            transfer.setId(UUID.randomUUID());
            transfer.setIdempotencyKey(request.getIdempotencyKey());
            transfer.setFromWalletId(request.getFrom());
            transfer.setToWalletId(request.getTo());
            transfer.setAmountPaise(request.getAmountPaise());
            transfer.setStatus("PENDING");

            transferRepository.save(transfer);
            log.info("Transfer created",
                "transfer_id", transfer.getId(),
                "from_wallet_id", request.getFrom(),
                "to_wallet_id", request.getTo(),
                "amount_paise", request.getAmountPaise());
            metrics.recordTransferCreated();

            // New transfer, proceed with debit/credit
            return executeTransfer(transfer, request, startTime);

        } catch (DataIntegrityViolationException e) {
            // Idempotency key already exists
            log.info("Idempotency key already exists: {}", request.getIdempotencyKey());
            Transfer existing = transferRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .orElseThrow(() -> {
                    log.error("Transfer not found after constraint violation for idempotency key: {}",
                        request.getIdempotencyKey());
                    return new RuntimeException("Transfer not found");
                });

            // Verify body matches
            if (!existing.getFromWalletId().equals(request.getFrom()) ||
                !existing.getToWalletId().equals(request.getTo()) ||
                !existing.getAmountPaise().equals(request.getAmountPaise())) {
                log.warn("Idempotency key conflict: same key, different body",
                    "idempotency_key", request.getIdempotencyKey());
                throw new IdempotencyConflictException("Same idempotency key with different request body");
            }

            // Same body, return existing result
            log.info("Transfer idempotent replay",
                "transfer_id", existing.getId(),
                "idempotency_key", request.getIdempotencyKey());
            metrics.recordIdempotentReplay();
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordTransferLatency(duration);
            return TransferResponse.from(existing);
        }
    }

    private TransferResponse executeTransfer(Transfer transfer, TransferRequest request, long startTime) {
        // Debit source wallet (atomic conditional UPDATE)
        int debitRows = walletRepository.debit(request.getFrom(), request.getAmountPaise());

        if (debitRows == 0) {
            // Insufficient funds
            transfer.setStatus("DECLINED");
            transfer.setReason("INSUFFICIENT_FUNDS");
            transferRepository.save(transfer);
            log.info("Transfer declined",
                "transfer_id", transfer.getId(),
                "reason", "INSUFFICIENT_FUNDS",
                "from_wallet_id", request.getFrom(),
                "amount_paise", request.getAmountPaise());
            metrics.recordTransferDeclined();
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordTransferLatency(duration);
            return TransferResponse.from(transfer);
        }

        // Debit succeeded, credit destination
        walletRepository.credit(request.getTo(), request.getAmountPaise());
        log.info("Transfer debited",
            "transfer_id", transfer.getId(),
            "from_wallet_id", request.getFrom(),
            "amount_paise", request.getAmountPaise());

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

        log.info("Transfer credited",
            "transfer_id", transfer.getId(),
            "to_wallet_id", request.getTo(),
            "amount_paise", request.getAmountPaise());

        // Mark transfer as completed
        transfer.setStatus("COMPLETED");
        transferRepository.save(transfer);

        log.info("Transfer completed",
            "transfer_id", transfer.getId(),
            "from_wallet_id", request.getFrom(),
            "to_wallet_id", request.getTo(),
            "amount_paise", request.getAmountPaise());

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
