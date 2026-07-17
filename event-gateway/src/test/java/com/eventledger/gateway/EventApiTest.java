package com.eventledger.gateway;

import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EventApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    // ---------- helpers ----------

    private String eventJson(String eventId, String accountId, String type,
                             String amount, String timestamp) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s",
                  "metadata": {"source": "test"}
                }
                """.formatted(eventId, accountId, type, amount, timestamp);
    }

    private MvcResult postEvent(String json) throws Exception {
        return mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    // ---------- core functionality ----------

    @Test
    @DisplayName("POST /events stores a new event and returns 201 with status PENDING")
    void createEvent() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-001", "acct-123", "CREDIT", "150.00",
                                "2026-05-15T14:02:11Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate submission returns 200 with the original event and does not create a second row")
    void duplicateSubmissionIsIdempotent() throws Exception {
        String json = eventJson("evt-dup", "acct-123", "CREDIT", "150.00", "2026-05-15T14:02:11Z");

        postEvent(json); // first: 201
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-dup"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Same eventId with a different payload returns 409 Conflict")
    void conflictingResubmission() throws Exception {
        postEvent(eventJson("evt-conflict", "acct-123", "CREDIT", "150.00", "2026-05-15T14:02:11Z"));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-conflict", "acct-123", "CREDIT", "999.00",
                                "2026-05-15T14:02:11Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("different payload")));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent duplicate submissions result in exactly one stored event (race safety)")
    void concurrentDuplicatesStoreExactlyOneEvent() throws Exception {
        String json = eventJson("evt-race", "acct-123", "CREDIT", "150.00", "2026-05-15T14:02:11Z");
        int threads = 8;

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> postEvent(json).getResponse().getStatus());
            }
            List<Future<Integer>> results = pool.invokeAll(tasks);

            for (Future<Integer> result : results) {
                // every response is a success (201 or 200) — never a 5xx
                assertThat(result.get()).isIn(200, 201);
            }
        }

        assertThat(repository.count()).isEqualTo(1);
    }

    // ---------- out-of-order tolerance ----------

    @Test
    @DisplayName("GET /events?account= lists events in eventTimestamp order regardless of arrival order")
    void listingIsChronologicalNotArrivalOrder() throws Exception {
        // arrival order: 3rd, 1st, 2nd (by business time)
        postEvent(eventJson("evt-c", "acct-9", "CREDIT", "30.00", "2026-05-15T14:03:00Z"));
        postEvent(eventJson("evt-a", "acct-9", "CREDIT", "10.00", "2026-05-15T14:01:00Z"));
        postEvent(eventJson("evt-b", "acct-9", "DEBIT", "20.00", "2026-05-15T14:02:00Z"));

        mockMvc.perform(get("/events").param("account", "acct-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-a"))
                .andExpect(jsonPath("$[1].eventId").value("evt-b"))
                .andExpect(jsonPath("$[2].eventId").value("evt-c"));
    }

    // ---------- retrieval ----------

    @Test
    @DisplayName("GET /events/{id} returns the event; unknown id returns 404")
    void getById() throws Exception {
        postEvent(eventJson("evt-get", "acct-1", "DEBIT", "42.00", "2026-05-15T14:02:11Z"));

        mockMvc.perform(get("/events/evt-get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"));

        mockMvc.perform(get("/events/evt-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("evt-nope")));
    }

    // ---------- validation ----------

    @Test
    @DisplayName("Missing required fields returns 400 with one message per field")
    void missingFieldsRejected() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messages.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
    }

    @Test
    @DisplayName("Zero or negative amounts are rejected with 400")
    void nonPositiveAmountRejected() throws Exception {
        postExpectingBadRequest(eventJson("evt-z", "acct-1", "CREDIT", "0", "2026-05-15T14:02:11Z"));
        postExpectingBadRequest(eventJson("evt-n", "acct-1", "CREDIT", "-5.00", "2026-05-15T14:02:11Z"));
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("Unknown event type is rejected with a precise message")
    void unknownTypeRejected() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-t", "acct-1", "TRANSFER", "10.00",
                                "2026-05-15T14:02:11Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("CREDIT or DEBIT")));
    }

    @Test
    @DisplayName("Malformed timestamp is rejected with 400")
    void malformedTimestampRejected() throws Exception {
        postExpectingBadRequest(eventJson("evt-ts", "acct-1", "CREDIT", "10.00", "yesterday"));
    }

    private void postExpectingBadRequest(String json) throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }
}
