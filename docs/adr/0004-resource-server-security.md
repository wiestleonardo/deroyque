# ADR 0004: OAuth 2.0 Resource Server with scopes and isolation per source system

Status: accepted

## Decision

- The clients are systems, so the flow is Client Credentials at the corporate IdP.
- The service validates the JWT with Spring Security: signature through the JWKS of the `issuer-uri`,
  `iss`, `aud` and `exp`.
- `activation:write` creates; `activation:read` gets and lists.
- The token `client_id` owns the request and every read filters by it (BOLA). A request from another
  system returns `404`.
- To run locally without an IdP there is the `local-dev.enabled` mode (HS256 and a test token issuer), off
  by default and isolated in the `localdev` package; an ArchUnit test makes sure the rest of the code does
  not depend on it.
