package com.paytm.wallet.dto;

import com.paytm.wallet.entity.Wallet;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {
    private UUID id;
    private String userId;
    private Long balancePaise;
    private BigDecimal balanceRupees;
    private LocalDateTime createdAt;

    public static WalletResponse from(Wallet wallet) {
        WalletResponse response = new WalletResponse();
        response.setId(wallet.getId());
        response.setUserId(wallet.getUserId());
        response.setBalancePaise(wallet.getBalancePaise());
        // Convert paise to rupees for display (paise / 100.0)
        response.setBalanceRupees(BigDecimal.valueOf(wallet.getBalancePaise())
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        response.setCreatedAt(wallet.getCreatedAt());
        return response;
    }
}
