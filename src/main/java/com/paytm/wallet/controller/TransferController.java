package com.paytm.wallet.controller;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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
            TransferResponse response = transferService.transfer(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(response);
        } catch (IdempotencyConflictException e) {
            log.warn("Idempotency conflict: {}", e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "IDEMPOTENCY_KEY_CONFLICT");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorResponse);
        }
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
