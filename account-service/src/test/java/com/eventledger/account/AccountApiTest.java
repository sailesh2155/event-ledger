package com.eventledger.account;

import com.eventledger.account.repository.TransactionRepository;
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
class AccountApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    // ---------- helpers ----------

    private String txnJson(String eventId, String type, String amount, String timestamp) {
        return """
                {
                  "eventId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s"
                }
                """.formatted(eventId, type, amount, timestamp);
    }

    private MvcResult apply(String accountId, String json) throws Exception {
        return mockMvc.perform(post("/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    // ---------- idempotent application ----------

    @Test
    @DisplayName("Applying a transaction returns 201 and affects the balance")
    void applyTransaction() throws Exception {
        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txnJson("evt-1", "CREDIT", "150.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-1"));

        mockMvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150.00));
    }

    @Test
    @DisplayName("Replaying the same eventId returns 200 and does NOT change the balance")
    void replayDoesNotDoubleApply() throws Exception {
        String json = txnJson("evt-replay", "CREDIT", "100.00", "2026-05-15T14:00:00Z");

        apply("acct-2", json); // 201
        mockMvc.perform(post("/accounts/acct-2/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        assertThat(repository.count()).isEqualTo(1);
        mockMvc.perform(get("/accounts/acct-2/balance"))
                .andExpect(jsonPath("$.balance").value(100.00)); // NOT 200.00
    }

    @Test
    @DisplayName("Same eventId with different details returns 409")
    void conflictingReplayRejected() throws Exception {
        apply("acct-3", txnJson("evt-c", "CREDIT", "100.00", "2026-05-15T14:00:00Z"));

        mockMvc.perform(post("/accounts/acct-3/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txnJson("evt-c", "CREDIT", "999.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isConflict());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent replays of the same transaction apply exactly once")
    void concurrentReplaysApplyOnce() throws Exception {
        String json = txnJson("evt-race", "CREDIT", "50.00", "2026-05-15T14:00:00Z");
        int threads = 8;

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> apply("acct-race", json).getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isIn(200, 201);
            }
        }

        assertThat(repository.count()).isEqualTo(1);
        mockMvc.perform(get("/accounts/acct-race/balance"))
                .andExpect(jsonPath("$.balance").value(50.00));
    }

    // ---------- balance math ----------

    @Test
    @DisplayName("Balance = sum of credits minus sum of debits")
    void balanceMath() throws Exception {
        apply("acct-m", txnJson("evt-m1", "CREDIT", "200.00", "2026-05-15T14:00:00Z"));
        apply("acct-m", txnJson("evt-m2", "DEBIT", "75.50", "2026-05-15T14:01:00Z"));
        apply("acct-m", txnJson("evt-m3", "CREDIT", "25.50", "2026-05-15T14:02:00Z"));

        mockMvc.perform(get("/accounts/acct-m/balance"))
                .andExpect(jsonPath("$.balance").value(150.00))
                .andExpect(jsonPath("$.transactionCount").value(3));
    }

    @Test
    @DisplayName("Balance is identical regardless of the order transactions arrive in")
    void balanceIsOrderIndependent() throws Exception {
        // same three transactions as balanceMath, applied in reverse order
        apply("acct-o", txnJson("evt-o3", "CREDIT", "25.50", "2026-05-15T14:02:00Z"));
        apply("acct-o", txnJson("evt-o2", "DEBIT", "75.50", "2026-05-15T14:01:00Z"));
        apply("acct-o", txnJson("evt-o1", "CREDIT", "200.00", "2026-05-15T14:00:00Z"));

        mockMvc.perform(get("/accounts/acct-o/balance"))
                .andExpect(jsonPath("$.balance").value(150.00));
    }

    @Test
    @DisplayName("Balance can go negative (net debits) — spec defines balance as a pure sum")
    void balanceCanGoNegative() throws Exception {
        apply("acct-neg", txnJson("evt-n1", "DEBIT", "100.00", "2026-05-15T14:00:00Z"));

        mockMvc.perform(get("/accounts/acct-neg/balance"))
                .andExpect(jsonPath("$.balance").value(-100.00));
    }

    // ---------- account details ----------

    @Test
    @DisplayName("Account details include balance, count and recent transactions (newest first)")
    void accountDetails() throws Exception {
        apply("acct-d", txnJson("evt-d1", "CREDIT", "10.00", "2026-05-15T14:00:00Z"));
        apply("acct-d", txnJson("evt-d2", "CREDIT", "20.00", "2026-05-15T15:00:00Z"));

        mockMvc.perform(get("/accounts/acct-d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(30.00))
                .andExpect(jsonPath("$.transactionCount").value(2))
                .andExpect(jsonPath("$.recentTransactions[0].eventId").value("evt-d2"));
    }

    @Test
    @DisplayName("Unknown account returns 404 for balance and details")
    void unknownAccount() throws Exception {
        mockMvc.perform(get("/accounts/acct-ghost/balance"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/accounts/acct-ghost"))
                .andExpect(status().isNotFound());
    }

    // ---------- validation ----------

    @Test
    @DisplayName("Invalid transactions are rejected with 400 and field messages")
    void validation() throws Exception {
        mockMvc.perform(post("/accounts/acct-v/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(4)));

        mockMvc.perform(post("/accounts/acct-v/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txnJson("evt-v", "TRANSFER", "10.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/accounts/acct-v/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txnJson("evt-v2", "DEBIT", "-5.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }
}
