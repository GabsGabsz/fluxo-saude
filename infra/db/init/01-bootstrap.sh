#!/usr/bin/env bash
# =============================================================================
# Bootstrap do PostgreSQL (executado UMA vez, como superusuário).
# Usado pelo docker-compose (dev), pelos testes de integração e como referência
# para o DBA em produção. Ver docs/adr/0004-isolamento-por-unidade.md.
#
#   fluxo_owner -> dono do banco/objetos; usado SOMENTE pelo Flyway (migrações)
#   fluxo_app   -> usado pela aplicação; sem DDL, sem BYPASSRLS, sem superusuário
#
# Senhas vêm do ambiente — nunca versionadas.
# =============================================================================
set -euo pipefail
: "${FLUXO_OWNER_PASSWORD:?defina FLUXO_OWNER_PASSWORD}"
: "${FLUXO_APP_PASSWORD:?defina FLUXO_APP_PASSWORD}"
FLUXO_DB="${FLUXO_DB:-fluxo}"

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" --dbname postgres \
     -v owner_pw="$FLUXO_OWNER_PASSWORD" -v app_pw="$FLUXO_APP_PASSWORD" -v db="$FLUXO_DB" <<'SQL'
SET password_encryption = 'scram-sha-256';

CREATE ROLE fluxo_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'owner_pw';
CREATE ROLE fluxo_app   LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT
    CONNECTION LIMIT 60 PASSWORD :'app_pw';

CREATE DATABASE :"db" OWNER fluxo_owner ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO fluxo_app;   -- sem TEMPORARY

-- Proteções operacionais do papel da aplicação.
-- idle_in_transaction é essencial: a cadeia de auditoria usa lock de transação;
-- uma transação esquecida aberta não pode travar todos os escritores.
ALTER ROLE fluxo_app SET statement_timeout = '15s';
ALTER ROLE fluxo_app SET lock_timeout = '5s';
ALTER ROLE fluxo_app SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE fluxo_app SET default_transaction_isolation = 'read committed';
ALTER ROLE fluxo_app SET timezone = 'UTC';
ALTER ROLE fluxo_owner SET timezone = 'UTC';

\connect :"db"
-- PostgreSQL < 15 permite CREATE em public para todos; endurecer.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
echo "bootstrap do banco ${FLUXO_DB} concluído"
