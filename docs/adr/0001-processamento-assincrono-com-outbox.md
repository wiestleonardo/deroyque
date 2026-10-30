# ADR 0001: processamento assíncrono usando a própria tabela como outbox

Status: aceito

## Contexto

O provedor leva cerca de 400 ms e pode passar para segundos ou cair. Chamar o provedor dentro do
`POST /api/v1/requests` prende a latência e a disponibilidade da API às dele. Chamar depois do commit, sem
registro do que falta enviar, perde a solicitação se a aplicação cair entre o commit e a chamada.

## Decisão

O POST grava a solicitação como `PENDING` e responde. Um job agendado busca as solicitações prontas (`PENDING`,
ou `PROCESSING` com a reserva vencida), reserva cada uma gravando com `@Version`, chama o provedor fora de
transação e grava o resultado. Se outra instância tiver alterado a solicitação nesse meio-tempo, o `@Version`
recusa a gravação.

As solicitações do lote são processadas em paralelo, cada uma numa thread virtual, e o lote seguinte só começa
quando todas terminam. O cliente HTTP é o do JDK, que não prende a thread do sistema durante a espera
(*pinning*), ao contrário do `HttpURLConnection`.

## Consequências

- A API continua rápida com o provedor lento; o consumidor acompanha pelo GET.
- Nada gravado se perde: o estado está no banco e uma reserva vencida é processada de novo.
- O tempo até `COMPLETED` inclui o intervalo do job (1 s).
- Não há broker para operar. Com muito volume, passaria para uma fila (SQS, Kafka) alimentada por outbox.
