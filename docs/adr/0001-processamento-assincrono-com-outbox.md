# ADR 0001 — Processamento assíncrono usando a própria tabela como outbox

**Status:** aceito

## Contexto
O provedor externo leva ~400 ms e pode degradar para segundos ou ficar fora do ar. Chamar o provedor
dentro do `POST /api/v1/requests` acopla a latência e a disponibilidade da nossa API à dele, e chamar
depois do commit sem nenhum registro perde solicitações se a aplicação cair entre o commit e a chamada.

## Decisão
O `POST` grava a solicitação como `PENDING` e responde. Um worker agendado busca solicitações vencidas
(`status IN (PENDING, PROCESSING) AND next_attempt_at <= now`) e processa cada uma em três passos:
reserva em transação curta (`PROCESSING` + lease), chamada HTTP fora de transação, aplicação do
resultado em transação curta condicionada à versão reservada.

## Consequências
- API rápida e estável mesmo com o provedor lento; consumidor acompanha o status pelo `GET`.
- Nenhuma solicitação gravada é perdida: o estado está no banco; lease vencido = reconciliação.
- Latência até `COMPLETED` inclui o intervalo de polling (1 s). Aceitável aqui; se não fosse, um evento
  após o commit poderia acordar o worker imediatamente, mantendo o polling como rede de segurança.
- Sem broker de mensagens para operar. Com volume muito alto, migraria para fila (SQS/Kafka) alimentada
  por outbox transacional.
