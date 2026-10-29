# ADR 0003: classificação das respostas do provedor e política de retentativa

Status: aceito

## Decisão

- 2xx: `COMPLETED`. 4xx (fora 401, 403, 408 e 429): `FAILED` sem nova tentativa.
- 5xx, 408, timeout, erro de rede, 401 e 403: nova tentativa. 429 e 503: nova tentativa respeitando o
  `Retry-After`.
- Falha temporária volta para `PENDING` com a próxima tentativa no futuro (backoff exponencial com
  jitter, teto de 5 min).
- No máximo 5 tentativas; depois `FAILED` com `MAX_ATTEMPTS_EXCEEDED:<motivo>`.
- Toda chamada envia `Idempotency-Key` com o id da solicitação, porque o timeout não diz se a ativação
  aconteceu.
- O resultado é o `sealed interface ResultadoAtivacaoVO` (`Sucesso`, `FalhaDefinitiva`,
  `FalhaTemporaria`), tratado com `switch` exaustivo: um tipo novo de resultado não compila sem
  tratamento.

## Consequências

- `PROCESSING` significa só "um worker está com ela agora", o que simplifica a reconciliação.
- Depende de o provedor deduplicar pela chave de idempotência; sem isso, seria preciso consultar o
  status no provedor antes de reenviar depois de um timeout.
