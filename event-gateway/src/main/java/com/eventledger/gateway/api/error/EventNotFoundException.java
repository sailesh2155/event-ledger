package com.eventledger.gateway.api.error;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String eventId) {
        super("No event found with eventId '" + eventId + "'");
    }
}
