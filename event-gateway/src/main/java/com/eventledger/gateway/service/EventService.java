package com.eventledger.gateway.service;

import com.eventledger.gateway.api.dto.EventRequest;
import com.eventledger.gateway.api.error.EventConflictException;
import com.eventledger.gateway.api.error.EventNotFoundException;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventType;
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

    public EventService(EventRepository repository) {
        this.repository = repository;
    }

    /**
     * Idempotent submission.
     *
     * Strategy: INSERT first and let the UNIQUE constraint on event_id decide.
     * If the insert violates the constraint, the event already exists — we then
     * compare payloads to distinguish a harmless retry (return the original)
     * from a genuine conflict (409).
     *
     * Deliberately NOT annotated @Transactional: the save must run (and flush)
     * in its own transaction so that, when the constraint violation occurs, the
     * follow-up SELECT executes in a fresh, healthy transaction. Wrapping both
     * in one transaction would leave the persistence context in a rollback-only
     * state after the failed insert, breaking the recovery query.
     */
    public SubmissionResult submit(EventRequest request) {
        try {
            Event saved = repository.save(newEventFrom(request));
            log.info("Stored new event eventId={} accountId={}", saved.getEventId(), saved.getAccountId());
            return new SubmissionResult(saved, true);
        } catch (DataIntegrityViolationException duplicate) {
            Event existing = repository.findByEventId(request.eventId())
                    .orElseThrow(() -> duplicate); // constraint fired but row invisible: rethrow original

            if (!existing.matchesPayload(request)) {
                log.warn("Conflicting resubmission for eventId={}", request.eventId());
                throw new EventConflictException(request.eventId());
            }
            log.info("Duplicate submission for eventId={}, returning original", request.eventId());
            return new SubmissionResult(existing, false);
        }
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
