package com.eventledger.account.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Inbound payload for POST /accounts/{accountId}/transactions.
 * eventId is carried through from the Gateway so idempotency holds
 * end-to-end across both services.
 */
public record TransactionRequest(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotNull(message = "type is required")
        @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
        String type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0", inclusive = false, message = "amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code, e.g. USD")
        String currency,

        @NotNull(message = "eventTimestamp is required (ISO 8601)")
        Instant eventTimestamp
) {
}
