-- =============================================================================
-- V11 — Gestão de usuários e lotações (M08, RF-001, RNF-001, RNF-013, CA-11)
-- Ver docs/adr/0006-gestao-de-usuarios.md
-- =============================================================================
-- Até a V10 o papel da aplicação podia inserir/excluir lotações e alterar cadastros de
-- usuários da unidade em QUALQUER contexto: a autorização administrativa existia só no
-- código Java. A partir daqui toda escrita em usuário/lotação pela aplicação passa por
-- funções SECURITY DEFINER que conferem o alcance no próprio banco:
--
--   * lotação (papéis em UMA unidade): exige ADMINISTRADOR ativo da unidade ativa;
--   * conta (nome, e-mail, registro, situação, senha): é GLOBAL — exige administrar a
--     UNIDADE GESTORA da conta (a que a criou) e TODAS as unidades em que ela está lotada.
--     O administrador da unidade A revoga o acesso na A, mas não desativa nem toma a conta
--     de quem também atua na B; e a B, ao vincular alguém da A, não passa a gerir a conta;
--   * ninguém altera os próprios papéis nem a própria conta por aqui;
--   * vincular conta existente à unidade só se ela estiver ativa e lotada em outra unidade
--     (contas órfãs não são "adotáveis"); remover a última lotação desativa a conta;
--   * toda unidade mantém ao menos um ADMINISTRADOR ativo, inclusive sob concorrência;
--   * usuário criado nasce ativo e obrigado a trocar a senha.
--
-- Ordem de bloqueio (evita deadlock): linhas de fluxo.unidade em ordem de id, depois a
-- linha do usuário-alvo com FOR NO KEY UPDATE (não conflita com as chaves estrangeiras).
-- O DBA (membro do papel dono) continua podendo agir diretamente (implantação/recuperação).

-- -----------------------------------------------------------------------------
-- 1. Mínimo privilégio: sem DML direto da aplicação em usuário e lotação.
-- -----------------------------------------------------------------------------
REVOKE INSERT, UPDATE ON fluxo.usuario FROM ${app_role};
REVOKE INSERT, DELETE ON fluxo.lotacao FROM ${app_role};

-- Unidade gestora: responsável pela identidade da conta (dados, situação, senha). Contas
-- existentes lotadas numa única unidade recebem essa unidade; as demais ficam sem gestora
-- (operações de conta só pelo DBA até ele definir). Não é visível para a aplicação.
ALTER TABLE fluxo.usuario ADD COLUMN unidade_gestora_id uuid REFERENCES fluxo.unidade (id);
UPDATE fluxo.usuario u
   SET unidade_gestora_id = l.unidade_id, versao = u.versao + 1
  FROM (SELECT usuario_id, min(unidade_id::text)::uuid AS unidade_id
          FROM fluxo.lotacao GROUP BY usuario_id HAVING count(DISTINCT unidade_id) = 1) l
 WHERE l.usuario_id = u.id;

-- -----------------------------------------------------------------------------
-- 2. Funções de alcance (devolvem só booleanos; nada para quem não administra o alvo).
-- -----------------------------------------------------------------------------

-- Sessão do DBA (membro do dono das tabelas, ou superusuário). Qualquer outro login é
-- tratado como aplicação: um papel novo/renomeado NÃO desliga as regras (falha fechada).
CREATE FUNCTION fluxo.sessao_do_dba() RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT pg_has_role(session_user, (SELECT c.relowner FROM pg_class c WHERE c.oid = 'fluxo.usuario'::regclass),
                       'MEMBER')
$$;

-- O usuário do contexto é ADMINISTRADOR, ativo, na unidade (que precisa estar no contexto).
CREATE FUNCTION fluxo.ctx_administra(p_unidade_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_unidade_id IS NOT NULL
       AND fluxo.ctx_usuario() IS NOT NULL
       AND p_unidade_id = ANY (fluxo.ctx_unidades())
       AND EXISTS (SELECT 1
                     FROM fluxo.lotacao l
                     JOIN fluxo.usuario u ON u.id = l.usuario_id AND u.ativo
                    WHERE l.usuario_id = fluxo.ctx_usuario()
                      AND l.unidade_id = p_unidade_id
                      AND l.papel = 'ADMINISTRADOR')
$$;

CREATE FUNCTION fluxo.ctx_administra_alguma() RETURNS boolean
    LANGUAGE sql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM unnest(fluxo.ctx_unidades()) AS u(id) WHERE fluxo.ctx_administra(u.id)) $$;

