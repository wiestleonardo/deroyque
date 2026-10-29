# ADR 0004 — Resource Server OAuth 2.0 com escopos e isolamento por consumidor

**Status:** aceito

## Decisão
- Consumidores são sistemas → fluxo *Client Credentials* no IdP corporativo.
- O serviço valida JWT (assinatura via JWKS do `issuer-uri`, `iss`, `aud`, `exp`) com Spring Security.
- `activation:write` cria; `activation:read` consulta.
- O `client_id` do token é o dono da solicitação; toda leitura filtra por ele (BOLA). Recurso de outro
  consumidor responde `404`.
- Para desenvolvimento local sem IdP existe o modo `app.local-dev.enabled` (HS256 + emissor de tokens
  de teste), desligado por padrão, isolado no pacote `localdev` (ArchUnit garante que o resto do código
  não depende dele).
