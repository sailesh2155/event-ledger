package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.repository.EventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resiliency behavior with a REAL (not mocked) client: the Account Service
 * base URL points at a port where nothing listens, so every downstream call
 * genuinely fails with connection-refused. This simulates the Account Service
 * being down, as the assignment requires, and verifies:
 *
 *  1. POST /events degrades to 503 (never a hang, never a raw 500)
 *  2. after enough failures the circuit breaker OPENS
 *  3. with the breaker open, calls fail fast (no timeout burned)
 *  4. events remain stored as PENDING throughout
 *  5. reads keep working the whole time
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // nothing listens here: every call is a genuine connection failure
        "account-service.base-url=http://localhost:59999",
        "account-service.connect-timeout-ms=200",
        "account-service.read-timeout-ms=200"
})
class ResiliencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository repository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker breaker;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        breaker = circuitBreakerRegistry.circuitBreaker(AccountServiceClient.CIRCUIT_BREAKER);
        breaker.reset();
    }

    private String eventJson(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "acct-cb",
                  "type": "CREDIT",
                  "amount": 10.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T14:02:11Z"
                }
                """.formatted(eventId);
    }

    @Test
    @DisplayName("Account Service down: 503s, breaker opens, calls fail fast, events stay PENDING, reads keep working")
    void breakerOpensUnderRepeatedFailure() throws Exception {
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Enough failing submissions to cross the threshold
        // (window 10, min calls 5, failure rate 50%)
        for (int i = 1; i <= 6; i++) {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(eventJson("evt-cb-" + i)))
                    .andExpect(status().isServiceUnavailable());
        }

        // 1) the breaker has opened
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 2) with the breaker open, the next call fails FAST (no connection
        //    attempt): still a clean 503, now mentioning the breaker
        long before = System.currentTimeMillis();
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-cb-fast")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("circuit breaker")));
        long elapsed = System.currentTimeMillis() - before;
        assertThat(elapsed).isLessThan(150); // far below even the 200ms connect timeout

        // 3) every event was stored despite failures, all PENDING
        assertThat(repository.count()).isEqualTo(7);
        assertThat(repository.findByEventId("evt-cb-3").orElseThrow().getStatus().name())
                .isEqualTo("PENDING");

        // 4) graceful degradation: reads work with downstream dead AND breaker open
        mockMvc.perform(get("/events/evt-cb-1")).andExpect(status().isOk());
        mockMvc.perform(get("/events").param("account", "acct-cb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    @DisplayName("Breaker transitions to HALF_OPEN and closes again once the downstream recovers")
    void breakerRecoversViaHalfOpen() {
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        // simulate successful trial calls (as if the downstream recovered)
        for (int i = 0; i < 3; i++) {
            breaker.onSuccess(10, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
