package com.eventledger.gateway.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Inbound payload for POST /events.
 *
 * `type` is deliberately a String (not the enum): binding an enum makes Jackson
 * fail deserialization on unknown values, which produces a generic "malformed
 * request" error. Validating the raw string lets us return a precise,
 * field-level message ("type: must be CREDIT or DEBIT") instead.
 */
public record EventRequest(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "accountId is required")
        String accountId,

        @NotNull(message = "type is required")
        @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
        String type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0", inclusive = false, message = "amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code, e.g. USD")
        String currency,

        @NotNull(message = "eventTimestamp is required (ISO 8601, e.g. 2026-05-15T14:02:11Z)")
        Instant eventTimestamp,

        Map<String, Object> metadata
) {
}
