-- =============================================================================
-- V3 — Auditoria imutável com cadeia de hash (RN-004, RN-010, RNF-002, RNF-010)
-- Decisão e trade-offs: docs/adr/0003-auditoria-imutavel.md
--
-- Garantias:
--   1. A aplicação NÃO tem INSERT/UPDATE/DELETE na tabela; só SELECT (sob RLS por
--      unidade) e as funções SECURITY DEFINER abaixo.
--   2. UPDATE, DELETE e TRUNCATE são rejeitados por trigger.
--   3. Cada registro carrega SHA-256(hash_anterior || conteúdo canônico) e a
--      "cabeça" da cadeia guarda o último elo: alteração, remoção no meio, no
--      início ou no fim são detectáveis por auditoria.verificar_cadeia().
--   4. "origem" distingue captura automática do banco (BANCO) de evento declarado
--      pela aplicação (APLICACAO) — a aplicação não consegue forjar uma captura.
-- =============================================================================

CREATE SEQUENCE auditoria.registro_seq AS bigint;

CREATE TABLE auditoria.registro (
    id             bigint      PRIMARY KEY,
    ocorrido_em    timestamptz NOT NULL,
    origem         text        NOT NULL CHECK (origem IN ('BANCO', 'APLICACAO')),
    usuario_id     uuid,                    -- sem FK de propósito: o log sobrevive a tudo
    unidade_id     uuid,
    origem_ip      inet,
    correlacao_id  text CHECK (correlacao_id IS NULL OR length(correlacao_id) <= 64),
    acao           text NOT NULL CHECK (acao ~ '^[A-Z_]{3,64}$'),
    recurso        text NOT NULL CHECK (length(recurso) BETWEEN 1 AND 128),
    recurso_id     text CHECK (recurso_id IS NULL OR length(recurso_id) <= 128),
    dados          jsonb NOT NULL DEFAULT '{}'::jsonb,
    hash_anterior  bytea,
    hash           bytea NOT NULL CHECK (length(hash) = 32)
);
CREATE INDEX registro_recurso_idx ON auditoria.registro (recurso, recurso_id, id);
CREATE INDEX registro_usuario_idx ON auditoria.registro (usuario_id, ocorrido_em);
CREATE INDEX registro_unidade_idx ON auditoria.registro (unidade_id, ocorrido_em);

-- Cabeça da cadeia: linha única, bloqueada (FOR UPDATE) por quem acrescenta um elo.
-- A aplicação não tem nenhum privilégio aqui, logo não consegue segurar o bloqueio.
CREATE TABLE auditoria.cadeia_cabeca (
    id          smallint PRIMARY KEY CHECK (id = 1),
    ultimo_id   bigint,
    ultimo_hash bytea
);
INSERT INTO auditoria.cadeia_cabeca (id) VALUES (1);

-- Representação canônica e inequívoca (quote_nullable distingue NULL de '').
CREATE FUNCTION auditoria.canonico(r auditoria.registro) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    SET search_path = pg_catalog
AS $$
    SELECT concat_ws('|',
        r.id::text,
        (extract(epoch FROM r.ocorrido_em) * 1000000)::bigint::text,
        quote_literal(r.origem),
        quote_nullable(r.usuario_id::text),
        quote_nullable(r.unidade_id::text),
        quote_nullable(r.origem_ip::text),
        quote_nullable(r.correlacao_id),
        quote_literal(r.acao),
        quote_literal(r.recurso),
        quote_nullable(r.recurso_id),
        quote_literal(r.dados::text))
$$;

CREATE FUNCTION auditoria.calcular_hash(p_anterior bytea, r auditoria.registro) RETURNS bytea
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    SET search_path = pg_catalog
AS $$ SELECT public.digest(coalesce(p_anterior, '\x'::bytea) || convert_to(auditoria.canonico(r), 'UTF8'), 'sha256') $$;

-- Encadeamento. O bloqueio da cabeça serializa os escritores até o COMMIT, então o
-- "último hash" lido já está confirmado. Exige READ COMMITTED (em REPEATABLE READ o
-- snapshot é fixo e a cabeça atualizada por outra transação causaria erro de
-- serialização em vez de bifurcação — mas recusamos explicitamente para clareza).
CREATE FUNCTION auditoria.tg_encadear() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
DECLARE
    v_cabeca auditoria.cadeia_cabeca;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'auditoria exige isolamento READ COMMITTED (atual: %)',
            current_setting('transaction_isolation') USING ERRCODE = '25000';
    END IF;

    SELECT * INTO STRICT v_cabeca FROM auditoria.cadeia_cabeca WHERE id = 1 FOR UPDATE;

    NEW.id            := nextval('auditoria.registro_seq');
    NEW.ocorrido_em   := clock_timestamp();
    NEW.hash_anterior := v_cabeca.ultimo_hash;
    NEW.hash          := auditoria.calcular_hash(v_cabeca.ultimo_hash, NEW);

    UPDATE auditoria.cadeia_cabeca SET ultimo_id = NEW.id, ultimo_hash = NEW.hash WHERE id = 1;
    RETURN NEW;