-- Alcance sobre a CONTA (operação global): o alvo não é o próprio usuário, a unidade ATIVA
-- é a sua unidade gestora (administrada pelo usuário do contexto) e TODAS as suas lotações
-- estão em unidades que o usuário do contexto administra (lotação real, verificada aqui).
CREATE FUNCTION fluxo.pode_administrar_conta(p_alvo uuid) RETURNS boolean
    LANGUAGE sql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_alvo IS NOT NULL
       AND p_alvo IS DISTINCT FROM fluxo.ctx_usuario()
       AND EXISTS (SELECT 1 FROM fluxo.usuario g
                    WHERE g.id = p_alvo AND fluxo.ctx_administra(g.unidade_gestora_id))
       AND EXISTS (SELECT 1 FROM fluxo.lotacao l
                    WHERE l.usuario_id = p_alvo AND fluxo.ctx_administra(l.unidade_id))
       AND NOT EXISTS (
            SELECT 1 FROM fluxo.lotacao l
             WHERE l.usuario_id = p_alvo
               AND NOT EXISTS (SELECT 1
                                 FROM fluxo.lotacao a
                                 JOIN fluxo.usuario au ON au.id = a.usuario_id AND au.ativo
                                WHERE a.usuario_id = fluxo.ctx_usuario()
                                  AND a.unidade_id = l.unidade_id
                                  AND a.papel = 'ADMINISTRADOR'))
$$;

-- O alvo (lotado numa unidade administrada no contexto) tem lotação em OUTRA unidade?
-- NULL quando o chamador não administra uma unidade do alvo (não revela nada).
CREATE FUNCTION fluxo.possui_outras_unidades(p_alvo uuid) RETURNS boolean
    LANGUAGE sql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE
             WHEN EXISTS (SELECT 1 FROM fluxo.lotacao l
                           WHERE l.usuario_id = p_alvo AND fluxo.ctx_administra(l.unidade_id))
             THEN EXISTS (SELECT 1 FROM fluxo.lotacao l
                           WHERE l.usuario_id = p_alvo AND NOT (l.unidade_id = ANY (fluxo.ctx_unidades())))
           END
$$;

-- -----------------------------------------------------------------------------
-- 3. Políticas RESTRITIVAS: sem efeito enquanto não houver GRANT de DML (seção 1), mas
--    garantem as mesmas regras se um GRANT for reintroduzido por engano.
-- -----------------------------------------------------------------------------
CREATE POLICY lotacao_admin_insercao ON fluxo.lotacao AS RESTRICTIVE FOR INSERT TO ${app_role}
    WITH CHECK (fluxo.ctx_administra(unidade_id) AND usuario_id IS DISTINCT FROM (SELECT fluxo.ctx_usuario()));
CREATE POLICY lotacao_admin_exclusao ON fluxo.lotacao AS RESTRICTIVE FOR DELETE TO ${app_role}
    USING (fluxo.ctx_administra(unidade_id) AND usuario_id IS DISTINCT FROM (SELECT fluxo.ctx_usuario()));
CREATE POLICY usuario_admin_criacao ON fluxo.usuario AS RESTRICTIVE FOR INSERT TO ${app_role}
    WITH CHECK ((SELECT fluxo.ctx_administra_alguma()));
CREATE POLICY usuario_admin_alteracao ON fluxo.usuario AS RESTRICTIVE FOR UPDATE TO ${app_role}
    USING (fluxo.pode_administrar_conta(id))
    WITH CHECK (fluxo.pode_administrar_conta(id));

-- Usuário criado fora do DBA: sempre ativo e obrigado a trocar a senha no 1º acesso.
CREATE FUNCTION fluxo.tg_usuario_provisionamento() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NOT fluxo.sessao_do_dba() THEN
        IF NEW.unidade_gestora_id IS NULL THEN
            RAISE EXCEPTION 'conta criada pela aplicação exige unidade gestora' USING ERRCODE = '23502';
        END IF;
        NEW.ativo := true;
        NEW.deve_trocar_senha := true;
        NEW.senha_alterada_em := clock_timestamp();
        NEW.versao := 0;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER usuario_provisionamento BEFORE INSERT ON fluxo.usuario
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_usuario_provisionamento();

-- -----------------------------------------------------------------------------
-- 4. Toda unidade mantém ao menos um ADMINISTRADOR ativo. Bloqueia a linha da unidade
--    antes de contar (READ COMMITTED: a contagem vê o que outra transação confirmou).
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.tg_garantir_administrador() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_unidades uuid[];
    v_unidade  uuid;
