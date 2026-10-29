# syntax=docker/dockerfile:1

# ---- build: compila e roda os testes unitários ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipITs \
    && java -Djarmode=tools -jar target/activation-service-*.jar extract --layers --launcher --destination extracted

# ---- runtime: só JRE, usuário sem privilégios ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
# Camadas da mais estável para a mais volátil: rebuilds só trocam a camada da aplicação.
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
USER app

# Heap proporcional ao limite de memória do contêiner (não à memória do host).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

# exec form: a JVM é o PID 1 e recebe o SIGTERM (desligamento gracioso, server.shutdown=graceful).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
