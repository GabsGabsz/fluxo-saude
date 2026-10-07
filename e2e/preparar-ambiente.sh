#!/usr/bin/env bash
# =============================================================================
# Sobe o sistema COMPLETO e REAL para os testes E2E (usado pelo CI e localmente):
#   PostgreSQL (bootstrap de produção) -> job de migração -> dados fictícios -> aplicação (jar).
#
# Pré-requisitos: PostgreSQL acessível com um SUPERUSUÁRIO em PGHOST/PGUSER (banco descartável!),
# psql, JDK 21, jar já construído (cd backend && mvn -DskipTests package), python3 com
# argon2-cffi (pip install argon2-cffi==25.1.0).
#
# Senhas dos papéis: geradas aleatoriamente se não vierem do ambiente; nunca versionadas.
# Cookie de sessão sem Secure/__Host- SOMENTE porque o E2E roda em http://localhost; em
# produção a aplicação fica atrás de TLS com a configuração padrão (__Host-FLUXO, Secure).
# =============================================================================
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
: "${PGHOST:?defina PGHOST}"
: "${PGUSER:?defina PGUSER (superusuário do banco descartável)}"
PORTA="${E2E_PORTA:-8080}"
export FLUXO_DB="${FLUXO_DB:-fluxo}"
export FLUXO_OWNER_PASSWORD="${FLUXO_OWNER_PASSWORD:-$(openssl rand -hex 24)}"
export FLUXO_APP_PASSWORD="${FLUXO_APP_PASSWORD:-$(openssl rand -hex 24)}"
URL="jdbc:postgresql://${PGHOST}:${PGPORT:-5432}/${FLUXO_DB}"

JAR="$(ls "$RAIZ"/backend/target/fluxo-saude-backend-*.jar 2>/dev/null | grep -v -e '\.original$' -e 'plain' | head -1 || true)"
[ -n "$JAR" ] || { echo "jar não encontrado: rode 'cd backend && mvn -DskipTests package'"; exit 1; }

echo "== 1/4 bootstrap do banco (papéis fluxo_owner/fluxo_app)"
POSTGRES_USER="$PGUSER" "$RAIZ/infra/db/init/01-bootstrap.sh"

echo "== 2/4 migrações (job separado, único com a credencial do dono)"
FLUXO_DB_URL="$URL" FLUXO_DB_OWNER_PASSWORD="$FLUXO_OWNER_PASSWORD" \
  java -jar "$JAR" --spring.profiles.active=migracao

echo "== 3/4 dados fictícios do E2E"
python3 "$RAIZ/e2e/dados/gerar-seed.py" \
  | PGPASSWORD="$FLUXO_OWNER_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 -U fluxo_owner -d "$FLUXO_DB" >/dev/null

echo "== 4/4 aplicação (somente a credencial restrita fluxo_app) na porta $PORTA"
mkdir -p "$RAIZ/e2e/aplicacao"   # fora de "resultados" (o Playwright limpa essa pasta)
( unset FLUXO_OWNER_PASSWORD
  FLUXO_DB_URL="$URL" FLUXO_DB_APP_PASSWORD="$FLUXO_APP_PASSWORD" nohup java -jar "$JAR" \
    --server.port="$PORTA" \
    --server.servlet.session.cookie.secure=false \
    --server.servlet.session.cookie.name=FLUXO \
    > "$RAIZ/e2e/aplicacao/aplicacao.log" 2>&1 &
  echo $! > "$RAIZ/e2e/aplicacao/aplicacao.pid" )

for _ in $(seq 1 90); do
  if curl -fsS "http://localhost:${PORTA}/actuator/health" >/dev/null 2>&1; then
    echo "aplicação no ar: http://localhost:${PORTA}/"
    exit 0
  fi
  sleep 2
done
echo "a aplicação não respondeu ao health check; últimas linhas do log:"
tail -50 "$RAIZ/e2e/aplicacao/aplicacao.log"
exit 1
