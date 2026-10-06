#!/usr/bin/env bash
# Executa as migrações e os testes SQL contra um PostgreSQL real (sem Java).
# Uso: PGHOST=... PGUSER=<superusuário> ./run-db-tests.sh
# Cria um banco descartável, papéis fluxo_owner/fluxo_app e roda cada teste.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
MIG="$DIR/../../main/resources/db/migration"
DB="fluxo_test_$$"
APP_ROLE=fluxo_app

psql -X -q -v ON_ERROR_STOP=1 -d postgres <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='fluxo_owner') THEN
    CREATE ROLE fluxo_owner LOGIN NOSUPERUSER NOCREATEROLE NOBYPASSRLS PASSWORD 'owner_test';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='${APP_ROLE}') THEN
    CREATE ROLE ${APP_ROLE} LOGIN NOSUPERUSER NOCREATEROLE NOBYPASSRLS PASSWORD 'app_test';
  END IF;
END \$\$;
CREATE DATABASE ${DB} OWNER fluxo_owner;
SQL
trap 'psql -X -q -d postgres -c "DROP DATABASE IF EXISTS ${DB} WITH (FORCE)" >/dev/null' EXIT

for f in $(ls "$MIG"/V*__*.sql | sort -V); do
  # search_path igual ao do Flyway (spring.flyway.schemas=fluxo,auditoria)
  sed "s/\${app_role}/${APP_ROLE}/g" "$f" | PGOPTIONS="-c search_path=fluxo,auditoria" \
    psql -X -q -v ON_ERROR_STOP=1 -1 -U fluxo_owner -d "$DB" >/dev/null \
    || { echo "FALHA na migração $(basename "$f")"; exit 1; }
  echo "migração ok: $(basename "$f")"
done

psql -X -q -v ON_ERROR_STOP=1 -U fluxo_owner -d "$DB" -f "$DIR/_fixtures.sql" >/dev/null \
  || { echo "FALHA nas fixtures"; exit 1; }

shopt -s nullglob
testes=("$DIR"/[to][0-9]*.sql)
[ "${#testes[@]}" -gt 0 ] || { echo "Nenhum teste encontrado"; exit 1; }
falhas=0
# t*.sql rodam como o papel da APLICAÇÃO; o*.sql rodam como DONO (cenários de DBA).
for t in "${testes[@]}"; do
  case "$(basename "$t")" in o*) role=fluxo_owner;; *) role="${APP_ROLE}";; esac
  if out=$(psql -X -q -v ON_ERROR_STOP=1 -U "$role" -d "$DB" -f "$t" 2>&1); then
    echo "PASSOU  $(basename "$t")"
  else
    echo "FALHOU  $(basename "$t")"; echo "$out" | tail -15; falhas=$((falhas+1))
  fi
done
if DB="$DB" "$DIR/concorrencia-auditoria.sh" 8 25; then echo "PASSOU  concorrencia-auditoria"; else echo "FALHOU  concorrencia-auditoria"; falhas=$((falhas+1)); fi
if DB="$DB" "$DIR/concorrencia-desfecho.sh"; then echo "PASSOU  concorrencia-desfecho"; else echo "FALHOU  concorrencia-desfecho"; falhas=$((falhas+1)); fi
if DB="$DB" "$DIR/concorrencia-administradores.sh"; then echo "PASSOU  concorrencia-administradores"; else echo "FALHOU  concorrencia-administradores"; falhas=$((falhas+1)); fi
if DB="$DB" "$DIR/concorrencia-credencial.sh"; then echo "PASSOU  concorrencia-credencial"; else echo "FALHOU  concorrencia-credencial"; falhas=$((falhas+1)); fi
if DB="$DB" "$DIR/concorrencia-ciencia.sh"; then echo "PASSOU  concorrencia-ciencia"; else echo "FALHOU  concorrencia-ciencia"; falhas=$((falhas+1)); fi
[ "$falhas" -eq 0 ] && echo "Todos os testes SQL passaram." || { echo "$falhas teste(s) falharam."; exit 1; }
