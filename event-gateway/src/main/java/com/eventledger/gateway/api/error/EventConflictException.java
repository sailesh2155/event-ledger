package com.eventledger.gateway.api.error;

/**
 * Same eventId submitted with a DIFFERENT payload. This is not an idempotent
 * retry (which would be harmless) but a contradiction the client must resolve,
 * hence 409 Conflict rather than silently returning the stored event.
 */
public class EventConflictException extends RuntimeException {

    public EventConflictException(String eventId) {
        super("Event '" + eventId + "' already exists with a different payload");
    }
}
