package com.eventledger.gateway.client;

/**
 * The Account Service reported that this eventId was already applied with
 * DIFFERENT transaction details (its 409). With both services enforcing the
 * same constraint this should not occur in normal operation; surfacing it
 * loudly (rather than swallowing it) is deliberate — it would indicate the
 * two stores have diverged.
 */
public class AccountServiceConflictException extends RuntimeException {

    public AccountServiceConflictException(String eventId) {
        super("Account Service reports conflicting transaction already applied for event '"
                + eventId + "'");
    }
}
