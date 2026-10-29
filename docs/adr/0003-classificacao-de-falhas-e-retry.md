# ADR 0003 — Classificação das respostas do provedor e política de retry

**Status:** aceito

## Decisão
- 2xx → `COMPLETED`. 4xx (exceto 401/403/408/429) → `FAILED` sem retry.
- 5xx, 408, timeout, erro de rede, 401/403 → retry. 429/503 → retry respeitando `Retry-After`.
- Falha temporária volta para `PENDING` com `next_attempt_at` (backoff exponencial com jitter, teto 5 min).
- Máximo de 5 tentativas; depois `FAILED` com `MAX_ATTEMPTS_EXCEEDED:<motivo>`.
- Toda chamada envia `Idempotency-Key = id da solicitação`, porque timeout não diz se a ativação ocorreu.
- O resultado é um `sealed interface ProviderOutcome` (`Success`, `PermanentFailure`, `TransientFailure`)
  tratado com `switch` exaustivo: um novo tipo de resultado não compila sem ser tratado.

## Consequências
- `PROCESSING` significa apenas "um worker está com ela agora", o que torna a reconciliação trivial.
- Depende de o provedor deduplicar pela chave de idempotência; sem isso, seria preciso consultar o
  status no provedor antes de reenviar após timeout.
