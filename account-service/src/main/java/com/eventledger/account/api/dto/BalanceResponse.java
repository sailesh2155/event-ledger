package com.eventledger.account.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(
        String accountId,
        BigDecimal balance,
        long transactionCount,
        Instant asOf
) {
}
