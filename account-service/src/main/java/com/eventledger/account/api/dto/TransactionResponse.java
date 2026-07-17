package com.eventledger.account.api.dto;

import com.eventledger.account.domain.AccountTransaction;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant appliedAt
) {

    public static TransactionResponse from(AccountTransaction txn) {
        return new TransactionResponse(
                txn.getEventId(),
                txn.getAccountId(),
                txn.getType().name(),
                txn.getAmount(),
                txn.getCurrency(),
                txn.getEventTimestamp(),
                txn.getAppliedAt()
        );
    }
}
