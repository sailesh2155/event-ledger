# Event Ledger

Two Spring Boot microservices that ingest financial transaction events from
unreliable upstream systems — events may arrive **out of order** and may be
**delivered more than once** — and keep account balances correct anyway, while
degrading gracefully when parts of the system are down.

## Architecture

```
                          ┌─────────────────────────┐
 Browser / Client ──────▶ │  Event Gateway  :8080   │   public-facing
                          │  validates, dedupes,    │
                          │  stores events (H2)     │
                          └───────────┬─────────────┘
                                      │ REST (sync) + X-Trace-Id
                                      ▼
                          ┌─────────────────────────┐
                          │  Account Service :8081  │   internal-only
                          │  applies transactions,  │
                          │  derives balances (H2)  │
                          └─────────────────────────┘
```

**Event Gateway** (public): entry point for all clients.
- `POST /events` — submit a transaction event (validates, enforces idempotency,
  stores locally, applies to the Account Service)
- `GET /events/{eventId}` — one event, from the Gateway's local store
- `GET /events?account={accountId}` — an account's events in **eventTimestamp**
  order (chronological, regardless of arrival order)
- `GET /health` — real health incl. database connectivity
- `GET /metrics` / `GET /metrics/{name}` — Micrometer metrics

**Account Service** (internal, never exposed to clients — in Docker its port
is not published at all): owns account state.
- `POST /accounts/{accountId}/transactions` — apply a transaction (idempotent)
- `GET /accounts/{accountId}/balance` — balance = Σ CREDIT − Σ DEBIT
- `GET /accounts/{accountId}` — details + 10 most recent transactions
- `GET /health`, `GET /metrics`

Each service is an independently runnable process with its **own private
in-memory H2 database**. They share nothing at runtime except the HTTP
contract. Data is ephemeral by design (in-memory DB per the constraints):
every restart is a clean slate.

## Prerequisites

- **Docker** (preferred path), or
- **Java 21** + **Maven 3.9+** (manual path)

## Running

### Docker Compose (preferred)

```bash
docker compose up --build
```

Gateway: http://localhost:8080/health — the Account Service is intentionally
**unreachable from the host** (internal-only); the Gateway reaches it on the
compose network. To expose 8081 for debugging, uncomment the `ports` mapping
in `docker-compose.yml`.

There is deliberately no `depends_on`: the Gateway is designed to start and
serve reads even when the Account Service is absent.

### Manual (two terminals)

```bash
mvn -pl account-service spring-boot:run    # terminal 1 → :8081
mvn -pl event-gateway  spring-boot:run     # terminal 2 → :8080
```

### Try it

```bash
curl -i -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
        "eventId": "evt-001",
        "accountId": "acct-123",
        "type": "CREDIT",
        "amount": 150.00,
        "currency": "USD",
        "eventTimestamp": "2026-05-15T14:02:11Z",
        "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
      }'
```

Response: `201 Created`, `status: APPLIED`, and an `X-Trace-Id` header — grep
that id in both services' JSON logs to see the full journey of the request.
Submit the exact same payload again: `200 OK` with the original event, and the
balance does not change. `requests.http` contains a ready-made tour
(happy path, duplicates, conflicts, out-of-order arrival, validation errors).

## Tests

```bash
mvn test
```

33 tests across both modules, covering:
- **Core functionality**: idempotency (incl. an 8-thread concurrent duplicate
  submission storing exactly one event), out-of-order arrival (chronological
  listings; identical transactions applied in reverse order yield the same
  balance), balance math, validation with field-level messages
- **Resiliency**: a real (unmocked) client against a dead port — 503s, the
  circuit breaker opening, fail-fast behavior (asserted < 150 ms), events
  preserved as PENDING, reads continuing to work
- **Trace propagation**: outbound header asserted with a mock server, and
  end-to-end: a trace id entering the Gateway is found in the Account
  Service's own log events (MDC)
- **Integration**: the real Account Service is booted in-JVM on a random port
  and the real Gateway is pointed at it — genuine HTTP between two Spring
  contexts and two databases, nothing mocked

## Resiliency pattern: circuit breaker + timeouts (and deliberately no auto-retry)

The Gateway's call to the Account Service has explicit **connect/read
timeouts** (2s/3s) and a **Resilience4j circuit breaker** (sliding window of
10 calls, opens at 50% failures with a 5-call minimum, 10s open state, 3
half-open trial calls).

**Why this pattern:** timeouts alone bound a single call, but during an outage
every request still burns a full timeout and a Gateway thread — under load
that is thread-pool exhaustion, i.e. the failure cascades. The breaker
converts "every request waits 2s to fail" into "every request fails in ~1ms
with an honest 503", protecting the Gateway and giving the struggling
downstream room to recover instead of hammering it.

