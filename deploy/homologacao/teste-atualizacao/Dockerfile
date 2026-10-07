# =============================================================================
# SOMENTE TESTE (CI, job Homologação). Deriva da imagem real e acrescenta migrações ARTIFICIAIS
# exclusivas do teste, para exercitar atualizações malsucedidas. Não altera nem acompanha as
# migrações do produto (backend/src/main/resources/db/migration) e nunca deve ser implantada.
#   CONJUNTO=parcial     V9001 confirma, V9002 falha  -> avanço PARCIAL do esquema
#   CONJUNTO=sem-avanco  só V9002 (falha)             -> falha SEM avanço do esquema
# =============================================================================
ARG BASE
FROM ${BASE}
ARG CONJUNTO
USER root
COPY ${CONJUNTO}/ /app/migracoes-teste/
RUN cd /app/migracoes-teste && chmod 0444 V*__*.sql \
 && { cat /app/migracoes.txt; sha256sum V*__*.sql; } | sort -k2 -V > /tmp/m && install -m 0444 /tmp/m /app/migracoes.txt && rm /tmp/m
ENV SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:/app/migracoes-teste
LABEL fluxo.somente-teste="migracoes artificiais de teste"
USER 10001:10001
