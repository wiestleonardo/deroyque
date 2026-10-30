# ADR 0001: asynchronous processing using the table itself as the outbox

Status: accepted

## Context

The provider takes about 400 ms and can slow down to seconds or go down. Calling it inside
`POST /api/v1/requests` ties the API latency and availability to the provider's. Calling it after the
commit, with no record of what is still to be sent, loses the request if the application dies between the
commit and the call.

## Decision

The POST saves the request as `PENDING` and returns. A scheduled job picks the requests that are ready
(`PENDING`, or `PROCESSING` with the next attempt due) and processes each one in three steps: claim in a
short transaction, HTTP call outside any transaction, and saving the result in a short transaction, only if
the version is still the claimed one.

## Consequences

- The API stays fast with a slow provider; the client follows the status through the GET.
- Nothing saved is lost: the state is in the database and an expired lease is processed again.
- The time until `COMPLETED` includes the job interval (1 s). If that mattered, an event after the commit
  could wake the job, keeping polling as the safety net.
- There is no broker to operate. With high volume, I would move to a queue (SQS, Kafka) fed by an outbox.
