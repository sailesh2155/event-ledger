package com.eventledger.account.api.error;

/**
 * Same eventId submitted with different transaction details. A replay of an
 * identical transaction is harmless and returns the original; a conflicting
 * reuse of an eventId is an upstream bug that must surface as 409.
 */
public class TransactionConflictException extends RuntimeException {

    public TransactionConflictException(String eventId) {
        super("Transaction for event '" + eventId + "' already applied with different details");
    }
}
