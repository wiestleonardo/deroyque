# Activation Service

Serviço que registra solicitações de ativação de produtos, permite consultar o status e faz a ativação por
meio de um provedor externo.

Java 21, Spring Boot 3.5 (MVC, Data JPA, Security Resource Server, Actuator), PostgreSQL, Liquibase, Maven e
Docker. Testes com JUnit 5, Mockito, MockMvcTester, WireMock, Awaitility, Testcontainers e ArchUnit.

## Como executar

Precisa do JDK 21 (o Maven vem pelo wrapper).

```bash
./mvnw verify                 # build + testes unitários e de integração
docker compose up --build     # aplicação + PostgreSQL + mock do provedor (WireMock)
```

Sem Docker: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (H2 em memória; provedor esperado em
`http://localhost:8089`). Os testes com PostgreSQL usam Testcontainers e são pulados sem Docker.

O perfil `local` habilita `/dev/token`, que emite tokens de teste, já que o desafio não tem servidor de
autenticação. Nunca é habilitado em produção.

```bash
TOKEN=$(curl -s -X POST "http://localhost:8080/dev/token?clientId=system-a" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

curl -i -X POST http://localhost:8080/api/v1/requests -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

O mock do provedor responde pelo `productCode`: `REJECT` → 400, `ERROR` → 500, `SLOW` → 5 s (estoura o timeout),
`BUSY` → 503 com `Retry-After`, qualquer outro → 200.

## API

| Método | Caminho | Escopo |
|---|---|---|
| POST | `/api/v1/requests` | `activation:write` |
| GET | `/api/v1/requests/{id}` | `activation:read` |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` |

- Campos obrigatórios com até 64 caracteres. `size` de 1 a 100 (acima disso, 400).
- `201` com `Location` ao criar. Datas em UTC, ISO-8601. DTOs separados da entidade.
- Erros em Problem Details (RFC 9457) com `traceId`: 400 (campos ou JSON inválido), 401/403, 404, 405, 409, 415
  e 500, todos no mesmo formato e sem stack trace nem detalhe interno. Erro do provedor não chega ao POST (a chamada é assíncrona): aparece no `status` e no `failureReason`.

## Decisões técnicas

### Status

`PENDING → PROCESSING → COMPLETED | FAILED`. Falha temporária volta para `PENDING` com nova data de tentativa e
o contador `attempts`. O status só muda por métodos da entidade, que recusam transições inválidas, e o
`@Version` protege contra escrita concorrente.

### Duplicidade

Quem garante é o índice único `(sistema de origem, externalId)`. Consultar e depois inserir não basta, porque
duas requisições podem consultar antes de qualquer uma inserir. Por isso o service consulta (caso comum do
retry), insere, e se o insert falhar no índice relê o registro vencedor.

| Caso | Resposta | Justificativa |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo | `200` com o registro existente | Retry legítimo: o sistema de origem recebe o mesmo resultado e não trata erro. `200` indica que nada foi criado agora. |
| Mesmo `externalId`, conteúdo diferente | `409` "Solicitação duplicada", com `existingRequestId` | É conflito, não retry. Devolver o antigo esconderia o erro. |
| Duas requisições iguais ao mesmo tempo | uma `201`, a outra `200` | O índice deixa só um insert passar; a outra relê o vencedor. |

O `externalId` é único por sistema de origem, porque dois sistemas podem gerar o mesmo valor.

### Integração com o provedor

Assíncrona: o POST grava `PENDING` e responde, então a API não depende da velocidade do provedor. Um job a cada
1 s busca um lote numa consulta só, reserva cada solicitação (`PROCESSING`, gravado com `@Version`, reserva de
30 s), chama o provedor **fora de transação** e grava o resultado. A tabela funciona como outbox: se a aplicação
cair antes ou durante a chamada, a solicitação continua no banco e é reprocessada quando a reserva vence.

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | Não | Provedor confirmou. |
| HTTP 400 (demais 4xx e 3xx) | `FAILED` | Não | Conteúdo recusado; repetir dá o mesmo resultado. |
| HTTP 500 (demais 5xx, 408) | `PENDING` com backoff | Sim | Falha temporária. |
| Timeout / erro de rede | `PENDING` com backoff | Sim, mesma `Idempotency-Key` | Não se sabe se ativou; a chave evita ativação duplicada. |
| HTTP 429 / 503 com `Retry-After` | `PENDING` até o `Retry-After` | Sim | O provedor disse quando aceita de novo. |
| 401 / 403 | `PENDING` com log de erro | Sim | O problema é a nossa credencial, não a solicitação. |

