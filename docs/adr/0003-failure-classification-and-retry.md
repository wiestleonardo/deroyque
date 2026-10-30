# ADR 0003: provider response classification and retry policy

Status: accepted

## Decision

- 2xx: `COMPLETED`. Any other status, including 3xx, is a failure. 4xx (except 401, 403, 408 and 429): `FAILED` with no retry.
- 5xx, 408, timeout and network errors: retry. 401 and 403 also retry, because the problem is our credential and the
  requests should recover once the secret is fixed. 429 and 503: retry honoring `Retry-After` (seconds).
- The classification lives in `IntegrationErrorHandler`, applied to the `RestClient` in `HttpClientConfig`, and
  always ends in an `IntegrationException` saying whether the failure is retryable.
- A retryable failure goes back to `PENDING` with exponential backoff plus jitter, capped at 5 minutes.
- At most 5 attempts; then `FAILED` with `MAX_ATTEMPTS_EXCEEDED:<reason>`.
- Every call sends `Idempotency-Key` with the request id, because a timeout does not tell whether the
  activation happened.

## Consequences

- `PROCESSING` only means "a worker has it right now", which keeps reconciliation simple.
- It relies on the provider deduplicating by the idempotency key; without that, the status would have to be
  checked at the provider before resending after a timeout.