END $$;

CREATE FUNCTION auditoria.tg_imutavel() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'registros imutáveis: % bloqueado em %.%', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = '42501';
END $$;

CREATE TRIGGER registro_encadear BEFORE INSERT ON auditoria.registro
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_encadear();
CREATE TRIGGER registro_imutavel BEFORE UPDATE OR DELETE ON auditoria.registro
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER registro_sem_truncate BEFORE TRUNCATE ON auditoria.registro
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();

-- Verificação de integridade (SECURITY DEFINER: percorre a cadeia inteira, sem RLS).
-- Devolve apenas IDs e o tipo de problema — nenhum conteúdo.
CREATE FUNCTION auditoria.verificar_cadeia()
    RETURNS TABLE (registro_id bigint, problema text)
    LANGUAGE plpgsql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    r          auditoria.registro;
    v_anterior bytea := NULL;
    v_ultimo   bigint := NULL;
    v_primeiro boolean := true;
    v_cabeca   auditoria.cadeia_cabeca;
BEGIN
    FOR r IN SELECT * FROM auditoria.registro ORDER BY id LOOP
        IF v_primeiro AND r.hash_anterior IS NOT NULL THEN
            registro_id := r.id; problema := 'início da cadeia ausente (registros iniciais removidos)';
            RETURN NEXT;
        ELSIF NOT v_primeiro AND r.hash_anterior IS DISTINCT FROM v_anterior THEN
            registro_id := r.id; problema := 'elo anterior não confere (remoção ou reordenação)';
            RETURN NEXT;
        END IF;
        IF r.hash <> auditoria.calcular_hash(r.hash_anterior, r) THEN
            registro_id := r.id; problema := 'conteúdo alterado (hash não confere)';
            RETURN NEXT;
        END IF;
        v_anterior := r.hash;
        v_ultimo   := r.id;
        v_primeiro := false;
    END LOOP;

    SELECT * INTO STRICT v_cabeca FROM auditoria.cadeia_cabeca WHERE id = 1;
    IF v_cabeca.ultimo_id IS DISTINCT FROM v_ultimo OR v_cabeca.ultimo_hash IS DISTINCT FROM v_anterior THEN
        registro_id := v_cabeca.ultimo_id; problema := 'fim da cadeia não confere com a cabeça (registros finais removidos)';
        RETURN NEXT;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- Captura automática de alterações de dados (AFTER INSERT/UPDATE/DELETE).
-- TG_ARGV = colunas cujo VALOR não deve ir para o log (dados pessoais, textos
-- livres, hash de senha): registra-se apenas que mudaram. O log é imutável e não
-- pode virar uma segunda cópia permanente de dados pessoais (LGPD, minimização).
-- -----------------------------------------------------------------------------
CREATE FUNCTION auditoria.tg_capturar() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_antes   jsonb;
    v_depois  jsonb;
    v_ref     jsonb;
    v_redigir text[] := TG_ARGV;
    v_ruido   text[] := ARRAY['atualizado_em', 'versao'];
    v_acao    text;
    v_usuario uuid := fluxo.exigir_usuario();   -- falha fechada
