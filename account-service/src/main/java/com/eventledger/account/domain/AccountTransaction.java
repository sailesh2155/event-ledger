package com.eventledger.account.domain;

import jakarta.persistence.Column;
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
import java.util.Objects;

/**
 * A transaction applied to an account.
 *
 * The UNIQUE constraint on event_id makes applying a transaction idempotent at
 * THIS service too — not just at the Gateway. This is deliberate defense in
 * depth: the scariest distributed-systems failure here is the Gateway calling
 * this service, the transaction being applied, and the response getting lost
 * (timeout). The Gateway cannot know whether the money moved. Because replays
 * of the same eventId are harmless here, that ambiguity is harmless: any
 * retry — automated or manual — can never double-apply a transaction.
 */
@Entity
@Table(
        name = "account_transactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_txn_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_txn_account_ts", columnList = "account_id, event_timestamp")
)
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    protected AccountTransaction() {
        // JPA
    }

    public AccountTransaction(String eventId,
                              String accountId,
                              TransactionType type,
                              BigDecimal amount,
                              String currency,
                              Instant eventTimestamp) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.appliedAt = Instant.now();
    }

    /** Distinguishes a harmless replay (same content) from a conflicting reuse of an eventId. */
    public boolean matches(String accountId, TransactionType type, BigDecimal amount,
                           String currency, Instant eventTimestamp) {
        return Objects.equals(this.accountId, accountId)
                && this.type == type
                && this.amount.compareTo(amount) == 0
                && Objects.equals(this.currency, currency)
                && Objects.equals(this.eventTimestamp, eventTimestamp);
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

    public TransactionType getType() {
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

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
