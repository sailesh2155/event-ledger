package com.eventledger.gateway.api.dto;

import com.eventledger.gateway.domain.Event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Outbound representation of an event. A dedicated DTO (rather than exposing
 * the JPA entity) keeps the API contract stable even if persistence changes.
 */
public record EventResponse(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        String status,
        Instant receivedAt
) {

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getEventId(),
                event.getAccountId(),
                event.getType().name(),
                event.getAmount(),
                event.getCurrency(),
                event.getEventTimestamp(),
                event.getMetadata(),
                event.getStatus().name(),
                event.getReceivedAt()
        );
    }
}
