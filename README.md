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

Os testes com PostgreSQL usam Testcontainers e são pulados quando não há Docker; os outros testes de
integração usam H2 com o mesmo changelog do Liquibase.

Sem Docker: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (H2 em memória; o provedor é esperado em
`http://localhost:8089`, que é o WireMock do compose).

O perfil `local` (usado também pelo compose) habilita `/dev/token`, que emite tokens de teste, já que o desafio
não tem servidor de autenticação. Ele nunca é habilitado em produção.

```bash
TOKEN=$(curl -s -X POST "http://localhost:8080/dev/token?clientId=system-a" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

curl -i -X POST http://localhost:8080/api/v1/requests -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

Os mappings do WireMock em `docker/wiremock/mappings` respondem pelo `productCode`: `REJECT` devolve 400,
`ERROR` devolve 500, `SLOW` demora 5 s, `BUSY` devolve 503 com `Retry-After` e qualquer outro devolve 200.

## API

| Método | Caminho | Escopo |
|---|---|---|
| POST | `/api/v1/requests` | `activation:write` |
| GET | `/api/v1/requests/{id}` | `activation:read` |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` |

- `externalId`, `customerId` e `productCode` são obrigatórios, com até 64 caracteres (o tamanho da coluna).
- `page` começa em 0; `size` vai de 1 a 100 (padrão 20) e acima de 100 responde 400.
- Datas em UTC, ISO-8601. Campos internos nunca saem na resposta.
- Erros em Problem Details (RFC 9457) com `traceId`, o mesmo id de correlação dos logs. Stack trace e mensagens
  internas nunca são devolvidos.
- Erros tratados: dados inválidos (400), sem token ou sem escopo (401/403), não encontrado (404), duplicidade
  (409) e erro inesperado (500). Erro do provedor nunca chega ao POST, porque a chamada é assíncrona: ele aparece
  no `status` e no `failureReason` da solicitação (por exemplo, `PROVIDER_REJECTED_400`).

## Decisões técnicas

### Organização

`api` (contrato do controller e implementação), `service` (regra de negócio), `repository` (Spring Data e a
entidade), `intercomm` (client do provedor), `job` (processamento agendado) e `config` (segurança, cliente HTTP,
tratamento de erro). Controller e job chamam services; services chamam o repository e o client do provedor. O
ArchUnit confere isso.

O código está em português; ficam em inglês só os nomes do contrato do desafio (campos do JSON, valores de
status e rotas).

### Status

`PENDING -> PROCESSING -> COMPLETED | FAILED`. Uma falha temporária volta para `PENDING` com uma nova data de
tentativa. O status só muda por métodos da entidade, que recusam transições inválidas (por exemplo, `COMPLETED`
nunca volta para `PROCESSING`). O `@Version` protege contra atualização concorrente.

### Duplicidade

Quem garante é o índice único `(sistema de origem, externalId)`. O service procura um registro existente
primeiro (o caso comum de retry) e depois insere; se duas requisições iguais passarem pela consulta ao mesmo
tempo, só um insert passa e a outra lê o registro gravado. Consultar e depois inserir não basta sozinho, porque
as duas requisições podem fazer a consulta antes de qualquer uma inserir.

| Caso | Resposta | Justificativa |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo | `200` com o registro existente | É um retry legítimo: o sistema de origem recebe o mesmo resultado da primeira chamada e não precisa tratar erro. O `200` (em vez de `201`) indica que nada foi criado agora. |
| Mesmo `externalId`, conteúdo diferente | `409`, título "Solicitação duplicada", detalhe "O externalId '...' já foi usado com outro customerId ou productCode." e `existingRequestId` | Não é retry, é um conflito: devolver o registro antigo esconderia o erro e criar outro quebraria a regra. |
| Duas requisições iguais ao mesmo tempo | uma recebe `201` e a outra `200` | O índice único deixa só um insert passar; a outra requisição relê o vencedor e cai no caso do retry. |

