# Activation Service

Records customer product activation requests, lets clients check their status and activates the product
through an external provider.

Java 21, Spring Boot 3.5 (MVC, Data JPA, Security Resource Server, Actuator), PostgreSQL, Liquibase, Maven,
Docker. Tests with JUnit 5, Mockito, MockMvcTester, WireMock, Awaitility, Testcontainers and ArchUnit.

## How to run

Requires JDK 21 (Maven comes with the wrapper).

```bash
./mvnw verify          # build + unit and integration tests
docker compose up --build   # app + PostgreSQL + provider mock (WireMock)
```

The PostgreSQL tests use Testcontainers and are skipped when Docker is not available; the other integration
tests use H2 with the same Liquibase changelog.

Without Docker: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (H2 in memory; the provider is
expected at `http://localhost:8089`, which is the WireMock from compose).

The `local` profile (also used by compose) enables `/dev/token`, which issues test tokens because there is no
authorization server in the challenge. It is never enabled in production.

```bash
TOKEN=$(curl -s -X POST "http://localhost:8080/dev/token?clientId=system-a" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

curl -i -X POST http://localhost:8080/api/v1/requests -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

The WireMock mappings in `docker/wiremock/mappings` answer by `productCode`: `REJECT` returns 400, `ERROR`
returns 500, `SLOW` takes 5 s, `BUSY` returns 503 with `Retry-After`, anything else returns 200.

## API

| Method | Path | Scope |
|---|---|---|
| POST | `/api/v1/requests` | `activation:write` |
| GET | `/api/v1/requests/{id}` | `activation:read` |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` |

- `externalId`, `customerId` and `productCode` are required, up to 64 characters (the column size).
- `page` starts at 0; `size` goes from 1 to 100 (default 20), anything above 100 returns 400.
- Dates are UTC, ISO-8601. Responses never expose internal fields.
- Errors use Problem Details (RFC 9457) with a `traceId`, the same correlation id found in the logs. Stack
  traces and internal messages are never returned.
- Handled errors: invalid data (400), missing token or scope (401/403), not found (404), duplicate (409),
  unexpected (500). Provider errors never reach the POST, because the call is asynchronous: they show up in
  the request `status` and `failureReason` (for example `PROVIDER_REJECTED_400`).

## Technical decisions

### Layout

`api` (controller contract and implementation), `service` (business rules), `repository` (Spring Data and the
entity), `intercomm` (provider client), `job` (scheduled processing), `config` (security, HTTP client, error
handling). Controller and job call services; services call repositories and the provider client. ArchUnit
checks this.

### Status

`PENDING -> PROCESSING -> COMPLETED | FAILED`. A temporary failure goes back to `PENDING` with a later
`nextAttemptAt`. The status only changes through entity methods, which reject invalid transitions (for
example, `COMPLETED` never goes back to `PROCESSING`). `@Version` protects against concurrent updates.

### Duplicates

A unique index on `(source system, externalId)` is the real guarantee. The service looks for an existing
record first (the common retry case) and then inserts; if two identical requests pass the lookup at the same
time, only one insert succeeds and the other one reads the saved record. Looking up and then inserting is
not enough on its own because both requests can run the lookup before either inserts.

| Case | Response |
|---|---|
| Same `externalId`, same content | `200` with the existing record |
| Same `externalId`, different content | `409` with `existingRequestId` |
| Two identical requests at the same time | one gets `201`, the other `200` |

`externalId` is unique per source system, since two systems may generate the same value.

### Provider integration

The POST only saves the request as `PENDING` and returns, so the API does not depend on the provider's
speed. A job runs every second, claims ready requests (`PENDING -> PROCESSING`, saved with `@Version`), calls
the provider outside any transaction and saves the result. Because the request is in the database before the
call, nothing is lost if the application stops between the commit and the call. If it stops during the call,
the request stays `PROCESSING` until its 30 s lease expires and is processed again.

| Provider response | Result | Retry? |
|---|---|---|
| 2xx | `COMPLETED` | No |
| 4xx (except 408 and 429) | `FAILED` | No, the provider rejected the content |
| 5xx, 408 | `PENDING` with backoff | Yes |
| 429 / 503 with `Retry-After` | `PENDING` after `Retry-After` | Yes |
| Timeout / network error | `PENDING` with backoff | Yes, with the same `Idempotency-Key` |

- Connect timeout 2 s, read timeout 3 s, both configurable.
- A timeout is ambiguous (the provider may have activated), so every call sends `Idempotency-Key` with the
  request id and the provider is expected to deduplicate by it.
- Up to 5 attempts with exponential backoff (2 s, 4 s, 8 s...) plus jitter, capped at 5 minutes. After that
  the request is `FAILED` with `MAX_ATTEMPTS_EXCEEDED`.
- `IntegrationErrorHandler` turns any HTTP failure into an `IntegrationException` (retryable or not), so new
  clients get the same handling by being built through `HttpClientConfig`.

### Security

- OAuth 2.0 Client Credentials, since the clients are other systems.
- The service is a Resource Server: it validates the JWT signature (JWKS from `JWT_ISSUER_URI`), issuer,
  audience and expiration.
