-- =============================================================================
-- V17 — Registro das LEITURAS nominais da passagem de plantão (RNF-002, ADR-0003;
-- revisão do PR #10, ponto 3) — ver docs/adr/0009-plantao-e-indicadores.md
--
-- A prévia e o detalhe da passagem exibem nomes de pacientes e descrições de pendências de
-- muitos casos de uma vez. O registro precisa dizer QUEM leu, EM QUE unidade, QUANDO e QUAIS
-- casos, sem copiar nomes ou descrições, e cabe no limite de 4 KB dos dados de um evento.
--
-- Representação: o conjunto de episódios exibidos é gravado UMA vez por unidade, endereçado
-- pelo SHA-256 da lista ordenada de ids (auditoria.conjunto_consultado). O evento da cadeia de
-- auditoria leva só o hash e a contagem. Leituras repetidas do mesmo conjunto (o caso comum:
-- várias aberturas da prévia sem mudança de casos) não duplicam a lista. O hash fica dentro do
-- registro encadeado, então trocar o conjunto depois seria detectável; além disso o conjunto é
-- imutável e o próprio CHECK recusa lista que não corresponda ao hash.
-- =============================================================================

CREATE TABLE auditoria.conjunto_consultado (
    unidade_id uuid        NOT NULL,
    hash       bytea       NOT NULL CHECK (length(hash) = 32),
    episodios  uuid[]      NOT NULL CHECK (cardinality(episodios) <= 5000 AND array_position(episodios, NULL) IS NULL),
    criado_em  timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (unidade_id, hash),
    CONSTRAINT conjunto_hash_confere
        CHECK (hash = public.digest(convert_to(array_to_string(episodios, ','), 'UTF8'), 'sha256'))
);
COMMENT ON TABLE auditoria.conjunto_consultado IS
    'Conjuntos de episódios exibidos em leituras nominais (só ids), endereçados por SHA-256; referenciados por auditoria.registro.dados->>''conjunto''';

CREATE TRIGGER conjunto_consultado_imutavel BEFORE UPDATE OR DELETE ON auditoria.conjunto_consultado
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER conjunto_consultado_sem_truncate BEFORE TRUNCATE ON auditoria.conjunto_consultado
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();

-- Leitura pela aplicação: só os conjuntos das unidades do contexto (como auditoria.registro).
ALTER TABLE auditoria.conjunto_consultado ENABLE ROW LEVEL SECURITY;
CREATE POLICY unidade_isolamento ON auditoria.conjunto_consultado FOR SELECT TO ${app_role}
    USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));
REVOKE ALL ON auditoria.conjunto_consultado FROM PUBLIC;
GRANT SELECT ON auditoria.conjunto_consultado TO ${app_role};

-- -----------------------------------------------------------------------------
-- Registra uma leitura nominal. Ator, instante, IP e correlação vêm do contexto do banco
-- (auditoria.registrar); a unidade precisa estar no contexto; os episódios precisam ser DESSA
-- unidade (referências verificáveis, nada de outra unidade). A lista é normalizada (sem nulos,
-- sem repetição, ordenada) antes do hash. Exige READ COMMITTED, como toda escrita na cadeia.
-- -----------------------------------------------------------------------------
CREATE FUNCTION auditoria.registrar_consulta(
    p_acao       text,
    p_recurso    text,
    p_recurso_id text,
    p_episodios  uuid[],
    p_dados      jsonb,
    p_unidade_id uuid
) RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_ids  uuid[];
    v_hash bytea;
BEGIN
    IF fluxo.ctx_usuario() IS NULL THEN
        RAISE EXCEPTION 'contexto de usuário ausente' USING ERRCODE = '42501';
    END IF;
    IF p_acao IS NULL OR p_acao !~ '^CONSULTA_[A-Z_]{3,55}$' THEN
        RAISE EXCEPTION 'ação de consulta inválida: %', p_acao USING ERRCODE = '22023';
    END IF;
    IF p_unidade_id IS NULL OR NOT (p_unidade_id = ANY (fluxo.ctx_unidades())) THEN
        RAISE EXCEPTION 'unidade fora do contexto autenticado' USING ERRCODE = '42501';
    END IF;
    IF p_dados IS NULL OR jsonb_typeof(p_dados) <> 'object' OR p_dados ? 'conjunto' OR p_dados ? 'episodios' THEN
        RAISE EXCEPTION 'dados da consulta inválidos' USING ERRCODE = '22023';
    END IF;

    SELECT coalesce(array_agg(DISTINCT x ORDER BY x), '{}'::uuid[]) INTO v_ids
      FROM unnest(coalesce(p_episodios, '{}'::uuid[])) AS x
     WHERE x IS NOT NULL;
    IF cardinality(v_ids) > 5000 THEN
        RAISE EXCEPTION 'conjunto consultado grande demais' USING ERRCODE = '22023';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(v_ids) AS x
                WHERE NOT EXISTS (SELECT 1 FROM fluxo.episodio e WHERE e.id = x AND e.unidade_id = p_unidade_id)) THEN
        RAISE EXCEPTION 'episódio fora da unidade da consulta' USING ERRCODE = '42501';
    END IF;

    v_hash := public.digest(convert_to(array_to_string(v_ids, ','), 'UTF8'), 'sha256');
    INSERT INTO auditoria.conjunto_consultado (unidade_id, hash, episodios)
    VALUES (p_unidade_id, v_hash, v_ids)
    ON CONFLICT (unidade_id, hash) DO NOTHING;

    RETURN auditoria.registrar(p_acao, p_recurso, p_recurso_id,
                               p_dados || jsonb_build_object('conjunto', encode(v_hash, 'hex'),
                                                             'episodios', cardinality(v_ids)),
                               p_unidade_id);
END $$;
REVOKE ALL ON FUNCTION auditoria.registrar_consulta(text, text, text, uuid[], jsonb, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auditoria.registrar_consulta(text, text, text, uuid[], jsonb, uuid) TO ${app_role};
