# ADR 0004: Resource Server OAuth 2.0 com escopos e isolamento por sistema de origem

Status: aceito

## Decisão

- Quem consome são sistemas, então o fluxo é Client Credentials no IdP corporativo.
- O serviço valida o JWT com Spring Security: assinatura pelo JWKS do `issuer-uri`, `iss`, `aud` e `exp`.
- `activation:write` cria; `activation:read` consulta.
- O `client_id` do token é o dono da solicitação e toda leitura filtra por ele (BOLA). Solicitação de outro
  sistema responde `404`.
- Para rodar localmente sem IdP existe o modo `local-dev.habilitado` (HS256 e emissor de token de teste),
  desligado por padrão e isolado no pacote `localdev`; um teste ArchUnit garante que o resto do código não
  depende dele.
