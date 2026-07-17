package com.eventledger.gateway.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The Gateway's ONLY integration point with the Account Service. All knowledge
 * of the downstream API (paths, payloads, error mapping) lives here, behind a
 * simple method — the rest of the Gateway neither knows nor cares that HTTP is
 * involved. This is also the single seam where the circuit breaker wraps, and
 * where tests substitute a mock.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    public static final String CIRCUIT_BREAKER = "accountService";

    private final RestClient restClient;

    public AccountServiceClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    /**
     * Applies a transaction downstream, guarded by a circuit breaker.
     *
     * Resiliency pattern choice — circuit breaker + timeouts, deliberately
     * WITHOUT automatic retry:
     *  - Timeouts (on the RestClient) bound how long any single call can hold
     *    a Gateway thread.
     *  - The circuit breaker watches the failure rate; when the Account
     *    Service is clearly down it OPENS and calls fail immediately
     *    (CallNotPermittedException) instead of each request burning a full
     *    timeout. This protects Gateway threads AND gives the struggling
     *    downstream room to recover instead of hammering it.
     *  - No automatic retry: an automatic retry storm is exactly what a
     *    recovering service does not need, and the client already has a safe,
     *    explicit retry path (resubmitting the same event is idempotent
     *    end-to-end). We prefer the caller deciding to retry over the
     *    Gateway silently multiplying traffic.
     *
     * Outcomes:
     * - 2xx: applied (or idempotently re-applied) — returns normally
     * - 409: conflicting transaction already exists -> AccountServiceConflictException
     * - breaker OPEN: fails fast -> CallNotPermittedException (mapped to 503)
     * - anything else (connect refused, timeout, 5xx, unexpected 4xx):
     *   -> AccountServiceUnavailableException; the caller leaves the event
     *   PENDING and the client may safely retry later.
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER)
    public void applyTransaction(String accountId, ApplyTransactionRequest request) {
        try {
            restClient.post()
                    .uri("/accounts/{accountId}/transactions", accountId)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.value() == 409, (req, res) -> {
                        throw new AccountServiceConflictException(request.eventId());
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new AccountServiceUnavailableException(
                                "unexpected response " + res.getStatusCode()
                                        + " for event " + request.eventId());
                    })
                    .toBodilessEntity();
            log.info("Applied transaction downstream eventId={} accountId={}",
                    request.eventId(), accountId);
        } catch (AccountServiceConflictException | AccountServiceUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // connection refused, DNS failure, connect/read timeout
            log.warn("Account Service unreachable for eventId={}: {}",
                    request.eventId(), e.getMessage());
            throw new AccountServiceUnavailableException("cannot reach service", e);
        } catch (RestClientException e) {
            log.warn("Account Service call failed for eventId={}: {}",
                    request.eventId(), e.getMessage());
            throw new AccountServiceUnavailableException(e.getMessage(), e);
        }
    }
}