BEGIN
    IF fluxo.sessao_do_dba() THEN
        RETURN NULL;   -- implantação/recuperação pelo DBA
    END IF;
    IF TG_TABLE_NAME = 'lotacao' THEN
        IF OLD.papel <> 'ADMINISTRADOR' THEN
            RETURN NULL;
        END IF;
        v_unidades := ARRAY[OLD.unidade_id];
    ELSE
        IF NOT (OLD.ativo AND NOT NEW.ativo) THEN
            RETURN NULL;
        END IF;
        SELECT array_agg(l.unidade_id) INTO v_unidades
          FROM fluxo.lotacao l WHERE l.usuario_id = OLD.id AND l.papel = 'ADMINISTRADOR';
        IF v_unidades IS NULL THEN
            RETURN NULL;
        END IF;
    END IF;
    PERFORM 1 FROM fluxo.unidade un WHERE un.id = ANY (v_unidades) ORDER BY un.id FOR NO KEY UPDATE;
    FOREACH v_unidade IN ARRAY v_unidades LOOP
        IF NOT EXISTS (SELECT 1
                         FROM fluxo.lotacao l
                         JOIN fluxo.usuario u ON u.id = l.usuario_id AND u.ativo
                        WHERE l.unidade_id = v_unidade AND l.papel = 'ADMINISTRADOR') THEN
            RAISE EXCEPTION 'a unidade ficaria sem administrador ativo' USING ERRCODE = '55000';
        END IF;
    END LOOP;
    RETURN NULL;
END $$;
CREATE TRIGGER lotacao_garantir_administrador AFTER DELETE ON fluxo.lotacao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_garantir_administrador();
CREATE TRIGGER usuario_garantir_administrador AFTER UPDATE OF ativo ON fluxo.usuario
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_garantir_administrador();

-- -----------------------------------------------------------------------------
-- 5. Operações administrativas.
--    Erros: FX403 = recusa administrativa (fora do alcance, autoalteração, sem permissão —
--    código próprio, para não se confundir com 42501 de GRANT ausente); 40001 = versão
--    desatualizada; P0002 = usuário inexistente; 23505 = login/e-mail em uso;
--    55000 = unidade ficaria sem administrador. Nada de senha ou hash na auditoria.
-- -----------------------------------------------------------------------------

-- Bloqueia (em ordem de id) a unidade ativa e, se informado, as unidades do usuário-alvo;
-- DEPOIS confere — com os bloqueios já obtidos — que o usuário do contexto ainda é
-- administrador ativo da unidade ativa. Devolve a unidade ativa.
CREATE FUNCTION fluxo.admin_travar(p_alvo uuid) RETURNS uuid
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_ctx uuid[] := fluxo.ctx_unidades();
BEGIN
    IF fluxo.ctx_usuario() IS NULL OR cardinality(v_ctx) <> 1 THEN
        RAISE EXCEPTION 'operação exige administrador da unidade ativa' USING ERRCODE = 'FX403';
    END IF;
    PERFORM 1 FROM fluxo.unidade un
     WHERE un.id = v_ctx[1]
        OR (p_alvo IS NOT NULL AND un.id IN (SELECT l.unidade_id FROM fluxo.lotacao l WHERE l.usuario_id = p_alvo))
     ORDER BY un.id
       FOR NO KEY UPDATE;
    IF NOT fluxo.ctx_administra(v_ctx[1]) THEN
        RAISE EXCEPTION 'operação exige administrador da unidade ativa' USING ERRCODE = 'FX403';
    END IF;
    RETURN v_ctx[1];
END $$;

-- Carrega o alvo com bloqueio e confere a versão lida pelo cliente.
CREATE FUNCTION fluxo.admin_carregar_alvo(p_usuario_id uuid, p_versao integer) RETURNS fluxo.usuario
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v fluxo.usuario;
BEGIN
    IF p_usuario_id IS NOT DISTINCT FROM fluxo.ctx_usuario() THEN
        RAISE EXCEPTION 'autoalteração não é permitida' USING ERRCODE = 'FX403';
    END IF;
    SELECT * INTO v FROM fluxo.usuario u WHERE u.id = p_usuario_id FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'usuário inexistente' USING ERRCODE = 'P0002';
    END IF;
    IF v.versao IS DISTINCT FROM p_versao THEN
        RAISE EXCEPTION 'versão desatualizada' USING ERRCODE = '40001';
    END IF;
    RETURN v;
END $$;