BEGIN
    IF TG_OP = 'INSERT' THEN
        v_acao := 'CRIAR';
        v_depois := to_jsonb(NEW);
        v_ref := v_depois;
    ELSIF TG_OP = 'DELETE' THEN
        v_acao := 'EXCLUIR';
        v_antes := to_jsonb(OLD);
        v_ref := v_antes;
    ELSE
        v_acao := 'ALTERAR';
        v_ref := to_jsonb(NEW);
        SELECT jsonb_object_agg(o.key, o.value), jsonb_object_agg(o.key, v_ref -> o.key)
          INTO v_antes, v_depois
          FROM jsonb_each(to_jsonb(OLD)) o
         WHERE (v_ref -> o.key) IS DISTINCT FROM o.value
           AND o.key <> ALL (v_ruido);
        IF v_antes IS NULL THEN
            RETURN NULL;  -- só metadados mudaram; nada de negócio a registrar
        END IF;
    END IF;

    IF cardinality(v_redigir) > 0 THEN
        SELECT jsonb_object_agg(e.key, CASE WHEN e.key = ANY (v_redigir) AND e.value <> 'null'::jsonb
                                            THEN to_jsonb('[redigido]'::text) ELSE e.value END)
          INTO v_antes FROM jsonb_each(v_antes) e;
        SELECT jsonb_object_agg(e.key, CASE WHEN e.key = ANY (v_redigir) AND e.value <> 'null'::jsonb
                                            THEN to_jsonb('[redigido]'::text) ELSE e.value END)
          INTO v_depois FROM jsonb_each(v_depois) e;
    END IF;

    INSERT INTO auditoria.registro
        (origem, usuario_id, unidade_id, origem_ip, correlacao_id, acao, recurso, recurso_id, dados)
    VALUES (
        'BANCO',
        v_usuario,
        coalesce(v_ref ->> 'unidade_id', CASE WHEN TG_TABLE_NAME = 'unidade' THEN v_ref ->> 'id' END)::uuid,
        fluxo.ctx_ip(),
        fluxo.ctx_correlacao(),
        v_acao,
        TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME,
        coalesce(v_ref ->> 'id', v_ref ->> 'usuario_id'),
        jsonb_strip_nulls(jsonb_build_object('antes', v_antes, 'depois', v_depois)));
    RETURN NULL;
END $$;

-- -----------------------------------------------------------------------------
-- Eventos declarados pela aplicação (consulta sensível, exportação, logout...).
-- Exige usuário autenticado no contexto; o autor é SEMPRE o do contexto e a
-- unidade, se informada, precisa estar no contexto. Ações de captura (CRIAR,
-- ALTERAR, EXCLUIR) são reservadas ao banco. Eventos de login usam funções
-- próprias (V6), pois ocorrem antes de haver contexto.
-- -----------------------------------------------------------------------------
CREATE FUNCTION auditoria.registrar(
    p_acao       text,
    p_recurso    text,
    p_recurso_id text   DEFAULT NULL,
    p_dados      jsonb  DEFAULT '{}'::jsonb,
    p_unidade_id uuid   DEFAULT NULL
) RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_usuario uuid := fluxo.ctx_usuario();
    v_id      bigint;
BEGIN
    IF v_usuario IS NULL THEN
        RAISE EXCEPTION 'contexto de usuário ausente' USING ERRCODE = '42501';
    END IF;
    IF p_acao IN ('CRIAR', 'ALTERAR', 'EXCLUIR') OR p_acao LIKE 'LOGIN\_%' THEN
        RAISE EXCEPTION 'ação reservada: %', p_acao USING ERRCODE = '42501';
    END IF;
    IF p_unidade_id IS NOT NULL AND NOT (p_unidade_id = ANY (fluxo.ctx_unidades())) THEN
        RAISE EXCEPTION 'unidade fora do contexto autenticado' USING ERRCODE = '42501';
    END IF;
    IF p_dados IS NOT NULL AND (jsonb_typeof(p_dados) <> 'object' OR pg_column_size(p_dados) > 4096) THEN
        RAISE EXCEPTION 'dados do evento devem ser um objeto de até 4 KB' USING ERRCODE = '22023';
    END IF;

    INSERT INTO auditoria.registro
        (origem, usuario_id, unidade_id, origem_ip, correlacao_id, acao, recurso, recurso_id, dados)
    VALUES ('APLICACAO', v_usuario, p_unidade_id, fluxo.ctx_ip(), fluxo.ctx_correlacao(),
            p_acao, p_recurso, p_recurso_id, coalesce(p_dados, '{}'::jsonb))
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

-- Leitura pela aplicação: somente registros das unidades do contexto (RLS).
-- Registros sem unidade (ex.: login) ficam acessíveis só por função de auditoria
-- dedicada (perfil AUDITORIA), a ser criada com o módulo de relatórios.
ALTER TABLE auditoria.registro ENABLE ROW LEVEL SECURITY;
CREATE POLICY unidade_isolamento ON auditoria.registro FOR SELECT TO ${app_role}
    USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));

REVOKE ALL ON ALL TABLES IN SCHEMA auditoria FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA auditoria FROM PUBLIC;
GRANT SELECT ON auditoria.registro TO ${app_role};
GRANT EXECUTE ON FUNCTION auditoria.registrar(text, text, text, jsonb, uuid),
                          auditoria.verificar_cadeia() TO ${app_role};
