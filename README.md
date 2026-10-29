# Activation Service

Serviço de ativação de produtos de clientes: registra solicitações, permite consultar o status e
realiza a ativação chamando um provedor externo.

**Stack:** Java 21 · Spring Boot 3.5 (Spring MVC, Spring Data JPA/Hibernate, Spring Security
Resource Server, Actuator) · PostgreSQL · Flyway · Maven · Docker.
**Testes:** JUnit 5, Mockito, AssertJ, MockMvc, WireMock, Awaitility, Testcontainers, ArchUnit.

---

## Sumário

1. [Como executar](#como-executar)
2. [API](#api)
3. [Decisões técnicas](#decisões-técnicas)
4. [Estratégia de testes](#estratégia-de-testes)
5. [Observabilidade](#observabilidade)
6. [Cenário de análise (item 10)](#cenário-de-análise-item-10)
7. [Produção](#produção)
8. [Pontos pendentes](#pontos-pendentes)
9. [Melhorias futuras](#melhorias-futuras)
10. [Uso de IA](#uso-de-ia)

Registros de decisão (ADRs) em [`docs/adr`](docs/adr).

---

## Como executar

Pré-requisito: **JDK 21** (o Maven vem pelo wrapper). Docker é opcional.

### Build e todos os testes (um comando)

```bash
./mvnw verify
```

Roda os testes unitários (surefire, `*Test`) e os de integração (failsafe, `*IT`). O teste contra
PostgreSQL real usa Testcontainers e é **ignorado automaticamente se não houver Docker**; os demais
testes de integração usam H2 em modo PostgreSQL com as mesmas migrações Flyway.

> Windows: use `mvnw.cmd verify`. Se o Maven reclamar da versão do Java, aponte `JAVA_HOME` para o JDK 21.

### Opção 1 — sem Docker (perfil `local`)

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

O perfil `local` usa H2 em memória, um **provedor simulado dentro da própria aplicação** e um
**emissor de tokens de teste** (não existe servidor de autenticação no desafio). Esses recursos só
existem com `app.local-dev.enabled=true`, que nunca é ligado em produção.

### Opção 2 — ambiente completo com Docker Compose

```bash
docker compose up --build
```

Sobe a aplicação, PostgreSQL 16 e o mock do provedor (WireMock, mappings em
[`docker/wiremock/mappings`](docker/wiremock/mappings)).

### Testando com curl

```bash
# 1. token de teste (client_credentials simulado)
TOKEN=$(curl -s -X POST "http://localhost:8080/dev/token?clientId=system-a" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

# 2. cria a solicitação
curl -i -X POST http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

# 3. consulta (use o id retornado / header Location)
curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"

# 4. lista com filtros
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

O mock do provedor reage ao `productCode`, para demonstrar cada cenário:

| productCode contém | Provedor responde | Status final esperado |
|---|---|---|
| (qualquer outro) | 200 | `COMPLETED` |
| `REJECT` | 400 | `FAILED` (1 tentativa) |
| `ERROR` | 500 sempre | `FAILED` após 5 tentativas (`MAX_ATTEMPTS_EXCEEDED`) |
| `SLOW` | demora 5 s (read-timeout é 3 s) | perfil local: timeout e depois `COMPLETED` pela idempotência; compose: `FAILED` após as tentativas |
| `BUSY` | 503 + `Retry-After: 3` | perfil local: `COMPLETED` na 2ª tentativa; compose: `FAILED` após as tentativas |

Endpoints operacionais (públicos): `/actuator/health/liveness`, `/actuator/health/readiness`,
`/actuator/prometheus`.

---

## API

| Método | Caminho | Escopo | Descrição |
|---|---|---|---|
| POST | `/api/v1/requests` | `activation:write` | Cria solicitação (idempotente por `externalId`) |
| GET | `/api/v1/requests/{id}` | `activation:read` | Consulta solicitação |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | Lista paginada (mais recentes primeiro) |

**Respostas do POST**

| Situação | Status |
|---|---|
| Criada | `201 Created` + `Location: /api/v1/requests/{id}` |
| Retry com o mesmo `externalId` e mesmo conteúdo | `200 OK` com o registro existente |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict` (Problem Details com `existingRequestId`) |
| Dados inválidos | `400` com lista `errors[]` por campo |
| Corpo acima de 16 KB | `413` |
| Sem token / token inválido | `401` · sem o escopo | `403` |

**Validação** (`externalId`, `customerId`, `productCode`): obrigatórios, até 64 caracteres, apenas
letras, números, `.`, `_` e `-`. Paginação: `page ≥ 0`, `1 ≤ size ≤ 100` (padrão 20).

**Resposta**

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

Datas sempre em UTC, ISO-8601. `failureReason` é um código estável, nunca a mensagem crua do provedor.
DTOs são `record`s separados da entidade JPA.

**Erros** seguem RFC 9457 (`application/problem+json`) com `traceId`, o mesmo identificador de
correlação presente em todos os logs daquela requisição (e devolvido no header `X-Correlation-Id`).
Nenhuma resposta contém stack trace ou mensagem interna; exceções inesperadas viram `500` genérico
e o detalhe fica só no log.

---

## Decisões técnicas

### Organização

Pacotes por responsabilidade, sem camadas extras:

```
api/             controller, DTOs (records), tradução de exceções -> Problem Details
application/     casos de uso: criação/consulta, processador, worker, política de retry
  provider/      porta para o provedor (interface + resultado sealed)
domain/          entidade ActivationRequest com as regras de transição, repositório
infrastructure/  cliente HTTP do provedor, segurança, filtros web, métricas, configuração
localdev/        token de teste e provedor simulado (só com app.local-dev.enabled=true)
```

A direção das dependências (api → application → domain; domínio sem web/HTTP; nada depende de
`localdev`) é verificada por testes ArchUnit. O repositório Spring Data fica no domínio de propósito:
criar uma interface própria só para esconder o Spring Data seria uma camada sem ganho aqui.

### Estados e transições

```
PENDING ──► PROCESSING ──► COMPLETED
   ▲            │
   └────────────┤  falha temporária (com backoff)
                └──► FAILED
```

- O status só muda por métodos da entidade (`startProcessing`, `complete`, `fail`, `scheduleRetry`,
  `releaseExpiredLease`); não há setters. Transição inválida lança `InvalidStatusTransitionException`
  (ex.: `COMPLETED` nunca volta para `PROCESSING`). Testado sem Spring.
- **Falha temporária volta para `PENDING`** com `attempts` incrementado e `next_attempt_at` no futuro.
  Escolhi isso em vez de "fica em PROCESSING com contador" porque `PROCESSING` passa a significar
  apenas "um worker está com ela agora", o que deixa a reconciliação simples: `PROCESSING` com lease
  vencido é sempre um worker que morreu.
- **Concorrência:** `@Version` (lock otimista) na entidade. Duas instâncias que tentam reservar a mesma
  solicitação: só uma grava; a outra recebe conflito e pula.

### Duplicidade (item 3)

**Estratégia:** constraint `UNIQUE (client_id, external_id)` no banco é a garantia; o código trata a
violação.

```
create():
  1. busca por (clientId, externalId)  -> se existe, resolve (atalho para o retry comum)
  2. INSERT (saveAndFlush, transação própria)
  3. se violar a constraint -> outra requisição venceu a corrida:
     relê o registro vencedor numa transação nova e resolve
```

**Por que "consultar e depois inserir" não basta:** entre o SELECT e o INSERT de uma requisição, outra
requisição idêntica pode executar o próprio SELECT. As duas não encontram nada e as duas inserem.
Nenhum nível de isolamento padrão (READ COMMITTED) impede isso, porque não há linha para travar. Só
uma restrição de unicidade no banco é atômica entre transações/instâncias. O método de criação não é
`@Transactional` justamente para que a releitura após a falha aconteça numa transação nova (no
PostgreSQL, uma transação que sofreu erro fica abortada até o rollback).

**Semântica da resposta**

| Cenário | Decisão | Justificativa |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | `200 OK` com o registro existente | Retry é esperado; o cliente precisa do `id` e do status. 409 obrigaria o cliente a tratar "erro" num caso de sucesso. `200` (e não `201`) indica que nada novo foi criado. |
| Mesmo `externalId`, `productCode`/`customerId` diferente | `409 Conflict`, `type=.../duplicate-request`, com `existingRequestId` | É erro do cliente: reutilizou o identificador para outra coisa. Não sobrescrevemos nem criamos outra. |
| Duas requisições idênticas em paralelo | Uma recebe `201`; a outra, `200` com o mesmo registro | A perdedora cai na violação de constraint, relê o vencedor e segue o caso "retry legítimo". |

**`externalId` é único por consumidor**, não globalmente: ele identifica a solicitação no sistema de
origem, e dois sistemas diferentes podem gerar o mesmo valor. Se fosse global, o sistema B, ao reusar
o id do sistema A, receberia o registro de A (vazamento — BOLA) ou um 409 que revela a existência dele.

Testado com 16 threads simultâneas contra H2 e 20 contra PostgreSQL (Testcontainers): sempre um único
registro e exatamente um `created=true`.

### Integração com o provedor (item 5)

**Assíncrono.** O `POST` só grava a solicitação como `PENDING` e responde (tempo do POST = um INSERT).
Um worker agendado (`ActivationWorker`, a cada 1 s) busca as vencidas e chama o provedor. Se o
provedor ficar lento (400 ms → 3 s), a API continua rápida; o atraso aparece só no tempo até
`COMPLETED`, e o consumidor acompanha pelo `GET`.

**Transação × chamada remota.** A chamada HTTP nunca acontece com transação aberta:

1. *reserva* (transação curta): `PENDING → PROCESSING`, `attempts++`, lease de 30 s;
2. *chamada ao provedor* (sem transação, sem conexão de banco presa);
3. *aplicação do resultado* (transação curta), só se a `version` ainda for a da reserva.

**Garantia de envio mesmo se a aplicação cair:** a própria tabela é a outbox. Não existe "commit e
depois chamo"; existe "commit", e o worker de qualquer instância encontra o registro `PENDING`. Se a
instância cair *durante* a chamada, a solicitação fica `PROCESSING` com lease vencido e é reprocessada
(reconciliação automática, `releaseExpiredLease`).

**Comportamento por retorno**

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | Não | Produto ativado. |
| HTTP 400 (e 404, 409, 422, demais 4xx) | `FAILED` (`PROVIDER_REJECTED_4xx`) | Não | O provedor recusou o conteúdo; repetir dá o mesmo resultado. |
| HTTP 500 / 502 / 504 / 408 | `PENDING` com backoff; `FAILED` ao esgotar | Sim | Falha do lado do provedor, normalmente passageira. |
| Timeout / erro de rede | `PENDING` com backoff; `FAILED` ao esgotar | Sim, **com a mesma Idempotency-Key** | Resultado desconhecido: o provedor pode ter ativado. |
| HTTP 429 / 503 com `Retry-After` | `PENDING` com `next_attempt_at = agora + Retry-After` (teto 5 min) | Sim | O provedor disse quando voltar; respeitar evita piorar a sobrecarga. Aceita segundos ou data HTTP. |
| HTTP 401 / 403 | `PENDING` com backoff + log de erro | Sim | Problema da nossa credencial (expirada/rotacionada), não da solicitação. |

- **Timeouts explícitos:** conexão 2 s e leitura 3 s, configuráveis (`PROVIDER_CONNECT_TIMEOUT`,
  `PROVIDER_READ_TIMEOUT`), via `RestClient` + `JdkClientHttpRequestFactory`. O lease (30 s) é maior
  que a soma, para um worker vivo nunca perder a reserva no meio da chamada.
- **Timeout ambíguo:** toda chamada envia `Idempotency-Key: <id da solicitação>`. Assume-se que o
  provedor deduplica por essa chave (prática comum; o mock local faz isso). Sem esse suporte, a
  alternativa seria consultar o status no provedor antes de reenviar.
- **Limite de tentativas:** 5 chamadas; backoff exponencial com jitter (2 s, 4 s, 8 s, 16 s — cada uma
  sorteada entre metade e o valor cheio, teto 5 min). Ao atingir o limite: `FAILED` com
  `failureReason=MAX_ATTEMPTS_EXCEEDED:<último motivo>`. Para produção, essas solicitações iriam para
  um alerta/fila de análise manual.
- **Circuit breaker:** não implementei. O backoff por solicitação já impede martelar o provedor, e o
  worker processa no máximo 20 por ciclo. Com volume alto, eu colocaria um circuit breaker
  (Resilience4j) em volta do `HttpActivationProvider`: com o circuito aberto, o worker reagenda sem
  chamar e sem gastar tentativa.
- **Paralelismo:** cada ciclo processa o lote com virtual threads (I/O), sem pool dedicado.

### Segurança (item 7) — implementada

- **Fluxo OAuth 2.0:** *Client Credentials*. Os consumidores são sistemas, não pessoas: não há usuário
  para fazer login nem redirect; cada sistema tem seu `client_id`/segredo (ou chave privada, `private_key_jwt`)
  no servidor de autorização (Keycloak, Okta, Entra ID, Cognito…).
- **O serviço é Resource Server** (Spring Security) e valida no JWT: **assinatura** (chaves públicas
  obtidas do **JWKS** do emissor, descoberto por `JWT_ISSUER_URI`, com cache e rotação automática),
  **emissor** (`iss`), **audiência** (`aud` deve conter `activation-service`), **expiração** (`exp`/`nbf`).
- **Autorização por escopo:** `activation:write` cria; `activation:read` consulta/lista. Um sistema de
  consulta (ex.: portal de atendimento) recebe só `read`.
- **Isolamento entre consumidores (BOLA):** cada solicitação guarda o `client_id` de quem criou; consulta,
  listagem e unicidade sempre filtram por ele. Solicitação de outro consumidor responde `404` (e não 403,
  para não revelar que existe).
- **Credencial do provedor externo:** lida de `PROVIDER_API_KEY`, que em produção é injetada a partir do
  secret manager do cloud (AWS Secrets Manager / Azure Key Vault / GCP Secret Manager / Vault), nunca
  do repositório nem de imagem. Rotação: o provedor aceita duas chaves durante a janela de troca; a nova
  é gravada no secret manager e as instâncias são reiniciadas (rolling) ou recarregam a configuração.
  A chave nunca é logada (`toString` mascarado). Se o provedor suportar OAuth, preferiria client
  credentials também na saída, com token de curta duração.
- **Limites:** corpo até 16 KB (filtro, `413`). Rate limit por consumidor eu colocaria no API Gateway
  (por `client_id`), não na aplicação — ver [Pontos pendentes](#pontos-pendentes).
- **Ambiente local:** como não há IdP, o modo `local-dev` usa HS256 com segredo compartilhado e as
  **mesmas validações** (emissor, audiência, expiração). Testes cobrem token expirado, adulterado, com
  emissor e com audiência errados (`401`), sem escopo (`403`) e o isolamento entre consumidores.

### Índices (listagem paginada)

| Índice | Uso |
|---|---|
| `UNIQUE (client_id, external_id)` | Idempotência; também serve a busca do retry. |
| `(status, next_attempt_at)` | Consulta do worker (`status IN (PENDING, PROCESSING) AND next_attempt_at <= now ORDER BY next_attempt_at`) e gauges por status. |
| `(client_id, status, created_at)` | Listagem filtrada por status, ordenada por data. |
| `(client_id, customer_id, created_at)` | Listagem filtrada por cliente. |

Toda listagem começa por `client_id` (BOLA), por isso ele é a primeira coluna. Com volume grande, a
contagem total da paginação fica cara; trocaria por paginação por cursor (`created_at`, `id`).
No PostgreSQL, o índice do worker poderia ser parcial (`WHERE status IN ('PENDING','PROCESSING')`),
ficando pequeno mesmo com milhões de concluídas; não fiz para manter a mesma migração no H2.

---

## Estratégia de testes

Pirâmide pensada para dar confiança para alterar o código; cada nível testa o que só ele consegue:

| Tipo | Classe | O que garante |
|---|---|---|
| Unitário (sem Spring) | `ActivationRequestTest` | Regras de transição, lease, truncamento. |
| Unitário (sem Spring) | `RetryPolicyTest` | Backoff exponencial, jitter, teto, Retry-After. |
| Unitário (Mockito) | `ActivationProcessorTest` | Decisão por resultado do provedor, limite de tentativas, reserva disputada, lease expirado. |
| Cliente HTTP real × WireMock | `HttpActivationProviderTest` | Mapeamento de cada status HTTP, Retry-After, timeout de leitura, provedor fora do ar, headers enviados (idempotência, correlação, credencial). |
| Fatia web (`@WebMvcTest`) | `ActivationRequestControllerTest` | Contrato HTTP: 201/Location, 200 idempotente, 409, 400 com campos, 404, 413, 500 sem detalhes, 401/403. |
| Integração (H2 + Flyway) | `DuplicateRequestIT` | Idempotência real no banco, inclusive 16 threads concorrentes. |
| Integração ponta a ponta | `ActivationFlowIT` | API → banco → worker → WireMock: sucesso, 400, 500 até esgotar, timeout + retry com a mesma chave. |
| Integração segurança | `SecurityAndIsolationIT` | Validação real de JWT, BOLA, health e métricas. |
| Integração PostgreSQL | `PostgresConcurrencyIT` | Corrida de duplicidade no banco de produção (Testcontainers). |
| Arquitetura | `ArchitectureTest` | Direção de dependências entre pacotes. |

Não há teste de servidor de autenticação real: tokens são gerados no teste (`jwt()` do
spring-security-test, ou assinados com a chave do modo local-dev).

---

## Observabilidade

- **Health:** `/actuator/health/liveness` e `/actuator/health/readiness` (readiness inclui o banco).
- **Correlação:** `X-Correlation-Id` recebido (validado contra log injection) ou gerado; vai para o MDC,
  para todas as linhas de log, para o `traceId` dos erros, é gravado na solicitação e **reenviado ao
  provedor** pelo worker — dá para seguir uma ativação do POST até a resposta do provedor.
- **Métricas (Prometheus, `/actuator/prometheus`):**
  - `http_server_requests_seconds` — latência da API (automática);
  - `activation_provider_calls_seconds{outcome}` — latência e taxa de erro do provedor;
  - `http_client_requests_seconds` — latência por status HTTP do provedor;
  - `activation_requests{status}` — solicitações por status (fila e presas);
  - `activation_requests_transitions_total{status}`;
  - `hikaricp_*`, `jvm_*`.
- **Logs JSON** (formato ECS) no perfil `prod`. Nenhum token, segredo ou chave do provedor é logado.
- **Tracing (não implementado):** adicionaria `micrometer-tracing-bridge-otel` + exporter OTLP; o
  `RestClient` do Boot já propaga o contexto W3C, e o `traceId` do OpenTelemetry substituiria o
  correlation id próprio.

---

## Cenário de análise (item 10)

> API 300 ms → 2,5 s · CPU 45% · memória 60% · algumas consultas 50 ms → 1,2 s · provedor 400 ms → 3 s

CPU e memória normais indicam que a aplicação não está trabalhando mais — está **esperando** alguma
coisa (I/O, conexões, locks).

**1. O que investigaria primeiro?**
Quando começou e o que mudou nesse momento: deploy, aumento de tráfego, job/batch, mudança no provedor,
migração de banco. Em seguida, **quais endpoints** pioraram (todos ou só alguns) e, no banco, se a
lentidão é de **execução** das consultas ou de **espera por conexão**.

**2. Como separar aplicação, banco ou integração?**
Decompondo o tempo de uma requisição lenta:
- *tracing* (spans HTTP → JDBC → chamada externa) mostra onde o tempo é gasto;
- banco: `pg_stat_statements` / slow query log dizem o tempo de execução real; se o banco diz 50 ms e a
  aplicação mede 1,2 s, o tempo está na espera por conexão do pool (`hikaricp_connections_pending`,
  `hikaricp_connections_acquire_seconds`), não no banco;
- provedor: `http_client_requests_seconds` por status e o tempo de rede vs. tempo de resposta;
- aplicação: thread dump — threads paradas em `HikariPool.getConnection`, em leitura de socket ou em
  locks apontam o culpado.

**3. Logs e métricas úteis**
Latência p50/p95/p99 por endpoint; latência e taxa de erro/timeout do provedor; pool do Hikari (ativas,
ociosas, pendentes, tempo de aquisição); `pg_stat_activity` (consultas em espera, locks), plano de
execução (`EXPLAIN ANALYZE`) das consultas lentas; tamanho da fila (`activation_requests{status=PENDING}`)
e taxa de retry; GC pauses e threads da JVM; logs com correlation id para seguir um caso específico.

**4. Melhorias possíveis**
- Tirar a chamada ao provedor do caminho síncrono e de dentro de transações (o que este serviço já faz).
- Timeouts curtos + circuit breaker no provedor; backoff com jitter para não gerar *retry storm*.
- Índices adequados às consultas lentas, paginação por cursor, evitar `COUNT` caro.
- Dimensionar o pool de conexões e separar pool/threads do worker e da API (bulkhead).
- Cache de leituras quentes, se houver.

**5. Causa comum? (hipótese)**
Sim: **o provedor lento esgota recursos compartilhados**. Se a chamada ao provedor acontecer dentro de
uma transação (ou segurando uma conexão), cada chamada que passa de 400 ms para 3 s prende a conexão
7× mais tempo; o pool esgota, as consultas passam a esperar conexão (parecem "lentas", 1,2 s, embora o
banco execute em 50 ms) e todas as rotas ficam lentas — com CPU baixa, porque todo mundo está esperando.
Retries agressivos multiplicam o efeito.
*Como confirmar:* `hikaricp_connections_pending` > 0 e tempo de aquisição alto coincidindo com a
latência do provedor; `pg_stat_statements` mostrando execução normal; `pg_stat_activity` com conexões
`idle in transaction`; thread dump com threads em `getConnection` e em leitura de socket do provedor.
(Hipótese alternativa: o banco degradou — ex.: tabela cresceu sem índice — e a integração ficou lenta por
outro motivo; o mesmo diagnóstico as diferencia.)

**6. Como validar a correção em produção**
Publicar com canary/rolling e comparar com a linha de base e com as instâncias antigas: p95/p99 da API
de volta a ~300 ms, `hikaricp_connections_pending` em zero, consultas de volta a ~50 ms, taxa de erro e
de retry normais, fila `PENDING` sem crescer. Deixar alertas (SLO de latência) para detectar regressão,
e fazer um teste de carga reproduzindo o provedor lento (WireMock com atraso) antes/depois.

---

## Produção

**Como executaria:**
- Imagem do [`Dockerfile`](Dockerfile): multi-stage, só JRE, usuário não-root, camadas do Spring Boot,
  `MaxRAMPercentage=75` (heap segue o limite do contêiner), healthcheck, JVM como PID 1 para receber
  `SIGTERM` (desligamento gracioso: `server.shutdown=graceful`).
- Orquestrador (Kubernetes/ECS) com ≥ 2 réplicas, *liveness* e *readiness* apontando para os endpoints
  do Actuator, limites de CPU/memória, `terminationGracePeriodSeconds` > tempo do lease.
- PostgreSQL gerenciado (RDS/Cloud SQL/Azure Database) com backups e réplica; migrações Flyway rodam no
  start (ou num job de migração antes do deploy, se houver muitas réplicas).
- Configuração por variáveis de ambiente (`DB_URL`, `JWT_ISSUER_URI`, `JWT_AUDIENCE`, `PROVIDER_BASE_URL`,
  timeouts, `SPRING_PROFILES_ACTIVE=prod`); segredos (`DB_PASSWORD`, `PROVIDER_API_KEY`) vindos do secret
  manager.
- API Gateway na frente: TLS, rate limit por `client_id`, limite de corpo.
- `/actuator/prometheus` exposto só para a rede interna (ou em `management.server.port` separado).

**Microsserviços e cloud**
- *Várias instâncias:* a regra de duplicidade está no banco (constraint), não em memória — vale para
  qualquer número de instâncias. Todos os workers podem rodar: a reserva usa lock otimista e o lease
  recupera o que uma instância morta deixou. Com volume alto, trocaria a reserva por
  `SELECT … FOR UPDATE SKIP LOCKED` para as instâncias não disputarem as mesmas linhas.
- *Configuração e segredos:* não sensível em variáveis de ambiente/ConfigMap; sensível no secret manager
  do cloud, montado como variável/arquivo (External Secrets / CSI driver), com rotação.
- *Aviso a outros serviços:* ao concluir (ou falhar), publicar evento `ActivationCompleted` /
  `ActivationFailed` num tópico (Kafka, SNS/SQS, Pub/Sub). Para não perder evento, usar outbox
  transacional: gravar o evento numa tabela na mesma transação da mudança de status e um relay
  (ou Debezium/CDC) publica — mesma ideia já usada aqui para a chamada ao provedor.
- *CI/CD:* PR → `./mvnw verify` (unitários, integração, Testcontainers, ArchUnit) → build da imagem →
  scan de vulnerabilidades (Trivy) → push com tag do commit para o registry → deploy automático em
  staging com smoke test → promoção para produção (canary/rolling) com rollback por métrica.
  O workflow em [`.github/workflows/ci.yml`](.github/workflows/ci.yml) cobre build, testes e imagem.

---

## Pontos pendentes

| Item | Como faria |
|---|---|
| Rate limit por consumidor | No API Gateway (por `client_id` do token). Se precisasse na aplicação: Bucket4j com estado no Redis, para valer entre instâncias, respondendo `429` + `Retry-After`. |
| Limite de corpo sem `Content-Length` (chunked) | O filtro checa o `Content-Length`; requisições chunked seriam limitadas no gateway/proxy (`client_max_body_size`). |
| Circuit breaker | Resilience4j em volta do cliente do provedor, reagendando sem consumir tentativa quando aberto. |
| Tracing distribuído | Micrometer Tracing + OpenTelemetry (OTLP). |
| Solicitações `FAILED` por esgotar tentativas | Endpoint administrativo de reprocessamento ou fila de análise manual, com alerta. |
| Resposta 409 do provedor para chave já usada | Depende do contrato real do provedor; se ele responder "já ativado", trataria como sucesso. |
| Imagem Docker / compose | Escritos e com mappings do WireMock validados, mas o `docker build` não foi executado no meu ambiente de desenvolvimento (sem daemon Docker); o CI faz o build da imagem. |

## Melhorias futuras

- Evento de conclusão publicado via outbox transacional (Kafka/SNS) para outros serviços.
- `SELECT … FOR UPDATE SKIP LOCKED` na reserva e índice parcial no PostgreSQL para alto volume.
- Paginação por cursor na listagem.
- Endpoint de cancelamento/reprocessamento (com novas transições no domínio).
- Contrato OpenAPI publicado (springdoc) e testes de contrato com o provedor (Pact).
- Alertas: fila `PENDING` crescendo, `PROCESSING` com lease vencido, taxa de `FAILED`, latência do provedor.
- Testes de carga (Gatling/k6) com provedor lento.
- Retenção/arquivamento de solicitações antigas.

## Uso de IA

O projeto foi desenvolvido com apoio do **Claude Code** (assistente de IA da Anthropic), usado para
ler o enunciado, gerar o código, os testes, a configuração Docker/CI e o rascunho deste README, em
commits incrementais por item do desafio. As decisões e o código foram revisados, executados e
testados localmente (`./mvnw verify` e teste manual do fluxo com o perfil `local`).
