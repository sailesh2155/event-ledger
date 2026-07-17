package com.eventledger.gateway.domain;

/**
 * Lifecycle of an event inside the Gateway.
 *
 * PENDING  – stored locally, not yet applied to the Account Service.
 * APPLIED  – successfully applied to the Account Service (set in a later step
 *            of the flow, once the Gateway -> Account Service call exists).
 * FAILED   – the Account Service rejected or could not process the event.
 */
public enum EventStatus {
    PENDING,
    APPLIED,
    FAILED
}
