package com.eventledger.gateway.repository;

import com.eventledger.gateway.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByEventId(String eventId);

    /**
     * Chronological by when the event happened (eventTimestamp), NOT by when it
     * arrived — this is what makes out-of-order arrival invisible to readers.
     */
    List<Event> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
