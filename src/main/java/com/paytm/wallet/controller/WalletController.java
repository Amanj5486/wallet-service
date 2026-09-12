package com.paytm.wallet.controller;

import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.entity.Wallet;
import com.paytm.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
@Slf4j
public class WalletController {
    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        log.info("Creating wallet for user: {}", userId);

        Wallet wallet = walletService.getOrCreate(userId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        log.info("Getting wallet: {} for user: {}", id, userId);

        Wallet wallet = walletService.getWallet(id);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    @PostMapping("/{id}/fund")
    public ResponseEntity<WalletResponse> fundWallet(
            @PathVariable UUID id,
            @RequestParam Long amountPaise,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        log.info("Funding wallet: {} with {} paise for user: {}", id, amountPaise, userId);

        Wallet wallet = walletService.fundWallet(id, amountPaise);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }
}
