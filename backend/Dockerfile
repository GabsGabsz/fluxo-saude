# syntax=docker/dockerfile:1
# =============================================================================
# Imagem da aplicação Fluxo Saúde (homologação; ver docs/adr/0011-ambiente-de-homologacao.md).
# - Estágio 1: build com Maven (testes rodam no CI, não aqui).
# - Estágio 2: só o JRE 21 + o jar; usuário sem privilégios; NENHUM segredo na imagem.
# A mesma imagem serve ao job de MIGRAÇÃO (perfil "migracao", credencial do dono) e à
# APLICAÇÃO (credencial restrita fluxo_app). Senhas chegam por arquivos de segredo montados em
# tempo de execução (variáveis *_FILE; ver docker/entrypoint.sh).
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline || true
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package \
 && cp "$(ls target/fluxo-saude-backend-*.jar | grep -v '\.original$' | head -1)" /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 fluxo \
 && useradd --system --uid 10001 --gid fluxo --home-dir /app --shell /usr/sbin/nologin fluxo
WORKDIR /app
COPY --from=build --chown=root:root --chmod=0444 /app.jar /app/app.jar
COPY --chown=root:root --chmod=0555 docker/entrypoint.sh /app/entrypoint.sh
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp"
EXPOSE 8080
# Saúde sem curl na imagem: requisição HTTP mínima pelo próprio bash. /actuator/health só diz
# UP/DOWN (sem detalhes) e considera o banco: banco fora do ar => DOWN (503).
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=6 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
ENTRYPOINT ["/app/entrypoint.sh"]
