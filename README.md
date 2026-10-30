# Activation Service

Serviço que recebe solicitações de ativação de produtos vindas de outros sistemas, guarda cada uma, ativa o
produto chamando um provedor externo e permite consultar o andamento.

O fluxo em uma frase: o sistema de origem faz `POST`, recebe na hora o id com status `PENDING`, e um job em
segundo plano chama o provedor e move a solicitação para `COMPLETED` ou `FAILED`. O sistema de origem acompanha
pelo `GET`.

**Stack:** Java 21, Spring Boot 3.5 (MVC, Data JPA, Security Resource Server, Actuator), PostgreSQL, Liquibase,
Maven e Docker. Testes com JUnit 5, Mockito, MockMvcTester, WireMock, Awaitility, Testcontainers e ArchUnit.

## Sumário

1. [Como executar](#como-executar)
2. [API](#api)
3. [Ciclo de vida da solicitação](#ciclo-de-vida-da-solicitação)
4. [Duplicidade](#duplicidade)
5. [Integração com o provedor](#integração-com-o-provedor)
6. [Concorrência](#concorrência)
7. [Datas e fuso horário](#datas-e-fuso-horário)
8. [Desempenho](#desempenho)
9. [Memória](#memória)
10. [Segurança](#segurança)
11. [Banco de dados](#banco-de-dados)
12. [Organização do código](#organização-do-código)
13. [Estratégia de testes](#estratégia-de-testes)
14. [Observabilidade](#observabilidade)
15. [Cenário de análise](#cenário-de-análise)
16. [Produção](#produção)
17. [Pontos pendentes e melhorias futuras](#pontos-pendentes-e-melhorias-futuras)
18. [Uso de IA](#uso-de-ia)

## Como executar

Precisa do JDK 21. O Maven vem pelo wrapper.

```bash
./mvnw verify
```

O `verify` compila e roda os testes unitários e de integração. Os testes com PostgreSQL usam Testcontainers e
são pulados quando não há Docker; os outros testes de integração usam H2 com o mesmo changelog do Liquibase.

### Com Docker

```bash
docker compose up --build
```

Sobe a aplicação, um PostgreSQL e um WireMock no papel do provedor.

### Sem Docker

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Usa H2 em memória e espera o provedor em `http://localhost:8089`, que é o WireMock do compose.

### Testando na mão

O desafio não tem servidor de autenticação, então o perfil `local` (usado também pelo compose) habilita
`/dev/token`, que emite tokens de teste. Esse endpoint fica isolado no pacote `localdev` e nunca é habilitado
em produção.

```bash
TOKEN=$(curl -s -X POST "http://localhost:8080/dev/token?clientId=system-a" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

curl -i -X POST http://localhost:8080/api/v1/requests -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

Os mappings do WireMock em `docker/wiremock/mappings` respondem de acordo com o `productCode`, para dar para
ver cada caminho do fluxo:

| `productCode` contém | Resposta do mock | Resultado esperado |
|---|---|---|
| `REJECT` | 400 | `FAILED` com `PROVIDER_REJECTED_400`, sem nova tentativa |
| `ERROR` | 500 | novas tentativas com backoff até `FAILED` com `MAX_ATTEMPTS_EXCEEDED:PROVIDER_HTTP_500` |
| `SLOW` | 200 depois de 5 s | cada chamada estoura o timeout de 3 s; repete com a mesma `Idempotency-Key` até `FAILED` com `MAX_ATTEMPTS_EXCEEDED:PROVIDER_TIMEOUT` |
| `BUSY` | 503 com `Retry-After: 3` | espera os 3 s pedidos pelo provedor antes de cada nova tentativa |
| qualquer outro | 200 | `COMPLETED` |

### Variáveis de ambiente

| Variável | Padrão | Para que serve |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | obrigatórias (no perfil `local`, H2) | conexão com o PostgreSQL |
| `DB_POOL_MAX_SIZE` | `10` | conexões no pool do Hikari |
| `JWT_ISSUER_URI` | vazio | emissor dos tokens; o JWKS é lido dele |
| `JWT_AUDIENCE` | `activation-service` | audiência exigida no token |
| `PROVIDER_BASE_URL` | obrigatória (no perfil `local`, `http://localhost:8089`) | endereço do provedor |
| `PROVIDER_CONNECT_TIMEOUT` | `2s` | tempo máximo para abrir a conexão |
| `PROVIDER_READ_TIMEOUT` | `3s` | tempo máximo esperando a resposta |
| `PROVIDER_API_KEY` | vazio | chave enviada no header `X-Api-Key` |
| `PROCESSING_JOB_ENABLED` | `true` | liga o job; com `false`, a instância só atende a API |
| `TZ` | `America/Sao_Paulo` na imagem | fuso da JVM (veja [Datas e fuso horário](#datas-e-fuso-horário)) |
| `SPRING_PROFILES_ACTIVE` | `prod` na imagem | `prod` liga log em JSON; `local` liga H2 e `/dev/token` |

## API

| Método | Caminho | Escopo | Resposta |
|---|---|---|---|
| POST | `/api/v1/requests` | `activation:write` | `201` quando cria, `200` no retry de uma existente |
| GET | `/api/v1/requests/{id}` | `activation:read` | `200` ou `404` |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | `200` com a página |

Exemplo de resposta:

```json
{
  "id": "5f0c6a8e-3d1b-4b7e-9a51-2f8d0c7e4a10",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "COMPLETED",
  "attempts": 1,
  "createdAt": "2026-10-30T17:30:33.141Z",
  "updatedAt": "2026-10-30T17:30:34.002Z"
}
```

- `externalId`, `customerId` e `productCode` são obrigatórios e têm até 64 caracteres, o tamanho da coluna.
- `page` começa em 0. `size` vai de 1 a 100 (padrão 20); acima de 100 responde 400, para nenhuma consulta
  trazer um volume sem limite.
- A listagem vem da mais recente para a mais antiga.
- Datas em UTC, ISO-8601, com até milissegundos.
- `failureReason` só aparece quando há falha. Campos internos (versão, sistema de origem, data da próxima
  tentativa) nunca saem na resposta.
- Erros seguem Problem Details (RFC 9457) e trazem `traceId`, o mesmo id de correlação dos logs. Stack trace e
  mensagens internas nunca são devolvidos.
- Erros tratados: dados inválidos (400), sem token ou sem escopo (401/403), não encontrado (404), duplicidade
  (409) e erro inesperado (500).
- Erro do provedor nunca chega ao POST, porque a chamada é assíncrona. Ele aparece no `status` e no
  `failureReason` da solicitação (por exemplo, `PROVIDER_REJECTED_400`).

## Ciclo de vida da solicitação

```
            POST
             │
             ▼
         PENDING ◄──────────────┐
             │ job reserva      │ falha temporária
             ▼                  │ (com backoff)
        PROCESSING ─────────────┘
          │      │
   sucesso│      │falha definitiva ou tentativas esgotadas
          ▼      ▼
    COMPLETED  FAILED
```

- O status só muda pelos métodos da entidade (`iniciarProcessamento`, `concluir`, `falhar`,
  `agendarNovaTentativa`). Eles recusam transições inválidas: `COMPLETED` e `FAILED` são finais e nunca voltam
  para `PROCESSING`.
- `dt_proximatentativa` tem dois papéis. Em `PENDING`, indica quando a solicitação pode ser processada. Em
  `PROCESSING`, indica quando a reserva vence.
- `qt_tentativa` conta as chamadas feitas ao provedor e aparece como `attempts`.

## Duplicidade

Quem garante a regra é o índice único `(cd_sistemaorigem, cd_externo)`. O service primeiro procura um registro
existente, que é o caso comum de retry, e só depois insere. A consulta sozinha não bastaria, porque duas
requisições podem consultar antes de qualquer uma inserir. Nesse caso o segundo insert falha no índice, e o
service relê o registro vencedor e responde como retry.

| Caso | Resposta | Justificativa |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo | `200` com o registro existente | É um retry legítimo: o sistema de origem recebe o mesmo resultado da primeira chamada e não precisa tratar erro. O `200` (em vez de `201`) indica que nada foi criado agora. |
| Mesmo `externalId`, conteúdo diferente | `409`, título "Solicitação duplicada", detalhe "O externalId '...' já foi usado com outro customerId ou productCode." e `existingRequestId` | Não é retry, é conflito. Devolver o registro antigo esconderia o erro, e criar outro quebraria a regra. |
| Duas requisições iguais ao mesmo tempo | uma recebe `201` e a outra `200` | O índice único deixa só um insert passar; a outra requisição relê o vencedor e cai no caso do retry. |

O `externalId` é único por sistema de origem, porque dois sistemas diferentes podem gerar o mesmo valor.

O `201` e o `200` do retry mostram exatamente as mesmas datas. A seção
[Datas e fuso horário](#datas-e-fuso-horário) explica por que isso precisou de cuidado.

## Integração com o provedor

O POST só grava a solicitação como `PENDING` e responde, então a API não depende da velocidade nem da
disponibilidade do provedor.

Um job roda a cada segundo e segue estes passos:

1. Busca de uma vez um lote de até 20 solicitações prontas: `PENDING` cuja data já chegou, ou `PROCESSING` com
   a reserva vencida.
2. Processa as solicitações do lote em paralelo, cada uma numa thread virtual.
3. Reserva cada solicitação, passando de `PENDING` para `PROCESSING` com reserva de 30 s. A gravação usa
   `@Version`, então só uma instância consegue reservar.
4. Chama o provedor fora de qualquer transação.
5. Grava o resultado.

A própria tabela funciona como outbox: a solicitação já está no banco antes da chamada, então nada se perde se
a aplicação parar entre o commit e a chamada. Se parar durante a chamada, a solicitação fica em `PROCESSING`
até a reserva vencer e é processada de novo.

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | Não | O provedor confirmou a ativação. |
| HTTP 400 (e os demais 4xx, exceto 401, 403, 408 e 429) e 3xx | `FAILED` (`PROVIDER_REJECTED_400`) | Não | O provedor recusou o conteúdo; repetir daria o mesmo resultado. |
| HTTP 500 (e os demais 5xx, 408) | `PENDING` com backoff | Sim | Falha temporária do provedor; depois de 5 tentativas vira `FAILED`. |
| Timeout / erro de rede | `PENDING` com backoff | Sim, com a mesma `Idempotency-Key` | Não dá para saber se ativou; a chave evita ativação duplicada no provedor. |
| HTTP 429 / 503 com `Retry-After` | `PENDING` até o `Retry-After` | Sim | O provedor disse quando volta a aceitar; tentar antes só aumenta a carga. |
| 401 / 403 | `PENDING` com backoff e log de erro | Sim | O problema é a nossa credencial, não a solicitação; volta a funcionar quando o segredo for corrigido. |

- **Timeouts:** 2 s para abrir a conexão e 3 s para a resposta, ambos configuráveis. Uma chamada nunca passa de
  5 s.
- **Idempotência:** timeout é ambíguo, porque o provedor pode ter ativado e só a resposta se perdeu. Por isso
  toda chamada envia `Idempotency-Key` com o id da solicitação, igual em todas as tentativas, e o provedor deve
  deduplicar por ela.
- **Backoff:** até 5 tentativas, com espera exponencial (2 s, 4 s, 8 s...) mais uma variação aleatória de até
  50%, limitada a 5 minutos. A variação evita que solicitações que falharam juntas voltem todas no mesmo
  instante. Depois da última tentativa, a solicitação fica `FAILED` com `MAX_ATTEMPTS_EXCEEDED:<motivo>`.
- **Retry-After:** quando o provedor informa, ele tem prioridade sobre o backoff, também com o limite de 5
  minutos.
- **Tradução das falhas:** o `IntegracaoErrorHandler` transforma qualquer falha HTTP numa `IntegracaoException`
  que já diz se permite nova tentativa. Um client novo montado pelo `HttpClientConfig` ganha o mesmo tratamento.

## Concorrência

O serviço pode rodar com várias instâncias, e o job processa em paralelo. Estas são as disputas possíveis e o
que resolve cada uma:

| Situação | O que acontece | O que garante |
|---|---|---|
| Dois POSTs iguais chegam juntos | Um grava e o outro relê o registro gravado | Índice único no banco |
| Duas instâncias pegam a mesma solicitação | A primeira reserva; a segunda recebe conflito de versão e pula a solicitação | `@Version` (lock otimista) |
| Outra instância altera a solicitação entre a leitura do lote e a reserva | A gravação falha por conflito de versão e a solicitação fica para a outra instância | `@Version` |
| A instância cai no meio da chamada | A reserva vence em 30 s e outra instância tenta de novo, com a mesma `Idempotency-Key` | Reserva com prazo e chave de idempotência |
| A mesma instância pegaria a solicitação duas vezes | Não acontece: o job usa `fixedDelay`, então o próximo lote só começa quando todas as chamadas do anterior terminam | `@Scheduled(fixedDelay)` e espera do executor |
| Várias solicitações criadas no mesmo milissegundo | A listagem desempata pelo id, então a ordem é estável e nenhum item repete ou some entre páginas | `ORDER BY dt_cadastro DESC, id DESC` |
| Relógios das instâncias um pouco diferentes | Uma instância adiantada poderia achar que a reserva de outra já venceu. A reserva de 30 s fica muito acima da chamada mais longa (5 s), e essa folga cobre a diferença | Margem entre a reserva e os timeouts |

Regra de configuração que vale manter: **a reserva precisa ser bem maior que o tempo máximo de uma chamada**
(`tempo-limite-conexao` + `tempo-limite-leitura`). Com uma reserva menor, outra instância poderia reprocessar uma
solicitação cuja chamada ainda está em andamento.

Por que lock otimista e não `SELECT ... FOR UPDATE`: o lock pessimista precisaria segurar a transação, e com ela
uma conexão do pool, durante toda a chamada ao provedor. Com `@Version`, a reserva é um `UPDATE` curto e a
chamada acontece sem conexão aberta. Para um volume bem maior, a evolução natural é usar `FOR UPDATE SKIP LOCKED`
só na reserva, como está em melhorias futuras.

## Datas e fuso horário

- Todas as datas são `Instant`, ou seja, um ponto no tempo sem fuso. No banco são `TIMESTAMP WITH TIME ZONE`,
  gravadas em UTC (`hibernate.jdbc.time_zone=UTC`). Na API saem em ISO-8601 com `Z`.
- O `TZ` do container (`America/Sao_Paulo`) só muda o fuso dos logs e o fuso do `Clock`. Ele não muda nenhum
  valor gravado nem devolvido, então trocar o `TZ` não desloca nenhuma data.
- Todo código que precisa da hora usa o bean `Clock`, nunca `Instant.now()`. Assim os testes fixam a hora e
  conferem backoff e reserva com valores exatos.

### Precisão em milissegundos

O `Clock` da aplicação avança de milissegundo em milissegundo (`Clock.tickMillis`). Sem isso, existia um bug:

- O Java 21 gera instantes com mais casas do que o banco guarda, por exemplo `17:30:33.141594900`.
- O PostgreSQL (e o H2) guarda só microssegundos e **arredonda** o valor para `17:30:33.141595`.
- O `POST` que criava a solicitação devolvia a data que estava em memória, enquanto o `GET` e o retry (`200`)
  devolviam a data lida do banco, já arredondada. A mesma solicitação aparecia com dois `createdAt` diferentes,
  e o arredondamento podia até empurrar a data um pouco para o futuro.

O `SolicitacaoAtivacaoDuplicadaIT` reproduziu exatamente esse caso (`expected .141594900Z but was .141595Z`) e
agora confere que o `201` e o `200` devolvem a mesma data. A escolha foi milissegundo, e não microssegundo,
porque é a precisão que a maioria dos clientes lê sem perda (o `Date` do JavaScript, por exemplo).

Com milissegundos, duas solicitações podem ter o mesmo `createdAt`. Por isso a listagem desempata pelo id (veja
[Concorrência](#concorrência)).

## Desempenho

### API

- O POST faz no máximo uma consulta e um insert, e nunca espera o provedor.
- O GET por id é uma consulta pela chave primária junto com o sistema de origem.
- A listagem é uma consulta paginada mais a contagem, as duas cobertas por índice e sempre filtradas pelo
  sistema de origem.
- As requisições rodam em threads virtuais (`spring.threads.virtual.enabled=true`), então uma requisição
  esperando o banco não ocupa uma thread do sistema operacional.

### Job

- **Uma consulta por lote.** O lote inteiro vem numa consulta só, pelo índice
  `(fl_situacao, dt_proximatentativa)`. Não há consulta por item para recarregar a solicitação: a reserva grava
  a própria entidade lida no lote.
- **Chamadas em paralelo.** Cada solicitação do lote chama o provedor numa thread virtual, então o lote dura o
  tempo da chamada mais lenta, não a soma de todas. Com o provedor em 400 ms e lotes de 20, uma instância passa
  de cerca de 2 ativações por segundo em sequência (20 × 0,4 s mais 1 s de intervalo) para cerca de 14 em
  paralelo (20 a cada 0,4 s mais 1 s).
- **Conexão do banco só pelo tempo necessário.** Cada item usa uma conexão por alguns milissegundos, na reserva
  e na gravação do resultado. Durante a chamada ao provedor nenhuma conexão fica presa, por isso 20 chamadas em
  paralelo cabem num pool de 10 conexões.
- **Cliente HTTP compatível com threads virtuais.** O cliente do provedor usa o `HttpClient` do JDK
  (`JdkClientHttpRequestFactory`). O `HttpURLConnection`, usado antes, tem blocos `synchronized` por dentro. No
  Java 21, uma thread virtual bloqueada dentro de `synchronized` prende a thread do sistema que a executa
  (*pinning*). Com várias chamadas lentas ao mesmo tempo, isso esgotaria essas threads, que são poucas (uma por
  núcleo), e travaria também as requisições da API. O `HttpClient` do JDK não tem esse problema e ainda
  reaproveita as conexões com o provedor.
- **O lote é o limite de chamadas simultâneas.** `tamanho-lote` também é o número máximo de chamadas ao provedor
  ao mesmo tempo por instância. Aumentar o lote aumenta a vazão, mas também a carga sobre o provedor.

### Pequenos cuidados

- A regex que valida o `X-Correlation-Id` é compilada uma vez só, e não a cada requisição.
- O backoff usa `ThreadLocalRandom`, que não disputa estado entre threads.

## Memória

- **Nada cresce sem limite na aplicação.** Não há cache em memória nem coleção estática que acumule dados. O job
  carrega no máximo `tamanho-lote` entidades por vez, descartadas no fim da rodada, e a listagem traz no máximo
  100 itens.
- **Contexto de log sempre limpo.** O id de correlação fica no MDC, que é por thread. O filtro HTTP e o job
  removem o valor num `finally`, então nenhum id fica preso numa thread nem vaza para outra tarefa.
- **Sessão JPA só durante a transação.** Com `spring.jpa.open-in-view=false`, a sessão do Hibernate fecha junto
  com a transação, em vez de durar até o fim da requisição.
- **Um cliente HTTP para a aplicação toda.** O `HttpClient` é criado uma vez, junto com o bean, e reutilizado em
  todas as chamadas.
- **Executor do job fechado a cada rodada.** O executor de threads virtuais é aberto com try-with-resources, que
  espera as tarefas e o fecha. Threads virtuais não ficam paradas esperando num pool.
- **Limites da JVM no container.** A imagem usa `-XX:MaxRAMPercentage=75`, para o heap respeitar o limite do
  container, e `-XX:+ExitOnOutOfMemoryError`, para o orquestrador reiniciar a instância em vez de ela continuar
  rodando em estado ruim. O compose usa as mesmas opções.
- **O que cresce é a tabela.** Solicitações finalizadas ficam no banco para sempre; uma rotina de retenção está
  em [pontos pendentes](#pontos-pendentes-e-melhorias-futuras).

## Segurança

- OAuth 2.0 Client Credentials, porque quem consome são outros sistemas, não pessoas.
- O serviço é Resource Server: valida a assinatura do JWT (JWKS do `JWT_ISSUER_URI`), o emissor, a audiência e
  a expiração.
- `activation:write` cria e `activation:read` consulta.
- Cada solicitação pertence ao client do token (`SistemaAutenticadoService`). Toda leitura filtra por ele, e uma
  solicitação de outro sistema responde `404` em vez de `403`, para não revelar que o id existe (proteção contra
  BOLA).
- A chave do provedor vem de `PROVIDER_API_KEY`, injetada pelo secret manager do cloud e rotacionada lá. Ela
  nunca fica no repositório nem aparece em log: o `toString` das properties a mascara.
- O `X-Correlation-Id` recebido só é aceito com até 64 caracteres, só letras, números e hífen; fora disso, um
  novo é gerado. Assim ninguém injeta conteúdo nos logs por esse header.

## Banco de dados

O schema é criado pelo Liquibase (`src/main/resources/db/changelog`). Nos testes, o Hibernate valida a entidade
contra esse schema (`ddl-auto=validate`), então uma divergência quebra o build.

| Índice | Atende |
|---|---|
| Único `(cd_sistemaorigem, cd_externo)` | regra de duplicidade |
| `(fl_situacao, dt_proximatentativa)` | consulta do lote do job |
| `(cd_sistemaorigem, fl_situacao, dt_cadastro)` | listagem filtrada por status |
| `(cd_sistemaorigem, cd_cliente, dt_cadastro)` | listagem filtrada por cliente |

Uma constraint `CHECK` limita `fl_situacao` aos quatro valores do enum.

## Organização do código

| Pacote | Papel |
|---|---|
| `api` | contrato do controller e implementação |
| `service` | regra de negócio |
| `repository` | Spring Data e a entidade |
| `intercomm` | client do provedor |
| `job` | processamento agendado |
| `config` | segurança, cliente HTTP, relógio, tratamento de erro |
| `localdev` | emissor de token de teste, só no perfil `local` |

Controller e job chamam services; services chamam o repository e o client do provedor. O ArchUnit confere esse
sentido e também que nenhum código fora de `localdev` depende dele.

O código está em português. Ficam em inglês só os nomes do contrato do desafio: campos do JSON, valores de
status, rotas, headers, escopos e códigos de motivo de falha.

As decisões maiores estão registradas em `docs/adr`.

## Estratégia de testes

| Tipo | O que cobre |
|---|---|
| Unitário | transições da entidade; decisões do processamento (sucesso, recusa, nova tentativa, Retry-After, última tentativa, reserva disputada, reserva expirada); um item com erro inesperado não interrompe o resto do lote |
| Client HTTP contra WireMock | headers enviados e como cada resposta, timeout ou erro de rede vira `IntegracaoException` |
| Fatia web | contrato HTTP, validação, 401/403 e formato do erro |
| Integração com H2 | duplicidade, inclusive com 16 threads ao mesmo tempo; mesma data no `201` e no retry; fluxo completo API → job → WireMock (sucesso, 400, 500 até esgotar, timeout e depois sucesso); JWT e isolamento entre sistemas |
| Integração com PostgreSQL (Testcontainers) | duplicidade concorrente e filtros da listagem no banco real |
| ArchUnit | sentido das dependências entre pacotes |

## Observabilidade

- `/actuator/health/liveness` e `/actuator/health/readiness`; a readiness confere o banco.
- O `X-Correlation-Id`, recebido ou gerado, vai em todas as linhas de log e no `traceId` dos erros. Ele também
  fica gravado na solicitação e é enviado ao provedor pelo job, então dá para seguir uma ativação desde a
  requisição original até a chamada ao provedor.
- `/actuator/prometheus` expõe a latência da API (`http_server_requests_seconds`), a latência e os erros do
  provedor (`http_client_requests_seconds`) e o pool de conexões (`hikaricp_connections_*`).
- Logs em JSON (ECS) no perfil `prod`. Tokens e chaves nunca são logados.

## Cenário de análise

API de 300 ms para 2,5 s, CPU 45%, memória 60%, algumas consultas de 50 ms para 1,2 s, provedor de 400 ms para
3 s.

1. **O que investigaria primeiro:** o que mudou quando a lentidão começou (deploy, tráfego, provedor, volume de
   dados) e quais endpoints pioraram. CPU e memória normais sugerem que a aplicação está esperando, não
   trabalhando mais.
2. **Aplicação, banco ou provedor:** tracing ou tempo por etapa de uma requisição lenta. Se o banco mede 50 ms
   (`pg_stat_statements`) mas a aplicação vê 1,2 s, o tempo está na espera por conexão do pool. A latência do
   provedor vem das métricas do client HTTP. Um thread dump mostra onde as threads estão paradas.
3. **Métricas úteis:** p95/p99 por endpoint, latência e taxa de erro do provedor, pool do Hikari (ativas,
   pendentes, tempo de aquisição), consultas lentas e seus planos, quantidade de solicitações `PENDING` e logs
   com o id de correlação.
4. **Melhorias:** manter a chamada ao provedor fora da requisição e fora de transação (já feito aqui), timeouts
   curtos e circuit breaker, backoff com variação aleatória, índices para as consultas lentas e pool bem
   dimensionado.
5. **Causa comum:** provedor lento chamado segurando uma conexão do banco. Cada chamada prende a conexão por
   mais tempo, o pool esgota, as consultas esperam conexão (parecem lentas, embora o banco seja rápido) e todos
   os endpoints ficam lentos com CPU baixa. Confirmaria vendo as conexões pendentes do Hikari subirem junto com
   a latência do provedor, o tempo das consultas normal no banco e sessões `idle in transaction`. Com threads
   virtuais no Java 21, outra causa com o mesmo sintoma é o *pinning*: chamadas bloqueantes dentro de
   `synchronized` prendendo as threads do sistema. O thread dump e o evento `jdk.VirtualThreadPinned` do JFR
   mostram isso.
6. **Validar a correção:** publicação canário comparada com a linha de base: p95 de volta perto de 300 ms, sem
   conexões pendentes e consultas perto de 50 ms. Antes de publicar, um teste de carga com um WireMock lento.

## Produção

- **Imagem:** o `Dockerfile` faz o build em dois estágios e roda só com JRE, com usuário não-root, heap pela
  memória do container, healthcheck e desligamento gracioso.
- **Execução:** Kubernetes ou ECS com 2 réplicas ou mais, liveness e readiness no Actuator, PostgreSQL
  gerenciado e Liquibase na subida.
- **Configuração:** por variáveis de ambiente (veja a [tabela](#variáveis-de-ambiente)), com
  `SPRING_PROFILES_ACTIVE=prod`. `DB_PASSWORD` e `PROVIDER_API_KEY` vêm do secret manager.
- **Várias instâncias:** funcionam sem mudança, porque a duplicidade é barrada pelo banco e a reserva do job usa
  `@Version`. Dá para ter instâncias só de API com `PROCESSING_JOB_ENABLED=false`.
- **Desligamento:** o Tomcat para de aceitar requisições e espera as que estão em andamento por até 20 s. Uma
  chamada ao provedor interrompida no meio fica em `PROCESSING` e é retomada quando a reserva vence.
- **Avisar outros sistemas:** um evento publicado quando a solicitação conclui, gravado na mesma transação e
  publicado a partir de uma tabela de saída (outbox).
- **CI/CD:** `./mvnw verify`, build da imagem, scan de vulnerabilidades, push, deploy em homologação e depois em
  produção. O workflow do GitHub Actions cobre build, testes (inclusive os de PostgreSQL) e build da imagem.

## Pontos pendentes e melhorias futuras

Pendente, com o caminho que eu seguiria:

- **Rate limit e limite de tamanho do corpo:** no API Gateway.
- **Circuit breaker para o provedor:** Resilience4j no service do provedor, para parar de chamar enquanto ele
  estiver fora.
- **Tracing distribuído:** Micrometer Tracing com OpenTelemetry.
- **Métrica de solicitações por status:** um gauge do Micrometer com a contagem por `fl_situacao`, consultada com
  cache curto para não pesar no banco a cada coleta do Prometheus.
- **Solicitações que esgotaram as tentativas:** alerta e um endpoint de reprocessamento.
- **Retenção:** expurgo ou arquivamento das solicitações finalizadas depois de um prazo, para a tabela não
  crescer sem limite.
- **Ambiente:** `docker build` e `docker compose` não rodaram na minha máquina (sem Docker); o CI faz o build da
  imagem e roda os testes com PostgreSQL.

Melhorias futuras: eventos de conclusão para outros serviços, paginação por cursor, `SELECT ... FOR UPDATE SKIP
LOCKED` na reserva para alto volume, documentação OpenAPI e alertas sobre o tamanho da fila e a taxa de falha.

## Uso de IA

O Claude Code (Anthropic) ajudou a ler o desafio e a escrever código, testes, arquivos de Docker/CI e este
README. Ele trabalhou com uma skill particular de Spring Boot, escrita por mim, com as minhas convenções de
código.

A arquitetura e os padrões do projeto são decisão minha: a divisão em controller, service, repository e
intercomm, a nomenclatura, a organização dos pacotes e a forma de tratar erros e integrações. São padrões que eu
mesmo defini e já validei em vários projetos. A IA seguiu esses padrões, sem escolhê-los.

Revisei as decisões e o código e validei com `./mvnw verify` e testes manuais.
