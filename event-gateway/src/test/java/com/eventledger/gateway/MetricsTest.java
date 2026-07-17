package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class MetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private EventRepository repository;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        doNothing().when(accountServiceClient).applyTransaction(anyString(), any());
    }

    private String eventJson(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "acct-metrics",
                  "type": "CREDIT",
                  "amount": 10.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T14:02:11Z"
                }
                """.formatted(eventId);
    }

    private void postEvent(String json) throws Exception {
        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    @DisplayName("Counters track received, applied and duplicate events; downstream calls are timed")
    void countersAndTimerMove() throws Exception {
        // metrics are cumulative across the shared context: measure deltas
        double receivedBefore = registry.get("events.received").counter().count();
        double appliedBefore = registry.get("events.applied").counter().count();
        double duplicateBefore = registry.get("events.duplicate").counter().count();
        long timedBefore = registry.get("account.service.call.duration").timer().count();

        postEvent(eventJson("evt-m1"));   // new -> received+1, applied+1, timer+1
        postEvent(eventJson("evt-m2"));   // new -> received+1, applied+1, timer+1
        postEvent(eventJson("evt-m1"));   // duplicate of APPLIED -> received+1, duplicate+1

        assertThat(registry.get("events.received").counter().count() - receivedBefore)
                .isEqualTo(3.0);
        assertThat(registry.get("events.applied").counter().count() - appliedBefore)
                .isEqualTo(2.0);
        assertThat(registry.get("events.duplicate").counter().count() - duplicateBefore)
                .isEqualTo(1.0);
        assertThat(registry.get("account.service.call.duration").timer().count() - timedBefore)
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("Custom metrics are served by the /metrics endpoint")
    void metricsEndpointServesCustomMetrics() throws Exception {
        postEvent(eventJson("evt-m3"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/metrics/events.received"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.name").value("events.received"));
    }
}
