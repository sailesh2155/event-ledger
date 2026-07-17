package com.eventledger.gateway.api;

import com.eventledger.gateway.api.dto.EventRequest;
import com.eventledger.gateway.api.dto.EventResponse;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 201 Created  – first time this eventId is seen.
     * 200 OK       – idempotent duplicate; body is the ORIGINAL stored event.
     * 409 Conflict – same eventId, different payload (via exception handler).
     */
    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        EventService.SubmissionResult result = eventService.submit(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(EventResponse.from(result.event()));
    }

    @GetMapping("/{eventId}")
    public EventResponse getById(@PathVariable String eventId) {
        return EventResponse.from(eventService.getByEventId(eventId));
    }

    /** Listing is ordered by eventTimestamp — chronological, not arrival order. */
    @GetMapping
    public List<EventResponse> listByAccount(@RequestParam("account") String accountId) {
        return eventService.listByAccount(accountId).stream()
                .map(EventResponse::from)
                .toList();
    }
}