- Timeout de conexão 2 s e de leitura 3 s, configuráveis.
- Até 5 tentativas, backoff exponencial (2 s, 4 s, 8 s...) com jitter, limitado a 5 min. Depois disso,
  `FAILED` com `MAX_ATTEMPTS_EXCEEDED`.
- A reserva de 30 s é bem maior que a chamada mais longa (5 s), o que cobre diferença de relógio entre
  instâncias.

### Concorrência e desempenho

- Duas instâncias disputando a mesma solicitação: o `@Version` deixa só uma reservar; a outra pula. Lock
  otimista em vez de `FOR UPDATE` para não segurar conexão do banco durante a chamada HTTP.
- O lote é processado em paralelo em threads virtuais: dura o tempo da chamada mais lenta, não a soma. A conexão
  do banco só é usada na reserva e na gravação, por isso 20 chamadas simultâneas cabem num pool de 10.
- O cliente HTTP é o do JDK, e não o `HttpURLConnection`, que usa `synchronized` por dentro e no Java 21 prende
  a thread do sistema durante a espera (*pinning*), podendo travar a API inteira com o provedor lento.
- A listagem ordena por data e id, para a paginação ficar estável quando várias solicitações têm a mesma data.

### Datas

Tudo é `Instant`, gravado como `TIMESTAMP WITH TIME ZONE` em UTC. O `TZ` do container (`America/Sao_Paulo`) só
afeta logs. O `Clock` trabalha em milissegundos: com nanossegundos, o banco arredondava o valor e o `201`
devolvia um `createdAt` diferente do `GET` e do retry (há um teste que cobre isso).

### Segurança

- OAuth 2.0 Client Credentials, porque quem consome são sistemas.
- Resource Server: valida assinatura (JWKS do `JWT_ISSUER_URI`), emissor, audiência e expiração.
- `activation:write` cria, `activation:read` consulta.
- Isolamento (BOLA): cada solicitação pertence ao client do token; solicitação de outro sistema responde `404`.
- A chave do provedor vem de `PROVIDER_API_KEY`, injetada e rotacionada pelo secret manager do cloud; nunca fica
  no repositório nem em log.

### Índices

Único `(cd_sistemaorigem, cd_externo)` para duplicidade; `(fl_situacao, dt_proximatentativa)` para o job;
`(cd_sistemaorigem, fl_situacao, dt_cadastro)` e `(cd_sistemaorigem, cd_cliente, dt_cadastro)` para a listagem,
que sempre filtra pelo sistema de origem e ordena por data.

### Organização

`api` (controller), `service` (regra de negócio), `repository` (Spring Data e entidade), `intercomm` (client do
provedor), `job` e `config`. O ArchUnit confere o sentido das dependências. Código em português; em inglês só o
contrato do desafio (JSON, status, rotas, headers, escopos). Decisões maiores em `docs/adr`.

## Estratégia de testes

- **Unitário (sem Spring):** transições da entidade e decisões do processamento (sucesso, recusa, nova
  tentativa, Retry-After, última tentativa, reserva disputada, reserva expirada, erro num item do lote).
- **Client HTTP contra WireMock:** headers enviados e como cada resposta, timeout ou erro de rede é tratado.
- **Fatia web:** contrato HTTP, validação, 401/403 e formato do erro.
- **Integração com H2:** duplicidade com 16 threads simultâneas, fluxo completo API → job → WireMock (sucesso,
  400, 500 até esgotar, timeout e depois sucesso) e isolamento entre sistemas.
- **Integração com PostgreSQL (Testcontainers):** duplicidade concorrente e filtros no banco real.
- **ArchUnit:** dependências entre camadas.

## Observabilidade

- `/actuator/health/liveness` e `/actuator/health/readiness` (confere o banco).
- `X-Correlation-Id` em todos os logs, no `traceId` dos erros, gravado na solicitação e enviado ao provedor.
- `/actuator/prometheus`: latência da API, latência e erros do provedor, pool de conexões.
- Logs em JSON (ECS) no perfil `prod`; tokens e chaves nunca são logados.

## Cenário de análise

API de 300 ms para 2,5 s, CPU 45%, memória 60%, consultas de 50 ms para 1,2 s, provedor de 400 ms para 3 s.

1. **O que investigaria primeiro:** o que mudou (deploy, tráfego, provedor, volume) e quais endpoints pioraram.
   CPU e memória normais indicam que a aplicação está esperando, não trabalhando mais.
2. **Aplicação, banco ou provedor:** tempo por etapa de uma requisição lenta. Se o banco mede 50 ms
   (`pg_stat_statements`) mas a aplicação vê 1,2 s, o tempo está na espera por conexão do pool. A latência do
   provedor vem das métricas do client HTTP; um thread dump mostra onde as threads estão paradas.
