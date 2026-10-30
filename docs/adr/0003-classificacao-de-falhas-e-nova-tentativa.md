# ADR 0003: classificação das respostas do provedor e política de nova tentativa

Status: aceito

## Decisão

- 2xx: `COMPLETED`. Qualquer outro status, inclusive 3xx, é falha. 4xx (exceto 401, 403, 408 e 429): `FAILED` sem
  nova tentativa.
- 5xx, 408, timeout e erro de rede: nova tentativa. 401 e 403 também, porque o problema é a nossa credencial e as
  solicitações devem se recuperar quando o segredo for corrigido. 429 e 503: nova tentativa respeitando o
  `Retry-After` (em segundos).
- A classificação fica no `IntegracaoErrorHandler`, aplicado ao `RestClient` no `HttpClientConfig`, e sempre
  termina numa `IntegracaoException` dizendo se a falha permite nova tentativa.
- Falha com nova tentativa volta para `PENDING` com backoff exponencial e variação aleatória, limitado a 5 minutos.
- No máximo 5 tentativas; depois `FAILED` com `MAX_ATTEMPTS_EXCEEDED:<motivo>`.
- Toda chamada envia `Idempotency-Key` com o id da solicitação, porque o timeout não diz se a ativação aconteceu.

## Consequências

- `PROCESSING` significa só "um worker está com ela agora", o que simplifica a reconciliação.
- Depende de o provedor deduplicar pela chave de idempotência; sem isso, seria preciso consultar o status no
  provedor antes de reenviar depois de um timeout.
