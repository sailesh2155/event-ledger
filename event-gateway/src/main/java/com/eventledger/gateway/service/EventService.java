package com.eventledger.gateway.service;

import com.eventledger.gateway.api.dto.EventRequest;
import com.eventledger.gateway.api.error.EventConflictException;
import com.eventledger.gateway.api.error.EventNotFoundException;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.ApplyTransactionRequest;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.metrics.EventMetrics;
import com.eventledger.gateway.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository repository;
    private final AccountServiceClient accountServiceClient;
    private final EventMetrics metrics;

    public EventService(EventRepository repository,
                        AccountServiceClient accountServiceClient,
                        EventMetrics metrics) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
        this.metrics = metrics;
    }

    /**
     * Full submission flow — the dual-write problem handled explicitly:
     *
     *   1. Store the event locally (status PENDING). The unique constraint on
     *      eventId arbitrates duplicates, race-safely.
     *   2. Call the Account Service to apply the transaction.
     *   3. On success, mark the event APPLIED.
     *
     * If step 2 fails, the event REMAINS stored as PENDING and the client
     * receives a 503. Resubmitting the same event later is safe and is the
     * designed recovery path: the duplicate branch below retries the
     * downstream call for PENDING events. Because the Account Service is
     * itself idempotent on eventId, this retry can never double-apply — even
     * in the lost-response case where the transaction WAS applied but the
     * Gateway never learned of it.
     *
     * Not @Transactional, deliberately, for two reasons: (a) the recovery
     * SELECT after a constraint violation needs a fresh transaction, and
     * (b) an HTTP call must never run inside a database transaction — it
     * would hold a DB connection hostage for the duration of network I/O.
     */
    public SubmissionResult submit(EventRequest request) {
        metrics.markReceived();
        try {
            Event saved = repository.save(newEventFrom(request));
            log.info("Stored new event eventId={} accountId={}",
                    saved.getEventId(), saved.getAccountId());
            applyDownstream(saved);
            return new SubmissionResult(saved, true);
        } catch (DataIntegrityViolationException duplicate) {
            Event existing = repository.findByEventId(request.eventId())
                    .orElseThrow(() -> duplicate);

            if (!existing.matchesPayload(request)) {
                log.warn("Conflicting resubmission for eventId={}", request.eventId());
                throw new EventConflictException(request.eventId());
            }

            if (existing.getStatus() == EventStatus.PENDING) {
                // Earlier attempt never confirmed downstream — this retry is
                // the recovery path, and idempotency makes it harmless.
                log.info("Duplicate submission for PENDING eventId={}, retrying downstream apply",
                        request.eventId());
                applyDownstream(existing);
            } else {
                log.info("Duplicate submission for eventId={}, returning original",
                        request.eventId());
            }
            metrics.markDuplicate();
            return new SubmissionResult(existing, false);
        }
    }

    private void applyDownstream(Event event) {
        metrics.timeDownstreamCall(() ->
                accountServiceClient.applyTransaction(event.getAccountId(), new ApplyTransactionRequest(
                        event.getEventId(),
                        event.getType().name(),
                        event.getAmount(),
                        event.getCurrency(),
                        event.getEventTimestamp()
                )));
        event.markApplied();
        repository.save(event);
        metrics.markApplied();
    }

    public Event getByEventId(String eventId) {
        return repository.findByEventId(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    public List<Event> listByAccount(String accountId) {
        return repository.findByAccountIdOrderByEventTimestampAsc(accountId);
    }

    private Event newEventFrom(EventRequest request) {
        return new Event(
                request.eventId(),
                request.accountId(),
                EventType.valueOf(request.type()),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                request.metadata()
        );
    }

    /** Result of a submission: the stored event, and whether this call created it. */
    public record SubmissionResult(Event event, boolean created) {
    }
}
