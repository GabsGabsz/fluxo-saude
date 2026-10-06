-- =============================================================================
-- V12 — Versão de credencial: sessão antiga recusada após troca/redefinição de senha
-- (RF-001, RNF-013, CA-11) — ADR-0002 §7, ADR-0006 §7
-- =============================================================================
-- Até a V11, a revalidação por transação (aplicar_contexto) conferia conta ativa, lotação
-- e papéis, mas NÃO se a senha mudou desde a autenticação. Se a remoção física das sessões
-- falhasse após uma redefinição de senha, a sessão antiga continuaria válida.
--
-- Agora cada conta tem uma VERSÃO DE CREDENCIAL, guardada na sessão no login e conferida
-- pelo banco em TODA transação. Ela muda quando muda algo que deve derrubar as sessões
-- existentes: senha (própria ou provisória), situação (desativar/reativar) e papéis
-- (alteração administrativa). Sessão com versão diferente = contexto recusado (401),
-- independentemente de a sessão ter sido apagada.
--
-- Login concorrente com redefinição: a versão é lida JUNTO com o hash usado na verificação e
-- a primeira transação com o usuário no contexto (leitura do perfil, ainda no login) já a
-- confere. Se a senha foi redefinida durante a verificação, o login é recusado; se a
-- redefinição ocorrer depois, a sessão nasce com a versão antiga e é recusada na requisição
-- seguinte.
--
-- Troca da própria senha: condicionada à versão da sessão (sem "lost update" entre duas
-- trocas simultâneas, ou entre a troca e uma redefinição pelo administrador).

ALTER TABLE fluxo.usuario ADD COLUMN credencial_versao integer NOT NULL DEFAULT 1 CHECK (credencial_versao >= 1);
GRANT SELECT (credencial_versao) ON fluxo.usuario TO ${app_role};

-- Qualquer mudança de senha ou situação incrementa a versão de credencial — inclusive
-- feita pelo DBA ou por função futura que esqueça de fazê-lo (não depende de quem altera).
CREATE FUNCTION fluxo.tg_usuario_credencial() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.senha_hash IS DISTINCT FROM OLD.senha_hash OR NEW.ativo IS DISTINCT FROM OLD.ativo THEN
        NEW.credencial_versao := greatest(NEW.credencial_versao, OLD.credencial_versao + 1);
    ELSIF NEW.credencial_versao < OLD.credencial_versao THEN
        NEW.credencial_versao := OLD.credencial_versao;   -- nunca regride (sessões antigas não "voltam")
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER usuario_credencial BEFORE UPDATE ON fluxo.usuario
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_usuario_credencial();

-- -----------------------------------------------------------------------------
-- Contexto validado: agora também confere a versão de credencial da sessão.
-- A versão anterior (4 parâmetros) é removida: não há caminho que ignore a conferência.
-- -----------------------------------------------------------------------------
DROP FUNCTION fluxo.aplicar_contexto(uuid, uuid, text, text);

CREATE FUNCTION fluxo.aplicar_contexto(p_usuario_id uuid, p_unidade_id uuid, p_ip text, p_correlacao text,
                                       p_credencial_versao integer)
    RETURNS text[]
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_papeis text[];
BEGIN
    IF p_usuario_id IS NULL OR p_credencial_versao IS NULL
       OR NOT EXISTS (SELECT 1 FROM fluxo.usuario u
                       WHERE u.id = p_usuario_id AND u.ativo AND u.credencial_versao = p_credencial_versao) THEN
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

-- Login: a versão de credencial é devolvida junto com o hash (mesma leitura).
DROP FUNCTION fluxo.credencial_para_login(text);
CREATE FUNCTION fluxo.credencial_para_login(p_login text)
    RETURNS TABLE (usuario_id uuid, senha_hash text, ativo boolean, deve_trocar_senha boolean,
                   bloqueado_ate timestamptz, credencial_versao integer)
    LANGUAGE sql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT u.id, u.senha_hash, u.ativo, u.deve_trocar_senha, a.bloqueado_ate, u.credencial_versao
      FROM fluxo.usuario u
      LEFT JOIN fluxo.usuario_acesso a ON a.usuario_id = u.id
     WHERE u.login OPERATOR(public.=) p_login::public.citext
$$;

-- Troca da própria senha condicionada à versão de credencial da sessão: a condição é
-- reavaliada pelo PostgreSQL após o bloqueio da linha, então se outra troca/redefinição foi
-- confirmada antes, nada é gravado (NULL = sessão desatualizada). Devolve a nova versão.
DROP FUNCTION fluxo.alterar_senha_propria(text);
CREATE FUNCTION fluxo.alterar_senha_propria(p_hash text, p_credencial_versao integer) RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_usuario uuid := fluxo.ctx_usuario();
    v_nova    integer;
