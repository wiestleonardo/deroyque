# ADR 0001: processamento assíncrono usando a própria tabela como outbox

Status: aceito

## Contexto

O provedor leva cerca de 400 ms e pode passar para segundos ou cair. Chamar o provedor dentro do
`POST /api/v1/requests` prende a latência e a disponibilidade da API às dele. Chamar depois do commit,
sem registro do que falta enviar, perde a solicitação se a aplicação cair entre o commit e a chamada.

## Decisão

O POST grava a solicitação como `PENDING` e responde. Um job agendado busca as solicitações prontas
(`PENDING` ou `PROCESSING` com a próxima tentativa vencida) e processa cada uma em três passos: reserva
numa transação curta, chamada HTTP fora de transação e gravação do resultado numa transação curta,
só se a versão ainda for a da reserva.

## Consequências

- A API continua rápida com o provedor lento; o consumidor acompanha pelo GET.
- Nada gravado se perde: o estado está no banco e uma reserva vencida é reprocessada.
- O tempo até `COMPLETED` inclui o intervalo do job (1 s). Se isso fosse problema, um evento depois do
  commit poderia acordar o job, mantendo o polling como garantia.
- Não há broker para operar. Com muito volume, passaria para fila (SQS, Kafka) alimentada por outbox.
