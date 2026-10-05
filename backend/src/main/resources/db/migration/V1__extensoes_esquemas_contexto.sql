-- =============================================================================
-- V1 — Extensões, esquemas e funções de contexto da requisição
--
-- Convenções (ver docs/adr/0004-isolamento-por-unidade.md):
--   * Esta migração roda com o papel DONO do banco (Flyway). A aplicação conecta
--     com o papel ${app_role}, que NÃO é dono, NÃO é superusuário e NÃO tem BYPASSRLS.
--   * A cada transação a aplicação informa o contexto via set_config(..., true):
--       fluxo.usuario_id    -> UUID do usuário autenticado
--       fluxo.unidade_ids   -> UUIDs (separados por vírgula) das unidades do usuário
--       fluxo.origem_ip     -> IP de origem da requisição
--       fluxo.correlacao_id -> ID de correlação (rastreamento de logs)
--     O terceiro argumento "true" limita o valor à transação corrente, o que é
--     seguro com pool de conexões (o valor some no COMMIT/ROLLBACK).
-- =============================================================================

-- SCHEMA public explícito: o Flyway põe "fluxo" à frente do search_path e, sem isto,
-- as extensões iriam para "fluxo", quebrando as referências public.* abaixo.
CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;   -- digest() p/ cadeia de hash da auditoria
CREATE EXTENSION IF NOT EXISTS citext   SCHEMA public;   -- login case-insensitive
CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;   -- busca por nome sem acento (RF-029)
CREATE EXTENSION IF NOT EXISTS pg_trgm  SCHEMA public;   -- busca aproximada por nome (RF-029)

CREATE SCHEMA IF NOT EXISTS fluxo;
CREATE SCHEMA IF NOT EXISTS auditoria;

-- Funções criadas pelo dono NÃO são executáveis por PUBLIC por padrão: cada uma
-- que a aplicação precisa recebe GRANT explícito (mínimo privilégio).
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- Ninguém além do dono cria objetos nos esquemas da aplicação.
-- (O endurecimento do esquema "public" — REVOKE CREATE — é feito pelo DBA em
--  infra/db/init/01-bootstrap.sh, pois "public" pertence ao superusuário.)
REVOKE ALL ON SCHEMA fluxo FROM PUBLIC;
REVOKE ALL ON SCHEMA auditoria FROM PUBLIC;
GRANT USAGE ON SCHEMA fluxo, auditoria TO ${app_role};

-- -----------------------------------------------------------------------------
-- Funções de contexto. STABLE + uso como (SELECT fluxo.ctx_...()) nas políticas
-- RLS faz o PostgreSQL avaliar uma única vez por consulta (InitPlan).
-- Valores malformados geram erro (falha fechada), nunca "acesso a tudo".
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.ctx_usuario() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    SET search_path = pg_catalog
AS $$ SELECT nullif(current_setting('fluxo.usuario_id', true), '')::uuid $$;

CREATE FUNCTION fluxo.ctx_unidades() RETURNS uuid[]
    LANGUAGE sql STABLE PARALLEL SAFE
    SET search_path = pg_catalog
AS $$
    SELECT coalesce(
        string_to_array(nullif(current_setting('fluxo.unidade_ids', true), ''), ',')::uuid[],
        '{}'::uuid[])
$$;

-- Normalização para busca (minúsculas, sem acento, espaços colapsados).
-- unaccent() não é IMMUTABLE; fixar o dicionário torna o resultado determinístico
-- e permite índice funcional.
CREATE FUNCTION fluxo.normalizar_texto(p text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    SET search_path = pg_catalog, public
AS $$ SELECT regexp_replace(lower(public.unaccent('public.unaccent'::regdictionary, p)), '\s+', ' ', 'g') $$;

-- IP e correlação: valores inválidos viram NULL (não derrubam a transação de negócio);
-- correlação truncada em 64 caracteres.
CREATE FUNCTION fluxo.ctx_ip() RETURNS inet
    LANGUAGE plpgsql STABLE
    SET search_path = pg_catalog
AS $$
BEGIN
    RETURN nullif(current_setting('fluxo.origem_ip', true), '')::inet;
EXCEPTION WHEN invalid_text_representation THEN
    RETURN NULL;
END $$;

CREATE FUNCTION fluxo.ctx_correlacao() RETURNS text
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$ SELECT left(nullif(current_setting('fluxo.correlacao_id', true), ''), 64) $$;

-- Instante máximo aceito para fatos informados (tolerância a diferença de relógio).
-- VOLATILE: usa clock_timestamp().
CREATE FUNCTION fluxo.limite_futuro() RETURNS timestamptz
    LANGUAGE sql VOLATILE
    SET search_path = pg_catalog
AS $$ SELECT clock_timestamp() + interval '2 minutes' $$;

-- Exige usuário no contexto quando a sessão é da aplicação (falha fechada).
CREATE FUNCTION fluxo.exigir_usuario() RETURNS uuid
    LANGUAGE plpgsql STABLE
    SET search_path = pg_catalog
AS $$
DECLARE
    v uuid := fluxo.ctx_usuario();
BEGIN
    IF v IS NULL AND pg_has_role(session_user, '${app_role}', 'MEMBER') THEN
        RAISE EXCEPTION 'contexto de usuário ausente' USING ERRCODE = '42501';
    END IF;
    RETURN v;
END $$;

GRANT EXECUTE ON FUNCTION fluxo.ctx_usuario(), fluxo.ctx_unidades(), fluxo.normalizar_texto(text),
                          fluxo.ctx_ip(), fluxo.ctx_correlacao(), fluxo.limite_futuro(),
                          fluxo.exigir_usuario() TO ${app_role};

-- Autoria e instante de criação definidos pelo BANCO (não forjáveis pela aplicação).
-- Argumentos: nome da coluna de autor, nome da coluna de instante (opcional).
CREATE FUNCTION fluxo.tg_autoria() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
DECLARE
    v_usuario uuid := fluxo.exigir_usuario();
    v_ajuste  jsonb := '{}'::jsonb;
BEGIN
    IF v_usuario IS NOT NULL THEN
        v_ajuste := jsonb_build_object(TG_ARGV[0], v_usuario);
    END IF;
    IF TG_NARGS > 1 THEN
        v_ajuste := v_ajuste || jsonb_build_object(TG_ARGV[1], clock_timestamp());
    END IF;
    NEW := jsonb_populate_record(NEW, v_ajuste);
    RETURN NEW;
END $$;

-- Mantém atualizado_em coerente sem depender da aplicação.
CREATE FUNCTION fluxo.tg_atualizado_em() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    NEW.atualizado_em := clock_timestamp();
    RETURN NEW;
END $$;
