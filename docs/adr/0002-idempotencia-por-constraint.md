# ADR 0002 — Idempotência por constraint única (client_id, external_id)

**Status:** aceito

## Contexto
Sistemas de origem fazem retry e podem enviar a mesma solicitação em paralelo. "Consultar se existe e
depois inserir" tem uma janela de corrida: duas requisições passam pela consulta antes de qualquer
INSERT e ambas inserem.

## Decisão
- `UNIQUE (client_id, external_id)` no banco é a garantia.
- A aplicação tenta o INSERT; na violação da constraint relê o registro vencedor numa transação nova.
- Mesmo conteúdo → `200` com o registro existente; conteúdo diferente → `409`.
- A unicidade é por consumidor (`client_id`), pois o `externalId` pertence ao sistema de origem.

## Alternativas consideradas
- *Lock pessimista / `SERIALIZABLE`*: não há linha para travar antes de existir; serializable gera
  falhas de serialização a tratar do mesmo jeito, com mais custo.
- *`INSERT … ON CONFLICT DO NOTHING`*: ótimo no PostgreSQL, mas específico do banco; a abordagem
  escolhida funciona em qualquer banco relacional e no H2 dos testes.
- *Lock distribuído (Redis)*: mais infraestrutura e ainda precisaria da constraint como garantia final.
