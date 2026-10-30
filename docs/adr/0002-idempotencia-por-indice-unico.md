# ADR 0002: idempotência pelo índice único (sistema de origem, externalId)

Status: aceito

## Contexto

Os sistemas de origem fazem retry e podem mandar a mesma solicitação em paralelo. Consultar se existe e depois
inserir deixa uma janela: duas requisições passam pela consulta antes de qualquer INSERT e as duas inserem.

## Decisão

- O índice único `(cd_sistemaorigem, cd_externo)` é a garantia.
- A aplicação consulta primeiro (o caso comum do retry) e depois faz o INSERT; se o índice recusar, relê o
  registro vencedor numa transação nova.
- Mesmo conteúdo responde `200` com o registro existente; conteúdo diferente responde `409`.
- A unicidade é por sistema de origem, porque o `externalId` pertence a ele.

## Alternativas

- Lock pessimista ou `SERIALIZABLE`: não há linha para travar antes de ela existir, e o serializable gera falhas
  que precisariam do mesmo tratamento, com custo maior.
- `INSERT ... ON CONFLICT DO NOTHING`: bom no PostgreSQL, mas específico dele; a solução escolhida funciona em
  qualquer banco relacional e no H2 dos testes.
- Lock distribuído (Redis): mais infraestrutura, e o índice continuaria sendo a garantia final.
