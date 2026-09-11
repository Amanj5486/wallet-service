package com.paytm.wallet.dto;

import com.paytm.wallet.entity.Transfer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponse {
    private UUID id;
    private UUID from;
    private UUID to;
    private Long amountPaise;
    private String status;
    private String reason;
    private LocalDateTime createdAt;

    public static TransferResponse from(Transfer transfer) {
        TransferResponse response = new TransferResponse();
        response.setId(transfer.getId());
        response.setFrom(transfer.getFromWalletId());
        response.setTo(transfer.getToWalletId());
        response.setAmountPaise(transfer.getAmountPaise());
        response.setStatus(transfer.getStatus());
        response.setReason(transfer.getReason());
        response.setCreatedAt(transfer.getCreatedAt());
        return response;
    }
}
