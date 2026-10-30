# ADR 0002: idempotency through the unique index (source system, externalId)

Status: accepted

## Context

Source systems retry and may send the same request in parallel. Checking whether it exists and then
inserting leaves a window: two requests pass the check before any INSERT and both insert.

## Decision

- The unique index on `(cd_sourcesystem, cd_external)` is the guarantee.
- The application tries the INSERT; if the index rejects it, it reads the winning record in a new
  transaction.
- Same content returns `200` with the existing record; different content returns `409`.
- Uniqueness is per source system, because the `externalId` belongs to it.

## Alternatives

- Pessimistic lock or `SERIALIZABLE`: there is no row to lock before it exists, and serializable produces
  serialization failures that would need the same handling, at a higher cost.
- `INSERT ... ON CONFLICT DO NOTHING`: good on PostgreSQL, but specific to it; the chosen approach works on
  any relational database and on the H2 used in tests.
- Distributed lock (Redis): more infrastructure, and the index would still be the final guarantee.