3. **Métricas úteis:** p95/p99 por endpoint, latência e erros do provedor, pool do Hikari (ativas, pendentes,
   tempo de aquisição), consultas lentas e seus planos, tamanho da fila `PENDING`.
4. **Melhorias:** provedor fora da requisição e fora de transação (já feito), timeouts curtos, circuit breaker,
   índices para as consultas lentas e pool bem dimensionado.
5. **Causa comum:** provedor lento chamado segurando conexão do banco. O pool esgota, as consultas esperam
   conexão (parecem lentas, mas o banco está rápido) e tudo fica lento com CPU baixa. Confirmaria com conexões
   pendentes do Hikari subindo junto com a latência do provedor e sessões `idle in transaction` no banco.
6. **Validar a correção:** canário comparado com a linha de base (p95 perto de 300 ms, sem conexões pendentes)
   e, antes de publicar, teste de carga com WireMock lento.

## Produção

- `Dockerfile` em dois estágios, só JRE, usuário não-root, heap pela memória do container, healthcheck e
  desligamento gracioso.
- Kubernetes ou ECS com 2+ réplicas, probes no Actuator, PostgreSQL gerenciado e Liquibase na subida.
- Configuração por variáveis de ambiente (`DB_URL`, `JWT_ISSUER_URI`, `PROVIDER_BASE_URL`, timeouts,
  `SPRING_PROFILES_ACTIVE=prod`); `DB_PASSWORD` e `PROVIDER_API_KEY` vêm do secret manager.
- Várias instâncias funcionam sem mudança: duplicidade barrada pelo banco e reserva do job com `@Version`.
- Outros serviços seriam avisados por um evento de conclusão publicado via outbox numa fila ou tópico.
- CI/CD: `./mvnw verify`, build da imagem, scan, push, deploy em homologação e produção. O GitHub Actions cobre
  build, testes e imagem.

## Pontos pendentes

Todos são diferenciais do desafio; ficaram descritos em vez de implementados.

| Ponto | O que usaria | Como faria |
|---|---|---|
| Rate limit | API Gateway (AWS API Gateway, Kong) | Limite por `client_id` do token, respondendo `429` com `Retry-After`. Fica no gateway porque o limite precisa valer para todas as instâncias juntas; dentro da aplicação (Bucket4j) exigiria um Redis para compartilhar o contador. |
| Limite de tamanho do corpo | API Gateway e Tomcat | Limite no gateway (por exemplo, 16 KB, folgado para três campos de 64 caracteres) e, como segunda barreira, `server.tomcat.max-swallow-size` mais um filtro que recusa `Content-Length` acima do limite com `413`. |
| Circuit breaker | Resilience4j (`resilience4j-spring-boot3`) | `@CircuitBreaker` no `ProvedorAtivacaoIntercommService`, abrindo com 50% de falha em uma janela de 20 chamadas. Com o circuito aberto, o job não chama o provedor e reagenda a solicitação como `PENDING`, sem gastar tentativa. Só timeout, erro de rede e 5xx contam como falha; 400 não, porque é erro da solicitação. |
| Tracing distribuído | Micrometer Tracing + OpenTelemetry (`micrometer-tracing-bridge-otel` e exporter OTLP) | O Spring já instrumenta o MVC, o `RestClient` e o JDBC. O trace id passaria a ser o id de correlação, e o header `traceparent` iria ao provedor. Envio para um OpenTelemetry Collector, e dele para Jaeger, Tempo ou o serviço do cloud. |
| Métrica de solicitações por status | Micrometer (`MultiGauge`) | Um `SELECT fl_situacao, COUNT(*) ... GROUP BY fl_situacao`, atualizado a cada 30 s por um `@Scheduled` (não a cada coleta do Prometheus), publicado como `activation_requests{status=...}`. Com isso dá para alertar sobre fila `PENDING` crescendo e sobre `FAILED` subindo. |

O `docker compose` não foi executado na minha máquina (sem Docker); o CI faz o build da imagem.

## Melhorias futuras

Alerta e reprocessamento manual para solicitações `FAILED`, retenção das solicitações finalizadas, eventos de
conclusão, paginação por cursor, `FOR UPDATE SKIP LOCKED` para alto volume e documentação OpenAPI.

## Uso de IA

O Claude Code (Anthropic) ajudou a ler o desafio e a escrever código, testes, arquivos de Docker/CI e este
README, usando uma skill particular de Spring Boot com as minhas convenções.

A arquitetura e os padrões são decisão minha: a divisão em controller, service, repository e intercomm, a
nomenclatura e a forma de tratar erros e integrações seguem padrões que eu mesmo defini e já validei em vários
projetos. Revisei o código e validei com `./mvnw verify` e testes manuais.
