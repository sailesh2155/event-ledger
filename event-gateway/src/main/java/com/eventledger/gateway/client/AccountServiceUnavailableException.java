package com.eventledger.gateway.client;

/**
 * The Account Service could not be reached or did not respond usefully
 * (connection refused, timeout, 5xx). The event remains stored as PENDING and
 * resubmitting the same event later is SAFE: both services are idempotent on
 * eventId, so a retry can never double-apply.
 */
public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String detail, Throwable cause) {
        super("Account Service unavailable: " + detail, cause);
    }

    public AccountServiceUnavailableException(String detail) {
        this(detail, null);
    }
}
