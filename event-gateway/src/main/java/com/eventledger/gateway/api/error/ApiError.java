package com.eventledger.gateway.api.error;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error shape across the API, so clients parse one structure
 * regardless of the failure mode.
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
