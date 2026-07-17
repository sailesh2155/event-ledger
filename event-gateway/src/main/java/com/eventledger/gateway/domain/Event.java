package com.eventledger.gateway.domain;

import com.eventledger.gateway.api.dto.EventRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A transaction event as received by the Gateway.
 *
 * Idempotency is enforced structurally: the UNIQUE constraint on event_id makes
 * the database the single arbiter of "have I seen this event before?". This is
 * race-safe under concurrent duplicate submissions, unlike a check-then-insert
 * approach which has a window between the SELECT and the INSERT.
 */
@Entity
@Table(
        name = "events",
        uniqueConstraints = @UniqueConstraint(name = "uk_events_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_events_account_ts", columnList = "account_id, event_timestamp")
)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private EventType type;

    /** BigDecimal, never double/float: money must not accumulate binary floating point error. */
    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Convert(converter = MetadataConverter.class)
    @Column(length = 4000)
    private Map<String, Object> metadata;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventStatus status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected Event() {
        // JPA
    }

    public Event(String eventId,
                 String accountId,
                 EventType type,
                 BigDecimal amount,
                 String currency,
                 Instant eventTimestamp,
                 Map<String, Object> metadata) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.status = EventStatus.PENDING;
        this.receivedAt = Instant.now();
    }

    /**
     * True when a resubmitted request carries the same business payload as this
     * stored event. Same eventId + same payload = idempotent retry (harmless).
     * Same eventId + different payload = a conflict the client must resolve.
     */
    public boolean matchesPayload(EventRequest request) {
        return Objects.equals(eventId, request.eventId())
                && Objects.equals(accountId, request.accountId())
                && type == EventType.valueOf(request.type())
                && amount.compareTo(request.amount()) == 0
                && Objects.equals(currency, request.currency())
                && Objects.equals(eventTimestamp, request.eventTimestamp())
                && Objects.equals(
                        metadata == null ? Map.of() : metadata,
                        request.metadata() == null ? Map.of() : request.metadata());
    }

    public void markApplied() {
        this.status = EventStatus.APPLIED;
    }

    public void markFailed() {
        this.status = EventStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public EventType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
