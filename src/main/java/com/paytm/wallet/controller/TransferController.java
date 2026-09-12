package com.paytm.wallet.controller;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/transfers")
@Slf4j
public class TransferController {
    private static final int MAX_RETRIES = 3;

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<?> createTransfer(
            @RequestBody TransferRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        log.info("Creating transfer: from_wallet_id={}, to_wallet_id={}, amount_paise={}, idempotency_key={}, user_id={}",
            request.getFrom(), request.getTo(), request.getAmountPaise(), request.getIdempotencyKey(), userId);

        try {
            return executeWithRetry(request);
        } catch (IdempotencyConflictException e) {
            log.warn("Idempotency conflict: {}", e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "IDEMPOTENCY_KEY_CONFLICT");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
        } catch (CannotAcquireLockException e) {
            log.error("Transfer FAILED after {} retries due to deadlock: from_wallet_id={}, to_wallet_id={}, amount_paise={}, idempotency_key={}",
                MAX_RETRIES, request.getFrom(), request.getTo(), request.getAmountPaise(), request.getIdempotencyKey());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "DEADLOCK_RETRIES_EXHAUSTED");
            errorResponse.put("message", "Transfer failed due to contention, please retry");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
        }
    }

    // Retry loop lives OUTSIDE @Transactional (which is on transferService.transfer())
    // so each attempt gets a fresh transaction
    private ResponseEntity<?> executeWithRetry(TransferRequest request) {
        CannotAcquireLockException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                TransferResponse response = transferService.transfer(request);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            } catch (CannotAcquireLockException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) {
                    break;
                }
                long backoffMs = 50L * (1L << attempt) + java.util.concurrent.ThreadLocalRandom.current().nextLong(50); // 50-100ms, 100-150ms, 200-250ms
                log.warn("Deadlock detected, retrying transfer: attempt={}, backoff_ms={}, idempotency_key={}",
                    attempt + 1, backoffMs, request.getIdempotencyKey());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Transfer interrupted", ie);
                }
            }
        }
        throw lastException;
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        log.info("Getting transfer: transfer_id={}, user_id={}", id, userId);

        TransferResponse response = transferService.getTransfer(id);
        return ResponseEntity.ok(response);
    }
}