O `externalId` é único por sistema de origem, porque dois sistemas podem gerar o mesmo valor.

### Integração com o provedor

O POST só grava a solicitação como `PENDING` e responde, então a API não depende da velocidade do provedor. Um
job roda a cada segundo, reserva as solicitações prontas (`PENDING -> PROCESSING`, gravado com `@Version`),
chama o provedor fora de qualquer transação e grava o resultado. Como a solicitação já está no banco antes da
chamada, nada se perde se a aplicação parar entre o commit e a chamada. Se parar durante a chamada, a
solicitação fica em `PROCESSING` até a reserva de 30 s expirar e é processada de novo.

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | Não | O provedor confirmou a ativação. |
| HTTP 400 (e os demais 4xx, exceto 401, 403, 408 e 429) e 3xx | `FAILED` (`PROVIDER_REJECTED_400`) | Não | O provedor recusou o conteúdo; repetir daria o mesmo resultado. |
| HTTP 500 (e os demais 5xx, 408) | `PENDING` com backoff | Sim | Falha temporária do provedor; depois de 5 tentativas vira `FAILED`. |
| Timeout / erro de rede | `PENDING` com backoff | Sim, com a mesma `Idempotency-Key` | Não sabemos se ativou; a chave evita ativação duplicada no provedor. |
| HTTP 429 / 503 com `Retry-After` | `PENDING` até o `Retry-After` | Sim | O provedor disse quando aceita de novo; tentar antes só aumenta a carga. |
| 401 / 403 | `PENDING` com backoff e log de erro | Sim | O problema é a nossa credencial, não a solicitação; volta a funcionar quando o segredo for corrigido. |

- Timeout de conexão de 2 s e de leitura de 3 s, configuráveis.
- Timeout é ambíguo (o provedor pode ter ativado), por isso toda chamada envia `Idempotency-Key` com o id da
  solicitação, e o provedor deve deduplicar por ela.
- Até 5 tentativas com backoff exponencial (2 s, 4 s, 8 s...) e variação aleatória, limitado a 5 minutos.
  Depois disso a solicitação fica `FAILED` com `MAX_ATTEMPTS_EXCEEDED`.
- O `IntegracaoErrorHandler` transforma qualquer falha HTTP numa `IntegracaoException` (com ou sem nova
  tentativa); um client novo montado pelo `HttpClientConfig` ganha o mesmo tratamento.

### Segurança

- OAuth 2.0 Client Credentials, porque quem consome são outros sistemas.
- O serviço é Resource Server: valida a assinatura do JWT (JWKS do `JWT_ISSUER_URI`), o emissor, a audiência e
  a expiração.
- `activation:write` cria e `activation:read` consulta.
- Cada solicitação pertence ao client do token (`SistemaAutenticadoService`); toda leitura filtra por ele, e uma
  solicitação de outro sistema responde `404` (BOLA).
- A chave do provedor vem de `PROVIDER_API_KEY`, injetada pelo secret manager do cloud e rotacionada lá; nunca
  fica no repositório nem em log.

### Fuso horário

O relógio usa o fuso da JVM, definido pelo `TZ` no Dockerfile e no compose (`America/Sao_Paulo`). As datas
gravadas e devolvidas são instantes em UTC.

### Índices

Único `(cd_sistemaorigem, cd_externo)` para duplicidade; `(fl_situacao, dt_proximatentativa)` para o job;
`(cd_sistemaorigem, fl_situacao, dt_cadastro)` e `(cd_sistemaorigem, cd_cliente, dt_cadastro)` para a listagem,
que sempre filtra pelo sistema de origem e ordena por data.

## Estratégia de testes

- Unitário: transições da entidade e decisões do processamento (sucesso, recusa, nova tentativa,
  Retry-After, última tentativa, reserva disputada, reserva expirada).
- Client HTTP contra WireMock: headers enviados e como cada resposta vira `IntegracaoException`.
- Fatia web: contrato HTTP, validação, 401/403 e formato do erro.
- Integração com H2: duplicidade, inclusive com 16 threads; fluxo completo API, job e WireMock (sucesso, 400,
  500 até esgotar, timeout e depois sucesso); validação do JWT e isolamento entre sistemas.
