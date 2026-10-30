# Activation Service

Serviço que registra solicitações de ativação de produtos, permite consultar o status e faz a ativação
chamando um provedor externo.

Stack: Java 21, Spring Boot 3.5 (MVC, Data JPA, Security Resource Server, Actuator), PostgreSQL,
Liquibase, Maven e Docker. Testes com JUnit 5, Mockito, AssertJ, MockMvcTester, WireMock, Awaitility,
Testcontainers e ArchUnit.

Seções: [Como executar](#como-executar) · [API](#api) · [Decisões técnicas](#decisões-técnicas) ·
[Testes](#estratégia-de-testes) · [Observabilidade](#observabilidade) ·
[Cenário de análise](#cenário-de-análise) · [Produção](#produção) · [Pontos pendentes](#pontos-pendentes) ·
[Melhorias futuras](#melhorias-futuras) · [Uso de IA](#uso-de-ia)

As decisões principais também estão registradas em [`docs/adr`](docs/adr).

## Como executar

Precisa do JDK 21. O Maven vem pelo wrapper e o Docker é opcional.

Build e todos os testes:

```bash
./mvnw verify
```

Roda os testes unitários (`*Test`, surefire) e os de integração (`*IT`, failsafe). Os testes contra
PostgreSQL real usam Testcontainers e são pulados quando não há Docker; os outros testes de integração
usam H2 em modo PostgreSQL com o mesmo changelog Liquibase. No Windows, `mvnw.cmd verify`.

Rodar sem Docker (perfil `local`):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

O perfil `local` usa H2 em memória, um provedor simulado dentro da própria aplicação e um endpoint que
emite tokens de teste, já que o desafio não tem servidor de autenticação. Tudo isso fica atrás de
`local-dev.habilitado=true`, que não é ligado em produção. O segredo dos tokens locais vem de
`LOCAL_JWT_SECRET`; sem ele, a aplicação gera um aleatório a cada subida.

Ambiente completo (aplicação, PostgreSQL 16 e WireMock como provedor):

```bash
docker compose up --build
```

Testando com curl:

```bash
TOKEN=$(curl -s -X POST "http://localhost:8080/dev/token?clientId=system-a" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

curl -i -X POST http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"

curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

O provedor simulado (e os mappings do WireMock em [`docker/wiremock/mappings`](docker/wiremock/mappings))
responde conforme o `productCode`:

| productCode contém | Provedor responde | Status final |
|---|---|---|
| qualquer outro valor | 200 | `COMPLETED` |
| `REJECT` | 400 | `FAILED` na 1ª tentativa |
| `ERROR` | 500 sempre | `FAILED` após 5 tentativas |
| `SLOW` | demora 5 s (timeout de leitura é 3 s) | local: timeout e depois `COMPLETED`; compose: `FAILED` após as tentativas |
| `BUSY` | 503 com `Retry-After: 3` | local: `COMPLETED` na 2ª tentativa; compose: `FAILED` após as tentativas |

Endpoints públicos de operação: `/actuator/health/liveness`, `/actuator/health/readiness` e
`/actuator/prometheus`.

## API

| Método | Caminho | Escopo | Descrição |
|---|---|---|---|
| POST | `/api/v1/requests` | `activation:write` | Cria a solicitação (idempotente por `externalId`) |
| GET | `/api/v1/requests/{id}` | `activation:read` | Consulta uma solicitação |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | Lista paginada, mais recentes primeiro |

Respostas do POST:

| Situação | Status |
|---|---|
| Criada | `201 Created` com `Location: /api/v1/requests/{id}` |
| Retry com mesmo `externalId` e mesmo conteúdo | `200 OK` com o registro existente |
| Mesmo `externalId` com conteúdo diferente | `409 Conflict` com `existingRequestId` |
| Dados inválidos | `400` com a lista `errors` por campo |
| Corpo acima de 16 KB | `413` |
| Sem token ou token inválido / sem o escopo | `401` / `403` |

Validação de `externalId`, `customerId` e `productCode`: obrigatórios, até 64 caracteres, só letras,
números, `.`, `_` e `-`. Paginação: `page >= 0` e `size` entre 1 e 100 (padrão 20).

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "FAILED",
  "attempts": 1,
  "failureReason": "PROVIDER_REJECTED_400",
  "createdAt": "2026-10-26T14:03:11Z",
  "updatedAt": "2026-10-26T14:03:12Z"
}
```

Datas em UTC, ISO-8601. `failureReason` é um código estável, nunca a mensagem do provedor.

Erros seguem Problem Details (RFC 9457, `application/problem+json`) e trazem `traceId`, o mesmo
identificador de correlação que aparece nos logs e no header `X-Correlation-Id`. Nenhuma resposta tem
stack trace ou mensagem interna; erro inesperado vira `500` genérico e o detalhe fica no log.

## Decisões técnicas

### Organização

```
api            interface do controller com o contrato HTTP; api.impl só delega para o service
service        regra de negócio (criação/consulta e processamento das solicitações)
repository     Spring Data; repository.domain tem a entidade e o converter do status
intercomm      client @HttpExchange do provedor e o service que classifica cada resposta
job            rotina agendada que dispara o processamento
config         segurança, cliente HTTP, filtros, tratamento de erro, métricas, properties
dto / vo       contrato da API e objetos internos (resultado da chamada, política de retentativa)
enumerator     status da solicitação e catálogo de erros (código, mensagem, HTTP status)
localdev       emissor de token e provedor simulado, só com local-dev.habilitado=true
```

As dependências vão sempre no mesmo sentido (controller e job chamam service; service chama
repository e intercomm), e isso é conferido por testes ArchUnit. Textos de erro e de validação ficam em
`messages.properties`; rotas, headers e escopos ficam em classes de constantes.

### Estados e transições

```
PENDING -> PROCESSING -> COMPLETED
   ^           |
   +-----------+  falha temporária, com backoff
               +-> FAILED
```

O status só muda pelos métodos da entidade (`iniciarProcessamento`, `concluir`, `falhar`,
`agendarNovaTentativa`, `liberarReservaExpirada`); não há setter. Uma transição inválida, como
`COMPLETED` voltando para `PROCESSING`, é rejeitada, e isso é testado sem Spring.

Falha temporária volta para `PENDING`, com `attempts` incrementado e a próxima tentativa no futuro. Com
isso, `PROCESSING` significa só "um worker está com ela agora", e a reconciliação fica simples:
`PROCESSING` com a reserva vencida é sempre um worker que morreu.

A entidade tem `@Version` (lock otimista): se duas instâncias tentam reservar a mesma solicitação, só
uma grava e a outra pula.

### Duplicidade

Quem garante a unicidade é o índice único `(cd_sistemaorigem, cd_externo)` no banco. O service faz:

1. busca por (sistema de origem, externalId); se existe, resolve na hora (é o retry comum);
2. tenta o INSERT (`saveAndFlush`, em transação própria);
3. se o índice recusar, outra requisição ganhou a corrida: relê o registro vencedor e resolve.

Consultar e depois inserir não basta porque, entre o SELECT e o INSERT de uma requisição, outra
requisição igual pode fazer o próprio SELECT. As duas não acham nada e as duas inserem; não há linha
para travar, então o isolamento READ COMMITTED não ajuda. Só a restrição no banco é atômica entre
transações e instâncias. Por isso o método de criação não abre transação: a releitura depois da falha
precisa de uma transação nova (no PostgreSQL, uma transação que deu erro fica abortada).

| Cenário | Resposta | Motivo |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo | `200` com o registro existente | Retry é esperado e o cliente precisa do `id` e do status. `200` em vez de `201` mostra que nada foi criado. |
| Mesmo `externalId`, `customerId`/`productCode` diferente | `409` com `existingRequestId` | O cliente reaproveitou o identificador para outra coisa; não sobrescrevemos nem criamos outra. |
| Duas requisições iguais em paralelo | uma recebe `201`, a outra `200` com o mesmo registro | A que perde bate no índice, relê e cai no caso de retry. |

O `externalId` é único por sistema de origem, não globalmente: dois sistemas podem gerar o mesmo valor,
e com unicidade global o sistema B receberia o registro de A ou um 409 que revela que ele existe.

Testado com 16 threads contra H2 e 20 contra PostgreSQL (Testcontainers): sempre um registro só e
exatamente uma criação.

### Integração com o provedor

O POST só grava a solicitação como `PENDING` e responde. Um job agendado (a cada 1 s) busca as que
estão prontas e chama o provedor. Se o provedor ficar lento, a API continua rápida; o atraso aparece só
no tempo até `COMPLETED`, que o consumidor acompanha pelo GET.

A chamada HTTP nunca acontece com transação aberta. São três passos:

1. reserva, numa transação curta: `PENDING -> PROCESSING`, `attempts + 1` e reserva de 30 s;
2. chamada ao provedor, sem transação e sem conexão de banco presa;
3. gravação do resultado, numa transação curta, só se a versão do registro ainda for a da reserva.

A própria tabela funciona como outbox: depois do commit do POST, qualquer instância encontra o registro
`PENDING`. Se a instância cair durante a chamada, a solicitação fica em `PROCESSING` com a reserva
vencida e é reprocessada.

| Retorno do provedor | Novo status | Nova tentativa? | Por quê |
|---|---|---|---|
| 2xx | `COMPLETED` | Não | Produto ativado. |
| 400 e demais 4xx | `FAILED` (`PROVIDER_REJECTED_4xx`) | Não | O provedor recusou o conteúdo; repetir dá o mesmo resultado. |
| 500, 502, 504, 408 | `PENDING` com backoff; `FAILED` ao esgotar | Sim | Falha do provedor, normalmente passageira. |
| Timeout ou erro de rede | `PENDING` com backoff; `FAILED` ao esgotar | Sim, com a mesma `Idempotency-Key` | O resultado é desconhecido: o provedor pode ter ativado. |
| 429 ou 503 com `Retry-After` | `PENDING` para depois do `Retry-After` (teto de 5 min) | Sim | O provedor disse quando voltar. Aceita segundos ou data HTTP. |
| 401 ou 403 | `PENDING` com backoff e log de erro | Sim | O problema é a nossa credencial, não a solicitação. |

- Timeouts: conexão 2 s e leitura 3 s, configuráveis por `PROVIDER_CONNECT_TIMEOUT` e
  `PROVIDER_READ_TIMEOUT`. A reserva de 30 s é maior que a soma, para um worker vivo não perder a
  reserva no meio da chamada.
- Timeout ambíguo: toda chamada manda `Idempotency-Key` com o id da solicitação. Parto do princípio de
  que o provedor deduplica por essa chave (o simulado faz isso). Sem esse suporte, consultaria o status
  no provedor antes de reenviar.
- Limite: 5 chamadas, com backoff exponencial e jitter (2 s, 4 s, 8 s, 16 s, cada uma sorteada entre
  a metade e o valor cheio, com teto de 5 min). Ao esgotar, `FAILED` com
  `MAX_ATTEMPTS_EXCEEDED:<último motivo>`.
- O lote de cada ciclo (até 20) roda em virtual threads, com no máximo 5 chamadas simultâneas, para não
  disputar mais conexões do que o pool tem.
- Circuit breaker não foi implementado; ver [Pontos pendentes](#pontos-pendentes).

### Segurança

- Fluxo OAuth 2.0 Client Credentials: os consumidores são sistemas, sem usuário nem redirect. Cada
  sistema tem seu client e segredo (ou `private_key_jwt`) no servidor de autorização.
- O serviço é Resource Server e valida no JWT a assinatura (chaves do JWKS do emissor, descoberto por
  `JWT_ISSUER_URI`), o emissor, a audiência (`activation-service`) e a expiração.
- Escopos: `activation:write` cria; `activation:read` consulta e lista.
- Isolamento entre consumidores (BOLA): a solicitação guarda o `client_id` de quem criou, e consulta,
  listagem e unicidade sempre filtram por ele. Solicitação de outro sistema responde `404`, não `403`,
  para não revelar que existe.
- Credencial do provedor: vem de `PROVIDER_API_KEY`, que em produção é injetada a partir do secret
  manager do cloud, nunca do repositório ou da imagem. Para rotacionar, o provedor aceita duas chaves
  durante a troca, a nova vai para o secret manager e as instâncias reiniciam em rolling. A chave
  nunca aparece em log.
- Corpo limitado a 16 KB (`413`). Rate limit ficaria no API Gateway; ver pendências.
- No ambiente local, sem IdP, o modo `local-dev` usa HS256 com as mesmas validações de emissor,
  audiência e expiração. Os testes cobrem token expirado, adulterado, com emissor e audiência errados,
  sem escopo e o isolamento entre sistemas.

### Índices

| Índice | Uso |
|---|---|
| único `(cd_sistemaorigem, cd_externo)` | Idempotência e busca do retry. |
| `(fl_situacao, dt_proximatentativa)` | Busca do job e contagem por status. |
| `(cd_sistemaorigem, fl_situacao, dt_cadastro)` | Listagem filtrada por status, ordenada por data. |
| `(cd_sistemaorigem, cd_cliente, dt_cadastro)` | Listagem filtrada por cliente. |

Toda listagem começa pelo sistema de origem, por isso ele é a primeira coluna. Com muito volume, o
total da paginação fica caro e eu passaria para paginação por cursor. No PostgreSQL o índice do job
poderia ser parcial (só `PENDING` e `PROCESSING`); não fiz para manter o mesmo changelog no H2.

## Estratégia de testes

| Tipo | Classe | O que garante |
|---|---|---|
| Unitário | `SolicitacaoAtivacaoTest` | Transições, reserva, limite do motivo de falha. |
| Unitário | `PoliticaRetentativaVOTest` | Backoff exponencial, jitter, teto, Retry-After. |
| Unitário (Mockito) | `ProcessamentoSolicitacaoAtivacaoServiceImplTest` | Decisão por resultado, limite de tentativas, reserva disputada, reserva vencida. |
| Cliente HTTP contra WireMock | `ProvedorAtivacaoIntercommServiceImplTest` | Cada status HTTP, Retry-After, timeout, provedor fora do ar, headers enviados. |
| Fatia web (`@WebMvcTest`) | `SolicitacaoAtivacaoControllerTest` | Contrato HTTP: 201, 200, 409, 400, 404, 413, 500, 401 e 403. |
| Integração (H2 + Liquibase) | `SolicitacaoAtivacaoDuplicadaIT` | Idempotência no banco, inclusive com 16 threads. |
| Integração ponta a ponta | `ProcessamentoSolicitacaoAtivacaoIT` | API, banco, job e WireMock: sucesso, 400, 500 até esgotar, timeout e retry com a mesma chave. |
| Integração de segurança | `SegurancaIsolamentoIT` | Validação real do JWT, BOLA, filtros da listagem, health e métricas. |
| Integração PostgreSQL | `SolicitacaoAtivacaoPostgresIT` | Corrida de duplicidade e listagem no banco real (Testcontainers). |
| Arquitetura | `ArquiteturaTest` | Sentido das dependências entre pacotes. |

O unitário cobre a regra, que é o que mais muda; a fatia web cobre o contrato HTTP; a integração cobre
o que só aparece com banco e rede de verdade (corrida no índice, timeout, transações). Os testes rodam
com o fuso em UTC, como no contêiner. Nenhum teste depende de servidor de autenticação real.

## Observabilidade

- Health: `/actuator/health/liveness` e `/actuator/health/readiness`; só a readiness olha o banco.
- Correlação: o `X-Correlation-Id` recebido (validado) ou gerado vai para o MDC, para todas as linhas de
  log, para o `traceId` dos erros, fica gravado na solicitação e é reenviado ao provedor pelo job.
- Métricas em `/actuator/prometheus`: `http_server_requests_seconds` (latência da API),
  `activation_provider_calls_seconds{outcome}` (latência e erro do provedor),
  `http_client_requests_seconds`, `activation_requests{status}`,
  `activation_requests_transitions_total{status}`, além de `hikaricp_*` e `jvm_*`.
- Logs em JSON (ECS) no perfil `prod`. Token, segredo e chave do provedor não são logados.
- Tracing distribuído não foi implementado; com `micrometer-tracing-bridge-otel` e exporter OTLP, o
  `traceId` do OpenTelemetry substituiria o correlation id próprio.

## Cenário de análise

> API 300 ms para 2,5 s, CPU 45%, memória 60%, algumas consultas 50 ms para 1,2 s, provedor 400 ms para 3 s.

CPU e memória normais indicam que a aplicação não está trabalhando mais, está esperando alguma coisa:
I/O, conexão ou lock.

1. O que investigaria primeiro: quando começou e o que mudou naquele momento (deploy, tráfego, job,
   mudança no provedor, migração). Depois, quais endpoints pioraram e se a lentidão no banco é de
   execução da consulta ou de espera por conexão.

2. Como separar aplicação, banco e integração: decompondo o tempo de uma requisição lenta. O tracing
   mostra quanto vai para HTTP, JDBC e chamada externa. Se o `pg_stat_statements` diz 50 ms e a
   aplicação mede 1,2 s, o tempo está na espera pelo pool (`hikaricp_connections_pending`,
   `hikaricp_connections_acquire_seconds`), não no banco. Para o provedor,
   `http_client_requests_seconds` por status. Um thread dump com threads paradas em
   `HikariPool.getConnection` ou lendo socket aponta o culpado.

3. Logs e métricas úteis: p50/p95/p99 por endpoint; latência, erro e timeout do provedor; pool do
   Hikari (ativas, ociosas, pendentes, tempo de aquisição); `pg_stat_activity` e `EXPLAIN ANALYZE` das
   consultas lentas; tamanho da fila (`activation_requests{status="PENDING"}`) e taxa de retry; pausas
   de GC; logs com correlation id para seguir um caso.

4. Melhorias: tirar a chamada ao provedor do caminho síncrono e de dentro de transações (este serviço
   já faz); timeouts curtos e circuit breaker; backoff com jitter para não gerar tempestade de retry;
   índices para as consultas lentas e paginação por cursor; pool dimensionado e separado entre API e
   job.

5. Causa comum (hipótese): o provedor lento esgota um recurso compartilhado. Se a chamada ao provedor
   acontece dentro de uma transação, cada chamada que passa de 400 ms para 3 s segura a conexão sete
   vezes mais. O pool esgota, as consultas passam a esperar conexão (parecem levar 1,2 s, embora o banco
   execute em 50 ms) e todas as rotas ficam lentas, com CPU baixa. Retry agressivo piora. Para
   confirmar: `hikaricp_connections_pending` acima de zero junto com a latência do provedor,
   `pg_stat_statements` normal, conexões `idle in transaction` no `pg_stat_activity` e thread dump com
   threads em `getConnection` e em leitura de socket. Se o banco tivesse degradado por outro motivo,
   o mesmo diagnóstico mostraria.

6. Validação depois de publicar: canary ou rolling, comparando com a linha de base e com as instâncias
   antigas: p95/p99 de volta a cerca de 300 ms, pool sem pendências, consultas em torno de 50 ms, erro
   e retry normais e fila `PENDING` estável. Antes, um teste de carga com o provedor lento (WireMock com
   atraso). Depois, alerta de SLO de latência para pegar regressão.

## Produção

- Imagem do [`Dockerfile`](Dockerfile): build em dois estágios, só JRE no final, usuário não-root,
  camadas do Spring Boot, heap em 75% do limite do contêiner, healthcheck e a JVM como PID 1 para
  receber o `SIGTERM` (desligamento gracioso).
- Orquestrador (Kubernetes ou ECS) com pelo menos 2 réplicas, liveness e readiness no Actuator,
  limites de CPU e memória e `terminationGracePeriodSeconds` maior que o desligamento gracioso.
- PostgreSQL gerenciado com backup e réplica. O Liquibase roda na subida, ou num job antes do
  deploy se houver muitas réplicas.
- Configuração por variável de ambiente (`DB_URL`, `DB_USERNAME`, `JWT_ISSUER_URI`, `JWT_AUDIENCE`,
  `PROVIDER_BASE_URL`, timeouts, `SPRING_PROFILES_ACTIVE=prod`); `DB_PASSWORD` e `PROVIDER_API_KEY` vêm
  do secret manager. Sem as obrigatórias, a aplicação não sobe.
- API Gateway na frente com TLS, rate limit por cliente e limite de corpo. `/actuator/prometheus` só na
  rede interna.

Microsserviços e cloud:

- Várias instâncias: a duplicidade é resolvida no banco, então vale para qualquer número de instâncias.
  O job pode rodar em todas, porque a reserva usa lock otimista e a reserva vencida recupera o que uma
  instância morta deixou. Com muito volume, trocaria por `SELECT ... FOR UPDATE SKIP LOCKED`.
- Configuração e segredos: o que não é sensível em variável de ambiente ou ConfigMap; o sensível no
  secret manager do cloud, com rotação.
- Aviso a outros serviços: ao concluir ou falhar, publicar um evento num tópico (Kafka, SNS/SQS,
  Pub/Sub), gravando o evento numa tabela na mesma transação da mudança de status e publicando a partir
  dela, a mesma ideia já usada aqui para chamar o provedor.
- CI/CD: no PR, `./mvnw verify` (unitários, integração, Testcontainers, ArchUnit); depois build da
  imagem, scan de vulnerabilidades, push com a tag do commit, deploy em staging com smoke test e
  promoção para produção em canary, com rollback por métrica. O workflow em
  [`.github/workflows/ci.yml`](.github/workflows/ci.yml) cobre build, testes e imagem.

## Pontos pendentes

| Item | Como faria |
|---|---|
| Rate limit por consumidor | No API Gateway, pelo client do token. Na aplicação, Bucket4j com estado no Redis para valer entre instâncias, respondendo `429` com `Retry-After`. |
| Corpo sem `Content-Length` (chunked) | O filtro olha o `Content-Length`; o chunked seria limitado no gateway ou proxy. |
| Circuit breaker | Resilience4j no service do provedor; com o circuito aberto, reagenda sem gastar tentativa. |
| Tracing distribuído | Micrometer Tracing com OpenTelemetry (OTLP). |
| Solicitações que esgotaram as tentativas | Alerta e endpoint administrativo de reprocessamento. |
| 409 do provedor para chave já usada | Depende do contrato real; se significar "já ativado", trataria como sucesso. |
| Docker | Dockerfile e compose escritos, mappings do WireMock validados, mas o `docker build` não rodou na minha máquina (sem Docker); o CI faz o build da imagem. |

## Melhorias futuras

- Evento de conclusão publicado via outbox para outros serviços.
- `SELECT ... FOR UPDATE SKIP LOCKED` na reserva e índice parcial no PostgreSQL.
- Paginação por cursor.
- Cancelamento e reprocessamento, com as transições novas no domínio.
- Contrato OpenAPI publicado e teste de contrato com o provedor.
- Alertas para fila crescendo, reserva vencida, taxa de `FAILED` e latência do provedor.
- Teste de carga com provedor lento.
- Retenção e arquivamento de solicitações antigas.

## Uso de IA

Usei o Claude Code (Anthropic) para ler o enunciado, gerar código, testes, configuração de Docker e CI
e o rascunho deste README, em commits por item do desafio. Revisei as decisões e o código e validei
com `./mvnw verify` e com o fluxo manual no perfil `local`.