-- Cria a conta e a lota na unidade ativa (senha provisória já em hash).
CREATE FUNCTION fluxo.admin_criar_usuario(p_id uuid, p_login text, p_nome text, p_email text, p_registro text,
                                          p_hash text, p_papeis fluxo.papel[])
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_unidade uuid := fluxo.admin_travar(NULL);
    v_papeis  text[];
BEGIN
    SELECT coalesce(array_agg(DISTINCT p::text ORDER BY p::text), '{}') INTO v_papeis
      FROM unnest(coalesce(p_papeis, '{}')) AS t(p) WHERE p IS NOT NULL;
    IF cardinality(v_papeis) = 0 THEN
        RAISE EXCEPTION 'informe ao menos um papel' USING ERRCODE = '22023';
    END IF;
    INSERT INTO fluxo.usuario (id, login, nome, email, registro_profissional, senha_hash, unidade_gestora_id)
    VALUES (p_id, lower(btrim(p_login)), p_nome, p_email, p_registro, p_hash, v_unidade);
    INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel)
    SELECT p_id, v_unidade, p::fluxo.papel FROM unnest(v_papeis) AS t(p);
    PERFORM auditoria.registrar('USUARIO_CRIADO', 'fluxo.usuario', p_id::text,
                                jsonb_build_object('papeis', to_jsonb(v_papeis)), v_unidade);
    RETURN 0;
END $$;

-- Define o CONJUNTO de papéis do usuário na unidade ativa (vazio = revoga o acesso nela).
-- Também vincula conta existente — só se estiver ATIVA e lotada em outra unidade. Se a
-- última lotação da conta for removida, a conta é desativada. Devolve a nova versão.
CREATE FUNCTION fluxo.admin_definir_papeis(p_usuario_id uuid, p_versao integer, p_papeis fluxo.papel[])
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
    -- Revogar o acesso na unidade gestora encerra a gestão da conta por ela: nenhuma outra
    -- unidade "herda" a identidade (operações de conta passam a exigir o DBA).
    UPDATE fluxo.usuario
       SET versao = versao + 1,
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

-- Dados cadastrais (globais): exige alcance sobre a conta.
CREATE FUNCTION fluxo.admin_alterar_conta(p_usuario_id uuid, p_versao integer, p_nome text, p_email text,
                                          p_registro text)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_unidade uuid := fluxo.admin_travar(p_usuario_id);
    v_alvo    fluxo.usuario := fluxo.admin_carregar_alvo(p_usuario_id, p_versao);
BEGIN
    IF NOT fluxo.pode_administrar_conta(p_usuario_id) THEN
        RAISE EXCEPTION 'conta fora do alcance do administrador' USING ERRCODE = 'FX403';
    END IF;
    IF v_alvo.nome = p_nome AND v_alvo.email::text IS NOT DISTINCT FROM p_email
       AND v_alvo.registro_profissional IS NOT DISTINCT FROM p_registro THEN
        RETURN v_alvo.versao;
    END IF;
    UPDATE fluxo.usuario
       SET nome = p_nome, email = p_email, registro_profissional = p_registro, versao = versao + 1
     WHERE id = p_usuario_id;
    PERFORM auditoria.registrar('CONTA_ALTERADA', 'fluxo.usuario', p_usuario_id::text, '{}'::jsonb, v_unidade);
    RETURN v_alvo.versao + 1;
END $$;

-- Ativa/desativa a conta em TODAS as unidades: exige alcance sobre a conta.
CREATE FUNCTION fluxo.admin_definir_situacao(p_usuario_id uuid, p_versao integer, p_ativo boolean)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_unidade uuid := fluxo.admin_travar(p_usuario_id);
    v_alvo    fluxo.usuario := fluxo.admin_carregar_alvo(p_usuario_id, p_versao);
BEGIN
    IF NOT fluxo.pode_administrar_conta(p_usuario_id) THEN
        RAISE EXCEPTION 'conta fora do alcance do administrador' USING ERRCODE = 'FX403';
    END IF;
    IF p_ativo IS NULL OR v_alvo.ativo = p_ativo THEN
        RETURN v_alvo.versao;
    END IF;
    UPDATE fluxo.usuario SET ativo = p_ativo, versao = versao + 1 WHERE id = p_usuario_id;
    PERFORM auditoria.registrar(CASE WHEN p_ativo THEN 'CONTA_REATIVADA' ELSE 'CONTA_DESATIVADA' END,
                                'fluxo.usuario', p_usuario_id::text, '{}'::jsonb, v_unidade);
    RETURN v_alvo.versao + 1;
END $$;