**Why no automatic retry:** a retry storm is exactly what a recovering service
does not need — and this system already has a safer alternative: because
*both* services are idempotent on `eventId`, resubmitting an event is always
safe, so retrying is the client's explicit, cheap choice rather than hidden
traffic multiplication. Only genuine unavailability is recorded as breaker
failure; a downstream `409` is a correct answer from a healthy service and
does not count.

**Graceful degradation** (Account Service down): `POST /events` returns `503`
(never a hang, never a raw 500) with a message stating the event is stored and
retrying is safe; `GET /events/*` keeps working because reads never leave the
Gateway's local database — by construction, not by fallback logic; balance
queries against the Account Service are simply unreachable, which is honest.

## Design decisions

**Idempotency via unique constraints, not check-then-insert.** Both services
carry a UNIQUE constraint on `eventId` and use insert-first / catch-violation.
A `SELECT`-then-`INSERT` has a race window under concurrency; the constraint
makes the database the single arbiter. Verified by concurrent tests
(8 threads, one row).

**Duplicate ≠ conflict.** Resubmitting the same `eventId` with the same
payload returns the original event (`200`). The same `eventId` with a
*different* payload is a contradiction, not a retry — `409 Conflict`.

**The Account Service is idempotent too (defense in depth).** The scariest
distributed failure here is: transaction applied downstream, response lost.
The Gateway cannot know whether money moved. Because replays are harmless at
the Account Service as well, that ambiguity is defused — no retry, by anyone,
can ever double-apply.

**Dual-write handled explicitly with an event lifecycle.** The Gateway stores
the event (`PENDING`), then applies it downstream, then marks it `APPLIED`.
If the downstream call fails, the event stays `PENDING` and the client gets a
`503`; resubmitting the same event later retries the apply and completes the
original intent. Simple, visible, and honest about the fact that two
databases cannot be updated atomically without heavier machinery (outbox /
sagas) that would be over-engineering at this scale.

**Balances are derived, never stored.** There is no balance column to drift or
corrupt; balance is `SUM(CASE CREDIT +amount ELSE -amount)` over the
transaction log, computed in the database. Because addition is commutative,
the result is provably independent of arrival order — which is *why*
out-of-order delivery cannot corrupt balances. Ordering is handled where it
matters: `ORDER BY eventTimestamp` on listings. (At very high volume a
snapshot/cache would be added; correctness wins at this scale.)

**Negative balances are allowed.** The spec defines balance as a pure sum and
sets no overdraft rule; a DEBIT may exceed the balance. Pinned by a test so
the behavior is a decision, not an accident.

**Tracing is a deliberate manual implementation of what OpenTelemetry
automates.** A filter births/adopts `X-Trace-Id` at the Gateway, an
interceptor propagates it downstream, the Account Service adopts it, and the
SLF4J MDC stamps it into every JSON log line of both services (Logstash
encoder, incl. service name). ~80 explainable lines; production would run the
OTel agent — the mechanism is identical.

**Metrics tell an operational story.** `events.received`, `events.applied`,
`events.duplicate`, and a timer on the downstream call. A growing gap between
received and applied means events are piling up `PENDING` — the downstream is
struggling. Micrometer is a facade; the same code feeds Prometheus by
swapping the registry.

**Error plumbing is duplicated per service on purpose.** A shared library
would couple two services that must be independently deployable; at two
services, duplication is cheaper than coupling. The uniform error shape
(`timestamp/status/error/messages`) is a convention of the API contract, not
shared code.

**Schema management:** Hibernate `ddl-auto` is acceptable *here* because the
databases are in-memory and rebuilt on every boot per the constraints;
production would use versioned migrations (Flyway).

**In tests only,** the Gateway module depends on the Account Service module
(`test` scope) to boot the real application in-JVM for the end-to-end suite.
Production artifacts remain fully independent.

## Project layout

```
event-ledger/
├── pom.xml                  # parent (Spring Boot 3.3, Java 21)
├── docker-compose.yml
├── requests.http            # clickable API tour (IntelliJ Ultimate)
├── event-gateway/           # public service :8080
│   └── src/main/java/com/eventledger/gateway/
│       ├── api/             # controllers, DTOs, error handling
│       ├── client/          # the single Account Service integration seam
│       ├── config/          # RestClient + timeouts
│       ├── domain/          # Event entity (unique eventId), lifecycle
│       ├── metrics/         # Micrometer counters/timer
│       ├── repository/
│       ├── service/         # store → apply → APPLIED orchestration
│       └── trace/           # trace filter + propagation interceptor
└── account-service/         # internal service :8081 (same layered layout)
```
