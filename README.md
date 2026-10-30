# Activation Service

Service that records customer product activation requests, lets clients check their status and performs
the activation by calling an external provider.

Stack: Java 21, Spring Boot 3.5 (MVC, Data JPA, Security Resource Server, Actuator), PostgreSQL,
Liquibase, Maven and Docker. Tests with JUnit 5, Mockito, AssertJ, MockMvcTester, WireMock, Awaitility,
Testcontainers and ArchUnit.

Sections: [How to run](#how-to-run) · [API](#api) · [Technical decisions](#technical-decisions) ·
[Tests](#testing-strategy) · [Observability](#observability) · [Analysis scenario](#analysis-scenario) ·
[Production](#production) · [Pending items](#pending-items) · [Future improvements](#future-improvements) ·
[AI usage](#ai-usage)

The main decisions are also recorded in [`docs/adr`](docs/adr).

## How to run

Requires JDK 21. Maven comes with the wrapper and Docker is optional.

Build and all tests:

```bash
./mvnw verify
```

Runs the unit tests (`*Test`, surefire) and the integration tests (`*IT`, failsafe). The tests against a
real PostgreSQL use Testcontainers and are skipped when Docker is not available; the other integration
tests use H2 in PostgreSQL mode with the same Liquibase changelog. On Windows, `mvnw.cmd verify`.

Run without Docker (`local` profile):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile uses in-memory H2, a fake provider inside the application itself and an endpoint
that issues test tokens, since the challenge has no authorization server. All of this sits behind
`local-dev.enabled=true`, which is never turned on in production. The local token secret comes from
`LOCAL_JWT_SECRET`; without it, the application generates a random one on every start.

Full environment (application, PostgreSQL 16 and WireMock as the provider):

```bash
docker compose up --build
```

Trying it with curl:

```bash
TOKEN=$(curl -s -X POST "http://localhost:8080/dev/token?clientId=system-a" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

curl -i -X POST http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"

curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

The fake provider (and the WireMock mappings in [`docker/wiremock/mappings`](docker/wiremock/mappings))
answers according to the `productCode`:

| productCode contains | Provider answers | Final status |
|---|---|---|
| anything else | 200 | `COMPLETED` |
| `REJECT` | 400 | `FAILED` on the 1st attempt |
| `ERROR` | always 500 | `FAILED` after 5 attempts |
| `SLOW` | takes 5 s (read timeout is 3 s) | local: timeout, then `COMPLETED`; compose: `FAILED` after the attempts |
| `BUSY` | 503 with `Retry-After: 3` | local: `COMPLETED` on the 2nd attempt; compose: `FAILED` after the attempts |

Public operational endpoints: `/actuator/health/liveness`, `/actuator/health/readiness` and
`/actuator/prometheus`.

## API

| Method | Path | Scope | Description |
|---|---|---|---|
| POST | `/api/v1/requests` | `activation:write` | Creates the request (idempotent by `externalId`) |
| GET | `/api/v1/requests/{id}` | `activation:read` | Gets one request |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | Paginated list, newest first |

POST responses:

| Situation | Status |
|---|---|
| Created | `201 Created` with `Location: /api/v1/requests/{id}` |
| Retry with the same `externalId` and same content | `200 OK` with the existing record |
| Same `externalId` with different content | `409 Conflict` with `existingRequestId` |
| Invalid data | `400` with the `errors` list per field |
| Body larger than 16 KB | `413` |
| No token or invalid token / missing scope | `401` / `403` |

Validation of `externalId`, `customerId` and `productCode`: required, up to 64 characters, only letters,
digits, `.`, `_` and `-`. Pagination: `page >= 0` and `size` between 1 and 100 (default 20).

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "FAILED",
  "attempts": 1,
  "failureReason": "PROVIDER_REJECTED_400",
  "createdAt": "2026-10-26T14:03:11Z",
  "updatedAt": "2026-10-26T14:03:12Z"
}
```

Dates in UTC, ISO-8601. `failureReason` is a stable code, never the provider message.

Errors follow Problem Details (RFC 9457, `application/problem+json`) and include `traceId`, the same
correlation id that shows up in the logs and in the `X-Correlation-Id` header. No response carries a
stack trace or an internal message; unexpected errors become a generic `500` and the detail stays in the
log.

## Technical decisions

### Layout

```
api            controller interface with the HTTP contract; api.impl only delegates to the service
service        business rules (create/get/list and request processing)
repository     Spring Data; repository.domain holds the entity
intercomm      @HttpExchange client for the provider and the service that classifies each response
job            scheduled routine that triggers processing
config         security, HTTP client, filters, error handling, metrics, properties
dto / vo       API contract and internal objects (call result, retry policy)
enumerator     request status and error catalog (code, message key, HTTP status)
localdev       token issuer and fake provider, only with local-dev.enabled=true
```

Dependencies always point the same way (controller and job call services; services call repository and
intercomm), and ArchUnit tests check it. Error and validation texts live in `messages.properties`;
routes, headers and scopes live in constant classes.

### Time zone

The application clock uses the JVM default zone, which comes from the environment: the Dockerfile and
docker-compose set `TZ=America/Sao_Paulo`, so changing the zone is a deployment setting, not a code
change. That only affects local times (logs, for example). Everything stored or returned is an instant:
the database columns are `TIMESTAMP WITH TIME ZONE`, Hibernate writes in UTC and the API always returns
UTC (ISO-8601), as the challenge asks. Tests run with the JVM in UTC.

### States and transitions

```
PENDING -> PROCESSING -> COMPLETED
   ^           |
   +-----------+  transient failure, with backoff
               +-> FAILED
```

Status only changes through entity methods (`startProcessing`, `complete`, `fail`, `scheduleRetry`,
`releaseExpiredLease`); there is no setter. An invalid transition, such as `COMPLETED` going back to
`PROCESSING`, is rejected, and that is tested without Spring.

A transient failure goes back to `PENDING`, with `attempts` incremented and the next attempt in the
future. This way `PROCESSING` only means "a worker has it right now", which keeps reconciliation simple:
`PROCESSING` with an expired lease is always a worker that died.

The entity has `@Version` (optimistic lock): if two instances try to claim the same request, only one
saves and the other skips it.

### Duplicates

The unique index on `(cd_sourcesystem, cd_external)` is what guarantees uniqueness. The service:

1. looks up (source system, externalId); if it exists, resolves right away (the common retry);
2. tries the INSERT (`saveAndFlush`, in its own transaction);
3. if the index rejects it, another request won the race: it reads the winning record and resolves.

Checking and then inserting is not enough because, between one request's SELECT and INSERT, an identical
request can run its own SELECT. Neither finds anything and both insert; there is no row to lock, so READ
COMMITTED does not help. Only the database constraint is atomic across transactions and instances. That
is why the create method does not open a transaction: the re-read after the failure needs a new one
(in PostgreSQL, a transaction that hit an error stays aborted).

| Scenario | Response | Reason |
|---|---|---|
| Same `externalId`, same content | `200` with the existing record | Retries are expected and the client needs the `id` and status. `200` instead of `201` shows nothing was created. |
| Same `externalId`, different `customerId`/`productCode` | `409` with `existingRequestId` | The client reused the identifier for something else; we neither overwrite nor create another. |
| Two identical requests in parallel | one gets `201`, the other `200` with the same record | The loser hits the index, re-reads and falls into the retry case. |

`externalId` is unique per source system, not globally: two systems can generate the same value, and with
global uniqueness system B would get system A's record or a 409 that reveals it exists.

Tested with 16 threads against H2 and 20 against PostgreSQL (Testcontainers): always a single record and
exactly one creation.

### Provider integration

The POST only saves the request as `PENDING` and returns. A scheduled job (every 1 s) picks the ones
that are ready and calls the provider. If the provider gets slow, the API stays fast; the delay only shows
in the time until `COMPLETED`, which the client follows through the GET.

The HTTP call never happens inside an open transaction. There are three steps:

1. claim, in a short transaction: `PENDING -> PROCESSING`, `attempts + 1` and a 30 s lease;
2. call the provider, with no transaction and no database connection held;
3. save the result, in a short transaction, only if the record version is still the claimed one.

The table itself works as the outbox: after the POST commits, any instance finds the `PENDING` record. If
an instance dies during the call, the request stays `PROCESSING` with an expired lease and is processed
again.

| Provider response | New status | Retry? | Why |
|---|---|---|---|
| 2xx | `COMPLETED` | No | Product activated. |
| 400 and other 4xx | `FAILED` (`PROVIDER_REJECTED_4xx`) | No | The provider rejected the content; retrying gives the same result. |
| 500, 502, 504, 408 | `PENDING` with backoff; `FAILED` when attempts run out | Yes | Provider-side failure, usually temporary. |
| Timeout or network error | `PENDING` with backoff; `FAILED` when attempts run out | Yes, with the same `Idempotency-Key` | The outcome is unknown: the provider may have activated. |
| 429 or 503 with `Retry-After` | `PENDING` until after `Retry-After` (5 min cap) | Yes | The provider said when to come back. Accepts seconds or an HTTP date. |
| 401 or 403 | `PENDING` with backoff and an error log | Yes | The problem is our credential, not the request. |

- Timeouts: connect 2 s and read 3 s, configurable through `PROVIDER_CONNECT_TIMEOUT` and
  `PROVIDER_READ_TIMEOUT`. The 30 s lease is longer than both together, so a live worker never loses its
  claim in the middle of a call.
- Ambiguous timeout: every call sends `Idempotency-Key` with the request id. The assumption is that the
  provider deduplicates by that key (the fake one does). Without that support, I would check the status at
  the provider before resending.
- Limit: 5 calls, with exponential backoff and jitter (2 s, 4 s, 8 s, 16 s, each drawn between half and
  the full value, capped at 5 min). When they run out, `FAILED` with `MAX_ATTEMPTS_EXCEEDED:<last reason>`.
- Each cycle's batch (up to 20) runs on virtual threads, with at most 5 concurrent calls, so it does not
  compete for more connections than the pool has.
- No circuit breaker was implemented; see [Pending items](#pending-items).

### Security

- OAuth 2.0 Client Credentials flow: the clients are systems, with no user and no redirect. Each system
  has its client and secret (or `private_key_jwt`) at the authorization server.
- The service is a Resource Server and validates the JWT signature (keys from the issuer's JWKS,
  discovered through `JWT_ISSUER_URI`), issuer, audience (`activation-service`) and expiration.
- Scopes: `activation:write` creates; `activation:read` gets and lists.
- Isolation between clients (BOLA): each request stores the `client_id` that created it, and get, list
  and uniqueness always filter by it. A request from another system returns `404`, not `403`, so its
  existence is not revealed.
- Provider credential: comes from `PROVIDER_API_KEY`, injected in production from the cloud secret
  manager, never from the repository or the image. To rotate it, the provider accepts two keys during the
  switch, the new one goes to the secret manager and instances restart in a rolling fashion. The key never
  shows up in logs.
- Body limited to 16 KB (`413`). Rate limiting would live in the API Gateway; see pending items.
- Locally, without an IdP, `local-dev` mode uses HS256 with the same issuer, audience and expiration
  checks. Tests cover expired, tampered, wrong-issuer and wrong-audience tokens, missing scope and
  isolation between systems.

### Indexes

| Index | Use |
|---|---|
| unique `(cd_sourcesystem, cd_external)` | Idempotency and the retry lookup. |
| `(fl_status, dt_nextattempt)` | The job query and counts by status. |
| `(cd_sourcesystem, fl_status, dt_created)` | List filtered by status, sorted by date. |
| `(cd_sourcesystem, cd_customer, dt_created)` | List filtered by customer. |

Every list starts with the source system, so it is the first column. With a lot of data, the page total
gets expensive and I would move to cursor pagination. In PostgreSQL the job index could be partial (only
`PENDING` and `PROCESSING`); I kept it plain so the same changelog runs on H2.

## Testing strategy

| Type | Class | What it guarantees |
|---|---|---|
| Unit | `ActivationRequestTest` | Transitions, lease, failure reason length. |
| Unit | `RetryPolicyVOTest` | Exponential backoff, jitter, cap, Retry-After. |
| Unit (Mockito) | `ActivationProcessingServiceImplTest` | Decision per result, attempt limit, contested claim, expired lease. |
| HTTP client against WireMock | `ActivationProviderIntercommServiceImplTest` | Each HTTP status, Retry-After, timeout, unreachable provider, headers sent. |
| Web slice (`@WebMvcTest`) | `ActivationRequestControllerTest` | HTTP contract: 201, 200, 409, 400, 404, 413, 500, 401 and 403. |
| Integration (H2 + Liquibase) | `DuplicateActivationRequestIT` | Idempotency in the database, including 16 threads. |
| End-to-end integration | `ActivationProcessingIT` | API, database, job and WireMock: success, 400, 500 until attempts run out, timeout and retry with the same key. |
| Security integration | `SecurityIsolationIT` | Real JWT validation, BOLA, list filters, health and metrics. |
| PostgreSQL integration | `ActivationRequestPostgresIT` | Duplicate race and list on a real database (Testcontainers). |
| Architecture | `ArchitectureTest` | Direction of dependencies between packages. |

Unit tests cover the rules, which change the most; the web slice covers the HTTP contract; integration
tests cover what only shows up with a real database and network (the index race, timeouts,
transactions). No test depends on a real authorization server.

## Observability

- Health: `/actuator/health/liveness` and `/actuator/health/readiness`; only readiness checks the database.
- Correlation: the incoming `X-Correlation-Id` (validated) or a generated one goes to the MDC, to every log
  line, to the `traceId` of errors, is stored on the request and is sent again to the provider by the job.
- Metrics at `/actuator/prometheus`: `http_server_requests_seconds` (API latency),
  `activation_provider_calls_seconds{outcome}` (provider latency and errors),
  `http_client_requests_seconds`, `activation_requests{status}`,
  `activation_requests_transitions_total{status}`, plus `hikaricp_*` and `jvm_*`.
- JSON logs (ECS) in the `prod` profile. Tokens, secrets and the provider key are not logged.
- Distributed tracing was not implemented; with `micrometer-tracing-bridge-otel` and an OTLP exporter, the
  OpenTelemetry `traceId` would replace the custom correlation id.

## Analysis scenario

> API 300 ms to 2.5 s, CPU 45%, memory 60%, some queries 50 ms to 1.2 s, provider 400 ms to 3 s.

Normal CPU and memory mean the application is not working harder, it is waiting on something: I/O, a
connection or a lock.

1. What I would look at first: when it started and what changed at that moment (deploy, traffic, a job,
   a provider change, a migration). Then, which endpoints got slower and whether the database slowness is
   query execution or waiting for a connection.

2. How to tell application, database and integration apart: by breaking down the time of a slow request.
   Tracing shows how much goes to HTTP, JDBC and the external call. If `pg_stat_statements` says 50 ms and
   the application measures 1.2 s, the time is spent waiting for the pool
   (`hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds`), not in the database. For the
   provider, `http_client_requests_seconds` by status. A thread dump with threads stuck in
   `HikariPool.getConnection` or reading a socket points to the culprit.

3. Useful logs and metrics: p50/p95/p99 per endpoint; provider latency, errors and timeouts; Hikari pool
   (active, idle, pending, acquire time); `pg_stat_activity` and `EXPLAIN ANALYZE` of the slow queries;
   queue size (`activation_requests{status="PENDING"}`) and retry rate; GC pauses; logs with the
   correlation id to follow one case.

4. Improvements: take the provider call out of the synchronous path and out of transactions (this service
   already does); short timeouts and a circuit breaker; backoff with jitter to avoid retry storms; indexes
   for the slow queries and cursor pagination; a properly sized pool, separate for API and job.

5. Common cause (hypothesis): the slow provider exhausts a shared resource. If the provider call happens
   inside a transaction, each call that goes from 400 ms to 3 s holds the connection seven times longer.
   The pool runs out, queries start waiting for a connection (they look like 1.2 s even though the
   database runs them in 50 ms) and every route gets slow, with low CPU. Aggressive retries make it worse.
   To confirm: `hikaricp_connections_pending` above zero together with the provider latency, normal
   `pg_stat_statements`, `idle in transaction` connections in `pg_stat_activity` and a thread dump with
   threads in `getConnection` and in socket reads. If the database had degraded for another reason, the
   same diagnosis would show it.

6. Validating after release: canary or rolling, compared with the baseline and with the old instances:
   p95/p99 back to about 300 ms, no pending connections, queries around 50 ms, normal error and retry
   rates and a stable `PENDING` queue. Before that, a load test with a slow provider (WireMock with delay).
   After that, a latency SLO alert to catch regressions.

## Production

- Image from the [`Dockerfile`](Dockerfile): two-stage build, only a JRE in the final image, non-root user,
  Spring Boot layers, heap at 75% of the container limit, healthcheck, `TZ` set and the JVM as PID 1 to
  receive `SIGTERM` (graceful shutdown).
- Orchestrator (Kubernetes or ECS) with at least 2 replicas, liveness and readiness on Actuator, CPU and
  memory limits and `terminationGracePeriodSeconds` longer than the graceful shutdown.
- Managed PostgreSQL with backups and a replica. Liquibase runs on startup, or as a job before the deploy
  if there are many replicas.
- Configuration through environment variables (`DB_URL`, `DB_USERNAME`, `JWT_ISSUER_URI`, `JWT_AUDIENCE`,
  `PROVIDER_BASE_URL`, timeouts, `TZ`, `SPRING_PROFILES_ACTIVE=prod`); `DB_PASSWORD` and `PROVIDER_API_KEY`
  come from the secret manager. Without the required ones the application does not start.
- API Gateway in front with TLS, per-client rate limiting and a body limit. `/actuator/prometheus` only on
  the internal network.

Microservices and cloud:

- Multiple instances: duplicates are resolved in the database, so it works with any number of instances.
  The job can run on all of them, because the claim uses an optimistic lock and the expired lease recovers
  what a dead instance left behind. With high volume, I would switch to `SELECT ... FOR UPDATE SKIP LOCKED`.
- Configuration and secrets: non-sensitive values in environment variables or a ConfigMap; sensitive
  ones in the cloud secret manager, with rotation.
- Notifying other services: when a request completes or fails, publish an event to a topic (Kafka,
  SNS/SQS, Pub/Sub), saving the event in a table in the same transaction as the status change and
  publishing from there, the same idea already used here to call the provider.
- CI/CD: on a PR, `./mvnw verify` (unit, integration, Testcontainers, ArchUnit); then image build,
  vulnerability scan, push tagged with the commit, deploy to staging with a smoke test and promotion to
  production as a canary, with metric-based rollback. The workflow in
  [`.github/workflows/ci.yml`](.github/workflows/ci.yml) covers build, tests and image.

## Pending items

| Item | How I would do it |
|---|---|
| Per-client rate limiting | At the API Gateway, by the token client. In the application, Bucket4j with state in Redis so it holds across instances, returning `429` with `Retry-After`. |
| Body without `Content-Length` (chunked) | The filter checks `Content-Length`; chunked bodies would be limited at the gateway or proxy. |
| Circuit breaker | Resilience4j in the provider service; while open, it reschedules without spending an attempt. |
| Distributed tracing | Micrometer Tracing with OpenTelemetry (OTLP). |
| Requests that ran out of attempts | Alert and an admin endpoint to reprocess them. |
| Provider 409 for an already used key | Depends on the real contract; if it means "already activated", I would treat it as success. |
| Docker | Dockerfile and compose are written and the WireMock mappings were validated, but `docker build` did not run on my machine (no Docker); CI builds the image. |

## Future improvements

- Completion event published through an outbox to other services.
- `SELECT ... FOR UPDATE SKIP LOCKED` on the claim and a partial index on PostgreSQL.
- Cursor pagination.
- Cancel and reprocess, with the new transitions in the domain.
- Published OpenAPI contract and contract tests with the provider.
- Alerts for a growing queue, expired leases, `FAILED` rate and provider latency.
- Load test with a slow provider.
- Retention and archiving of old requests.

## AI usage

I used Claude Code (Anthropic) to read the challenge, generate code, tests, Docker and CI configuration and
a draft of this README, in commits per challenge item. I reviewed the decisions and the code and validated
them with `./mvnw verify` and the manual flow in the `local` profile.