-- Senha provisória (redefinição de acesso): exige alcance sobre a conta; obriga a troca
-- no próximo acesso e zera o bloqueio por tentativas.
CREATE FUNCTION fluxo.admin_definir_senha_provisoria(p_usuario_id uuid, p_versao integer, p_hash text)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_unidade uuid := fluxo.admin_travar(p_usuario_id);
    v_alvo    fluxo.usuario := fluxo.admin_carregar_alvo(p_usuario_id, p_versao);
BEGIN
    IF NOT fluxo.pode_administrar_conta(p_usuario_id) THEN
        RAISE EXCEPTION 'conta fora do alcance do administrador' USING ERRCODE = 'FX403';
    END IF;
    -- usuario_acesso ANTES de usuario (cuja auditoria bloqueia a cabeça da cadeia): mesma
    -- ordem do registro de tentativa de login, sem risco de deadlock entre os dois.
    UPDATE fluxo.usuario_acesso SET falhas_consecutivas = 0, bloqueado_ate = NULL WHERE usuario_id = p_usuario_id;
    UPDATE fluxo.usuario
       SET senha_hash = p_hash, deve_trocar_senha = true, senha_alterada_em = clock_timestamp(),
           versao = versao + 1
     WHERE id = p_usuario_id;
    PERFORM auditoria.registrar('SENHA_PROVISORIA_DEFINIDA', 'fluxo.usuario', p_usuario_id::text, '{}'::jsonb,
                                v_unidade);
    RETURN v_alvo.versao + 1;
END $$;

-- Localiza conta existente pelo login exato, para vincular à unidade. Devolve o mínimo
-- (sem nome, e-mail ou registro) e audita a consulta (sem gravar o login digitado).
CREATE FUNCTION fluxo.admin_localizar_por_login(p_login text)
    RETURNS TABLE (usuario_id uuid, versao integer, lotado_na_unidade boolean, vinculavel boolean)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_unidade uuid := fluxo.admin_travar(NULL);
    v_id      uuid;
BEGIN
    SELECT u.id INTO v_id FROM fluxo.usuario u
     WHERE u.login OPERATOR(public.=) lower(btrim(coalesce(p_login, '')))::public.citext;
    PERFORM auditoria.registrar('USUARIO_LOCALIZADO', 'fluxo.usuario', v_id::text,
                                jsonb_build_object('encontrado', v_id IS NOT NULL), v_unidade);
    RETURN QUERY
    SELECT u.id, u.versao,
           EXISTS (SELECT 1 FROM fluxo.lotacao l WHERE l.usuario_id = u.id AND l.unidade_id = v_unidade),
           u.ativo AND u.id IS DISTINCT FROM fluxo.ctx_usuario()
               AND EXISTS (SELECT 1 FROM fluxo.lotacao l WHERE l.usuario_id = u.id)
      FROM fluxo.usuario u WHERE u.id = v_id;
END $$;

REVOKE ALL ON FUNCTION fluxo.sessao_do_dba(), fluxo.ctx_administra(uuid), fluxo.ctx_administra_alguma(),
                       fluxo.pode_administrar_conta(uuid), fluxo.possui_outras_unidades(uuid),
                       fluxo.admin_travar(uuid), fluxo.admin_carregar_alvo(uuid, integer),
                       fluxo.admin_criar_usuario(uuid, text, text, text, text, text, fluxo.papel[]),
                       fluxo.admin_definir_papeis(uuid, integer, fluxo.papel[]),
                       fluxo.admin_alterar_conta(uuid, integer, text, text, text),
                       fluxo.admin_definir_situacao(uuid, integer, boolean),
                       fluxo.admin_definir_senha_provisoria(uuid, integer, text),
                       fluxo.admin_localizar_por_login(text),
                       fluxo.tg_garantir_administrador(), fluxo.tg_usuario_provisionamento() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.sessao_do_dba(), fluxo.ctx_administra(uuid), fluxo.ctx_administra_alguma(),
                          fluxo.pode_administrar_conta(uuid), fluxo.possui_outras_unidades(uuid),
                          fluxo.admin_criar_usuario(uuid, text, text, text, text, text, fluxo.papel[]),
                          fluxo.admin_definir_papeis(uuid, integer, fluxo.papel[]),
                          fluxo.admin_alterar_conta(uuid, integer, text, text, text),
                          fluxo.admin_definir_situacao(uuid, integer, boolean),
                          fluxo.admin_definir_senha_provisoria(uuid, integer, text),
                          fluxo.admin_localizar_por_login(text) TO ${app_role};
