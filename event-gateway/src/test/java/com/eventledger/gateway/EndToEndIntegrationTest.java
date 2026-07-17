package com.eventledger.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eventledger.account.AccountServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TRUE end-to-end integration: the REAL Account Service application is booted
 * in this JVM on a random port, the Gateway (also real, random port) is
 * pointed at it, and requests flow over genuine HTTP between two Spring
 * contexts with two separate H2 databases. Nothing is mocked.
 *
 * The account-service dependency is TEST scope only: production artifacts
 * remain fully independent; only this test module knows both exist.
 *
 * Covers the assignment's integration requirements:
 *  - full Gateway -> Account Service flow (submit, apply, balance)
 *  - idempotency holding END-TO-END across both services
 *  - trace ID propagation verified in the Account Service's OWN logs
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndIntegrationTest {

    /** The real Account Service, started before the Gateway context resolves its base-url. */
    private static final ServletWebServerApplicationContext ACCOUNT_APP =
            (ServletWebServerApplicationContext) new SpringApplicationBuilder(AccountServiceApplication.class)
                    .run("--server.port=0",
                            "--spring.application.name=account-service",
                            "--spring.datasource.url=jdbc:h2:mem:accountdb-e2e;DB_CLOSE_DELAY=-1");
    @DynamicPropertySource
    static void pointGatewayAtRealAccountService(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url",
                () -> "http://localhost:" + ACCOUNT_APP.getWebServer().getPort());
    }

    @AfterAll
    static void shutDownAccountService() {
        ACCOUNT_APP.close();
    }

    @Autowired
    private TestRestTemplate gateway; // targets the Gateway's random port

    private String accountServiceUrl(String path) {
        return "http://localhost:" + ACCOUNT_APP.getWebServer().getPort() + path;
    }

    private HttpEntity<String> jsonEntity(String body, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }
        return new HttpEntity<>(body, headers);
    }

    private String eventJson(String eventId, String accountId, String type,
                             String amount, String timestamp) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s"
                }
                """.formatted(eventId, accountId, type, amount, timestamp);
    }

    @Test
    @DisplayName("Full flow: events submitted to the Gateway move real money in the real Account Service")
    void fullFlowAcrossBothServices() {
        ResponseEntity<String> created = gateway.postForEntity("/events",
                jsonEntity(eventJson("e2e-1", "acct-e2e", "CREDIT", "200.00",
                        "2026-05-15T14:00:00Z"), null), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("\"status\":\"APPLIED\"");

        gateway.postForEntity("/events",
                jsonEntity(eventJson("e2e-2", "acct-e2e", "DEBIT", "75.00",
                        "2026-05-15T15:00:00Z"), null), String.class);

        // read the balance DIRECTLY from the Account Service — its own DB, its own HTTP port
        ResponseEntity<String> balance = new TestRestTemplate()
                .getForEntity(accountServiceUrl("/accounts/acct-e2e/balance"), String.class);
        assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balance.getBody()).contains("125.0"); // 200 - 75

        // idempotency END-TO-END: resubmit the credit, balance must not move
        ResponseEntity<String> dup = gateway.postForEntity("/events",
                jsonEntity(eventJson("e2e-1", "acct-e2e", "CREDIT", "200.00",
                        "2026-05-15T14:00:00Z"), null), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> balanceAfterDup = new TestRestTemplate()
                .getForEntity(accountServiceUrl("/accounts/acct-e2e/balance"), String.class);
        assertThat(balanceAfterDup.getBody()).contains("125.0"); // unchanged
    }

    @Test
    @DisplayName("A trace ID entering the Gateway appears in the Account Service's own log events")
    void traceIdPropagatesIntoAccountServiceLogs() {
        // Attach an in-memory appender to the account service's loggers.
        // Both apps share one JVM (and thus one Logback context), so we can
        // observe the REAL log events the Account Service emits.
        Logger accountLogger = (Logger) LoggerFactory.getLogger("com.eventledger.account");
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        accountLogger.addAppender(captured);
        try {
            String traceId = "e2e-trace-42";
            ResponseEntity<String> response = gateway.postForEntity("/events",
                    jsonEntity(eventJson("e2e-trace-evt", "acct-trace", "CREDIT", "10.00",
                            "2026-05-15T14:00:00Z"), traceId), String.class);

            // the Gateway echoed the caller's trace id
            assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo(traceId);

            // and the Account Service logged under the SAME trace id:
            // proof the header crossed the service boundary and entered its MDC
            assertThat(captured.list)
                    .anySatisfy(event -> {
                        assertThat(event.getMDCPropertyMap()).containsEntry("traceId", traceId);
                        assertThat(event.getFormattedMessage()).contains("e2e-trace-evt");
                    });
        } finally {
            accountLogger.detachAppender(captured);
        }
    }
}
