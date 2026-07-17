package com.eventledger.gateway.client;

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
 * involved. This is also the single seam where the circuit breaker will wrap
 * in a later step, and where tests substitute a mock.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestClient restClient;

    public AccountServiceClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    /**
     * Applies a transaction downstream. Outcomes:
     * - 2xx: applied (or idempotently re-applied) — returns normally
     * - 409: conflicting transaction already exists -> AccountServiceConflictException
     * - anything else (connect refused, timeout, 5xx, unexpected 4xx):
     *   -> AccountServiceUnavailableException; the caller leaves the event
     *   PENDING and the client may safely retry later.
     */
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
