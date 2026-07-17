package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.ApplyTransactionRequest;
import com.eventledger.gateway.trace.TraceIdFilter;
import com.eventledger.gateway.trace.TraceIdPropagationInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TracePropagationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountServiceClient accountServiceClient; // keep web tests off the network

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Every response carries an X-Trace-Id, generated when the caller sent none")
    void traceIdGeneratedWhenAbsent() throws Exception {
        mockMvc.perform(get("/events").param("account", "acct-any"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceIdFilter.TRACE_HEADER));
    }

    @Test
    @DisplayName("A caller-supplied X-Trace-Id is adopted, not replaced")
    void suppliedTraceIdIsPreserved() throws Exception {
        mockMvc.perform(get("/events")
                        .param("account", "acct-any")
                        .header(TraceIdFilter.TRACE_HEADER, "caller-trace-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_HEADER, "caller-trace-123"));
    }

    @Test
    @DisplayName("Outbound calls to the Account Service carry the current trace ID header")
    void outboundCallCarriesTraceHeader() {
        // Build a real client wired with the propagation interceptor,
        // bound to a mock server that ASSERTS on the header.
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://account-service")
                .requestInterceptor(new TraceIdPropagationInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountServiceClient client = new AccountServiceClient(builder.build());

        server.expect(requestTo("http://account-service/accounts/acct-1/transactions"))
                .andExpect(header(TraceIdFilter.TRACE_HEADER, "trace-abc"))
                .andRespond(withSuccess());

        MDC.put(TraceIdFilter.MDC_KEY, "trace-abc");
        client.applyTransaction("acct-1", new ApplyTransactionRequest(
                "evt-t1", "CREDIT", new BigDecimal("10.00"), "USD",
                Instant.parse("2026-05-15T14:00:00Z")));

        server.verify(); // fails the test if the header was missing or wrong
    }
}