- `activation:write` creates, `activation:read` reads.
- Each request belongs to the token's client (`AuthenticatedSystemService`); reads always filter by it, and a
  request from another system returns `404` (BOLA).
- The provider key comes from `PROVIDER_API_KEY`, injected from the cloud secret manager and rotated there;
  it is never in the repository or logs.

### Time zone

The clock uses the JVM zone, set by `TZ` in the Dockerfile and compose (`America/Sao_Paulo`). Stored and
returned dates are UTC instants.

### Indexes

Unique `(cd_sourcesystem, cd_external)` for duplicates; `(fl_status, dt_nextattempt)` for the job;
`(cd_sourcesystem, fl_status, dt_created)` and `(cd_sourcesystem, cd_customer, dt_created)` for the list,
which always filters by source system and sorts by date.

## Testing strategy

- Unit: entity transitions, processing decisions (success, rejection, retry, Retry-After, last attempt,
  concurrent claim, expired lease).
- HTTP client against WireMock: headers sent and how each response becomes an `IntegrationException`.
- Web slice: HTTP contract, validation, 401/403, error format.
- Integration with H2: duplicates including 16 concurrent threads; full flow API, job and WireMock (success,
  400, 500 until max attempts, timeout then success); JWT validation and isolation between systems.
- Integration with PostgreSQL (Testcontainers): concurrent duplicates and list filters.
- ArchUnit: direction of dependencies.

## Observability

- `/actuator/health/liveness` and `/actuator/health/readiness` (readiness checks the database).
- `X-Correlation-Id` (received or generated) goes to every log line, to the error `traceId`, is stored with
  the request and is sent to the provider by the job.
- `/actuator/prometheus` exposes API latency (`http_server_requests_seconds`) and provider latency and errors
  (`http_client_requests_seconds`).
- JSON logs (ECS) in the `prod` profile; tokens and keys are never logged.

## Analysis scenario

API 300 ms to 2.5 s, CPU 45%, memory 60%, some queries 50 ms to 1.2 s, provider 400 ms to 3 s.

1. **First:** what changed when it started (deploy, traffic, provider, data volume) and which endpoints got
   slower. Normal CPU and memory suggest the application is waiting, not working harder.
2. **Application, database or provider:** tracing or per-step timing of a slow request. If the database
   reports 50 ms (`pg_stat_statements`) but the application sees 1.2 s, the time is spent waiting for a
   connection from the pool. Provider latency comes from the HTTP client metrics. A thread dump shows where
   threads are stuck.
3. **Useful metrics:** p95/p99 per endpoint, provider latency and error rate, Hikari pool (active, pending,
   acquire time), slow queries and their plans, number of `PENDING` requests, logs with the correlation id.
4. **Improvements:** keep provider calls out of the request and out of transactions (already done here),
   short timeouts and a circuit breaker, backoff with jitter, indexes for the slow queries, a sized pool.
5. **Common cause:** a slow provider called while holding a database connection. Each call holds the
   connection longer, the pool runs out, queries wait for a connection (they look slow even though the
   database is fast) and every endpoint slows down with low CPU. Confirm with pending connections in Hikari
   rising together with provider latency, normal query times in the database and `idle in transaction`
   sessions.
6. **Validate the fix:** canary release compared with the baseline: p95 back near 300 ms, no pending
   connections, queries back near 50 ms; a load test with a slow WireMock before releasing.

## Production

- Docker image from the `Dockerfile`: multi-stage build, JRE only, non-root user, heap sized by container
  memory, healthcheck, graceful shutdown.
- Kubernetes or ECS with 2+ replicas, liveness/readiness on Actuator, managed PostgreSQL, Liquibase on
  startup.
- Configuration by environment variables (`DB_URL`, `JWT_ISSUER_URI`, `PROVIDER_BASE_URL`, timeouts, `TZ`,
  `SPRING_PROFILES_ACTIVE=prod`); `DB_PASSWORD` and `PROVIDER_API_KEY` from the secret manager.
- Several instances work as-is: duplicates are blocked by the database and the job claim uses `@Version`.
- Other services could be notified by publishing an event when a request completes, saved in the same
  transaction and published from an outbox table.
- CI/CD: `./mvnw verify`, image build, vulnerability scan, push, deploy to staging, then production. The
  GitHub Actions workflow covers build, tests and image build.

## Pending items

- Rate limiting and request body size limit: at the API Gateway.
- Circuit breaker for the provider: Resilience4j around the provider service.
- Distributed tracing: Micrometer Tracing with OpenTelemetry.
- Requests that ran out of attempts: alert and a reprocess endpoint.
- `docker build` and `docker compose` were not run on my machine (no Docker); CI builds the image.

## Future improvements

Completion events for other services, cursor pagination, `SELECT ... FOR UPDATE SKIP LOCKED` for high volume,
OpenAPI documentation, alerts on queue size and failure rate.

## AI usage

Claude Code (Anthropic) helped read the challenge and write code, tests, Docker/CI files and this README. I
reviewed the decisions and the code and validated them with `./mvnw verify` and manual tests.