- Integração com PostgreSQL (Testcontainers): duplicidade concorrente e filtros da listagem.
- ArchUnit: sentido das dependências.

## Observabilidade

- `/actuator/health/liveness` e `/actuator/health/readiness` (a readiness confere o banco).
- O `X-Correlation-Id` (recebido ou gerado) vai em todas as linhas de log, no `traceId` dos erros, fica gravado
  na solicitação e é enviado ao provedor pelo job.
- `/actuator/prometheus` expõe a latência da API (`http_server_requests_seconds`) e a latência e os erros do
  provedor (`http_client_requests_seconds`).
- Logs em JSON (ECS) no perfil `prod`; tokens e chaves nunca são logados.

## Cenário de análise

API de 300 ms para 2,5 s, CPU 45%, memória 60%, algumas consultas de 50 ms para 1,2 s, provedor de 400 ms para
3 s.

1. **O que investigaria primeiro:** o que mudou quando começou (deploy, tráfego, provedor, volume de dados) e
   quais endpoints pioraram. CPU e memória normais sugerem que a aplicação está esperando, não trabalhando mais.
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
   os endpoints ficam lentos com CPU baixa. Confirmaria com conexões pendentes no Hikari subindo junto com a
   latência do provedor, tempo normal das consultas no banco e sessões `idle in transaction`.
6. **Validar a correção:** publicação canário comparada com a linha de base: p95 de volta perto de 300 ms, sem
   conexões pendentes e consultas perto de 50 ms; antes de publicar, um teste de carga com um WireMock lento.

## Produção

- Imagem Docker do `Dockerfile`: build em dois estágios, só JRE, usuário não-root, heap pela memória do
  container, healthcheck e desligamento gracioso.
- Kubernetes ou ECS com 2 réplicas ou mais, liveness e readiness no Actuator, PostgreSQL gerenciado e Liquibase
  na subida.
- Configuração por variáveis de ambiente (`DB_URL`, `JWT_ISSUER_URI`, `PROVIDER_BASE_URL`, timeouts, `TZ`,
  `SPRING_PROFILES_ACTIVE=prod`); `DB_PASSWORD` e `PROVIDER_API_KEY` vêm do secret manager.
- Várias instâncias funcionam sem mudança: a duplicidade é barrada pelo banco e a reserva do job usa `@Version`.
- Outros serviços poderiam ser avisados por um evento publicado quando a solicitação conclui, gravado na mesma
  transação e publicado a partir de uma tabela de saída (outbox).
- CI/CD: `./mvnw verify`, build da imagem, scan de vulnerabilidades, push, deploy em homologação e depois em
  produção. O workflow do GitHub Actions cobre build, testes e build da imagem.

## Pontos pendentes

- Rate limit e limite de tamanho do corpo: no API Gateway.
- Circuit breaker para o provedor: Resilience4j no service do provedor.
- Tracing distribuído: Micrometer Tracing com OpenTelemetry.
- Métrica de solicitações por status: um gauge do Micrometer com a contagem por `fl_situacao`, consultada com
  cache curto para não pesar no banco a cada coleta do Prometheus.
- Solicitações que esgotaram as tentativas: alerta e um endpoint de reprocessamento.
- `docker build` e `docker compose` não rodaram na minha máquina (sem Docker); o CI faz o build da imagem.

## Melhorias futuras

Eventos de conclusão para outros serviços, paginação por cursor, `SELECT ... FOR UPDATE SKIP LOCKED` para alto
volume, documentação OpenAPI e alertas sobre o tamanho da fila e a taxa de falha.

## Uso de IA

O Claude Code (Anthropic) ajudou a ler o desafio e a escrever código, testes, arquivos de Docker/CI e este
README. Revisei as decisões e o código e validei com `./mvnw verify` e testes manuais.
