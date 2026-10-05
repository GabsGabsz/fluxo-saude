-- =============================================================================
-- V8 — Revalidação da autorização a cada transação e proteção do hash de senha
-- (RF-001, RNF-001, RNF-013, CA-11) — ADR-0002 §7
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Contexto validado: a aplicação não "declara" mais quem é o usuário e onde
--    atua; o banco CONFERE, a cada transação, que o usuário está ativo e lotado na
--    unidade (em unidade ativa) e devolve os papéis vigentes. Usuário desativado ou
--    com lotação removida perde o acesso na requisição seguinte (revogação real),
--    sem esperar a sessão expirar. Retorna NULL quando o contexto é inválido.
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.aplicar_contexto(p_usuario_id uuid, p_unidade_id uuid, p_ip text, p_correlacao text)
    RETURNS text[]
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_papeis text[];
BEGIN
    IF p_usuario_id IS NULL
       OR NOT EXISTS (SELECT 1 FROM fluxo.usuario u WHERE u.id = p_usuario_id AND u.ativo) THEN
        RETURN NULL;
    END IF;
    IF p_unidade_id IS NOT NULL THEN
        SELECT array_agg(l.papel::text ORDER BY l.papel::text) INTO v_papeis
          FROM fluxo.lotacao l
          JOIN fluxo.unidade un ON un.id = l.unidade_id AND un.ativa
         WHERE l.usuario_id = p_usuario_id AND l.unidade_id = p_unidade_id;
        IF v_papeis IS NULL THEN
            RETURN NULL;
        END IF;
    END IF;
    PERFORM set_config('fluxo.usuario_id', p_usuario_id::text, true),
            set_config('fluxo.unidade_ids', coalesce(p_unidade_id::text, ''), true),
            set_config('fluxo.origem_ip', coalesce(p_ip, ''), true),
            set_config('fluxo.correlacao_id', coalesce(p_correlacao, ''), true);
    RETURN coalesce(v_papeis, '{}'::text[]);
END $$;

-- -----------------------------------------------------------------------------
-- 2. Hash de senha fora do alcance de UPDATE/SELECT direto pela aplicação.
--    Colegas da mesma unidade enxergam o cadastro (RLS), mas NÃO o hash e não podem
--    trocá-lo. A troca é feita pelo próprio usuário via função.
-- -----------------------------------------------------------------------------
REVOKE SELECT, UPDATE ON fluxo.usuario FROM ${app_role};
GRANT SELECT (id, login, nome, email, registro_profissional, ativo, deve_trocar_senha, senha_alterada_em,
              criado_em, atualizado_em, versao) ON fluxo.usuario TO ${app_role};
GRANT UPDATE (nome, email, registro_profissional, ativo, deve_trocar_senha, versao) ON fluxo.usuario TO ${app_role};

CREATE FUNCTION fluxo.hash_senha_propria() RETURNS text
    LANGUAGE sql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$ SELECT u.senha_hash FROM fluxo.usuario u WHERE u.id = fluxo.ctx_usuario() $$;

CREATE FUNCTION fluxo.alterar_senha_propria(p_hash text) RETURNS boolean
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_usuario uuid := fluxo.ctx_usuario();
BEGIN
    IF v_usuario IS NULL THEN
        RAISE EXCEPTION 'contexto de usuário ausente' USING ERRCODE = '42501';
    END IF;
    UPDATE fluxo.usuario
       SET senha_hash = p_hash, deve_trocar_senha = false, senha_alterada_em = clock_timestamp(),
           versao = versao + 1
     WHERE id = v_usuario;
    RETURN FOUND;
END $$;

-- Tentativa de login em conta bloqueada: auditada SEM incrementar o contador
-- (tentativas durante o bloqueio não o prolongam).
CREATE FUNCTION fluxo.registrar_tentativa_bloqueada(p_usuario_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF fluxo.ctx_usuario() IS NOT NULL THEN
        RAISE EXCEPTION 'tentativa de login não pode ocorrer com usuário já no contexto' USING ERRCODE = '42501';
    END IF;
    INSERT INTO auditoria.registro (origem, usuario_id, origem_ip, correlacao_id, acao, recurso, recurso_id, dados)
    VALUES ('APLICACAO', p_usuario_id, fluxo.ctx_ip(), fluxo.ctx_correlacao(), 'LOGIN_FALHA', 'autenticacao',
            p_usuario_id::text, '{"motivo": "conta_bloqueada"}');
    RETURN true;
END $$;

REVOKE ALL ON FUNCTION fluxo.aplicar_contexto(uuid, uuid, text, text), fluxo.hash_senha_propria(),
                       fluxo.alterar_senha_propria(text), fluxo.registrar_tentativa_bloqueada(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.aplicar_contexto(uuid, uuid, text, text), fluxo.hash_senha_propria(),
                          fluxo.alterar_senha_propria(text), fluxo.registrar_tentativa_bloqueada(uuid) TO ${app_role};
