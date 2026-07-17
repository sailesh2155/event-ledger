package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound payload for the Account Service's
 * POST /accounts/{accountId}/transactions endpoint.
 *
 * The eventId is passed through so idempotency holds END-TO-END: the Account
 * Service dedupes on the same identifier the Gateway does.
 */
public record ApplyTransactionRequest(
        String eventId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp
) {
}
