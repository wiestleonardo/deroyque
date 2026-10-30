# ADR 0003: provider response classification and retry policy

Status: accepted

## Decision

- 2xx: `COMPLETED`. 4xx (except 401, 403, 408 and 429): `FAILED` with no retry.
- 5xx, 408, timeout, network error, 401 and 403: retry. 429 and 503: retry honoring `Retry-After`.
- A transient failure goes back to `PENDING` with the next attempt in the future (exponential backoff with
  jitter, capped at 5 min).
- At most 5 attempts; then `FAILED` with `MAX_ATTEMPTS_EXCEEDED:<reason>`.
- Every call sends `Idempotency-Key` with the request id, because a timeout does not tell whether the
  activation happened.
- The result is the `sealed interface ActivationResultVO` (`Success`, `PermanentFailure`,
  `TransientFailure`), handled with an exhaustive `switch`: a new result type does not compile until it is
  handled.

## Consequences

- `PROCESSING` only means "a worker has it right now", which keeps reconciliation simple.
- It relies on the provider deduplicating by the idempotency key; without that, the status would have to
  be checked at the provider before resending after a timeout.
