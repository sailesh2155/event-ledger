package com.eventledger.account.api.error;

import java.time.Instant;
import java.util.List;

/**
 * Same error shape as the Gateway's ApiError. Deliberately duplicated rather
 * than extracted into a shared library: the assignment (and good microservice
 * practice at this scale) favors fully independent services over compile-time
 * coupling. The consistency is a convention of the API contract, not code.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        List<String> messages
) {

    public static ApiError of(int status, String error, List<String> messages) {
        return new ApiError(Instant.now(), status, error, messages);
    }

    public static ApiError of(int status, String error, String message) {
        return of(status, error, List.of(message));
    }
}