BEGIN
    IF v_usuario IS NULL THEN
        RAISE EXCEPTION 'contexto de usuário ausente' USING ERRCODE = '42501';
    END IF;
    UPDATE fluxo.usuario
       SET senha_hash = p_hash, deve_trocar_senha = false, senha_alterada_em = clock_timestamp(),
           versao = versao + 1
     WHERE id = v_usuario AND ativo AND credencial_versao = p_credencial_versao
    RETURNING credencial_versao INTO v_nova;
    RETURN v_nova;
END $$;

-- Lotação alterada DIRETAMENTE pelo DBA (remoção ou troca de papel) também derruba as
-- sessões: sem isso, remover e recolocar o mesmo papel "ressuscitaria" sessões antigas.
-- Pela aplicação, admin_definir_papeis já incrementa a versão (abaixo). Concessão nova não
-- precisa: a sessão antiga não tem o papel novo e a comparação de papéis já a recusa.
CREATE FUNCTION fluxo.tg_lotacao_credencial() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF fluxo.sessao_do_dba() THEN
        UPDATE fluxo.usuario
           SET credencial_versao = credencial_versao + 1, versao = versao + 1
         WHERE id = OLD.usuario_id OR id = CASE WHEN TG_OP = 'UPDATE' THEN NEW.usuario_id END;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER lotacao_credencial AFTER UPDATE OR DELETE ON fluxo.lotacao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_lotacao_credencial();

-- Papéis alterados pela administração também derrubam as sessões existentes (mesmo que,
-- depois, o mesmo conjunto de papéis seja restaurado). Corpo idêntico ao da V11, exceto a
-- versão de credencial no UPDATE.
CREATE OR REPLACE FUNCTION fluxo.admin_definir_papeis(p_usuario_id uuid, p_versao integer, p_papeis fluxo.papel[])
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_unidade uuid := fluxo.admin_travar(NULL);
    v_alvo    fluxo.usuario := fluxo.admin_carregar_alvo(p_usuario_id, p_versao);
    v_antes   text[];
    v_depois  text[];
    v_orfao   boolean := false;
BEGIN
    SELECT coalesce(array_agg(DISTINCT p::text ORDER BY p::text), '{}') INTO v_depois
      FROM unnest(coalesce(p_papeis, '{}')) AS t(p) WHERE p IS NOT NULL;
    SELECT coalesce(array_agg(l.papel::text ORDER BY l.papel::text), '{}') INTO v_antes
      FROM fluxo.lotacao l WHERE l.usuario_id = p_usuario_id AND l.unidade_id = v_unidade;
    IF v_antes = v_depois THEN
        RETURN v_alvo.versao;
    END IF;
    IF cardinality(v_antes) = 0 AND NOT (
           v_alvo.ativo AND EXISTS (SELECT 1 FROM fluxo.lotacao l WHERE l.usuario_id = p_usuario_id)) THEN
        RAISE EXCEPTION 'só é possível vincular conta ativa e lotada em outra unidade' USING ERRCODE = 'FX403';
    END IF;

    DELETE FROM fluxo.lotacao l
     WHERE l.usuario_id = p_usuario_id AND l.unidade_id = v_unidade AND NOT (l.papel::text = ANY (v_depois));
    INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel)
    SELECT p_usuario_id, v_unidade, p::fluxo.papel FROM unnest(v_depois) AS t(p)
    ON CONFLICT DO NOTHING;
    v_orfao := v_alvo.ativo AND NOT EXISTS (SELECT 1 FROM fluxo.lotacao l WHERE l.usuario_id = p_usuario_id);
    UPDATE fluxo.usuario
       SET versao = versao + 1,
           credencial_versao = credencial_versao + 1,
           ativo = ativo AND NOT v_orfao,
           unidade_gestora_id = CASE WHEN cardinality(v_depois) = 0 AND unidade_gestora_id = v_unidade
                                     THEN NULL ELSE unidade_gestora_id END
     WHERE id = p_usuario_id;

    PERFORM auditoria.registrar(
        CASE WHEN cardinality(v_depois) = 0 THEN 'ACESSO_REVOGADO'
             WHEN cardinality(v_antes) = 0 THEN 'ACESSO_CONCEDIDO'
             ELSE 'PAPEIS_ALTERADOS' END,
        'fluxo.usuario', p_usuario_id::text,
        jsonb_build_object('antes', to_jsonb(v_antes), 'depois', to_jsonb(v_depois)), v_unidade);
    IF v_orfao THEN
        PERFORM auditoria.registrar('CONTA_DESATIVADA', 'fluxo.usuario', p_usuario_id::text,
                                    '{"motivo": "sem_lotacao"}'::jsonb, v_unidade);
    END IF;
    RETURN v_alvo.versao + 1;
END $$;

REVOKE ALL ON FUNCTION fluxo.aplicar_contexto(uuid, uuid, text, text, integer), fluxo.credencial_para_login(text),
                       fluxo.alterar_senha_propria(text, integer), fluxo.tg_usuario_credencial(),
                       fluxo.tg_lotacao_credencial() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.aplicar_contexto(uuid, uuid, text, text, integer), fluxo.credencial_para_login(text),
                          fluxo.alterar_senha_propria(text, integer) TO ${app_role};
