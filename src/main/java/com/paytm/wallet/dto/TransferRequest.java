package com.paytm.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequest {
    private UUID from;
    private UUID to;
    private Long amountPaise;
    private UUID idempotencyKey;
}
