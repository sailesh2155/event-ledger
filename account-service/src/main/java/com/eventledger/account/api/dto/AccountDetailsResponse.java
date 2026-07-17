package com.eventledger.account.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record AccountDetailsResponse(
        String accountId,
        BigDecimal balance,
        long transactionCount,
        List<TransactionResponse> recentTransactions
) {
}
