-- =============================================================================
-- V6 — Isolamento por unidade (RLS), privilégios mínimos, autenticação controlada
-- e ligação da auditoria (RNF-001, RNF-003, CA-10, CA-11)
-- Ver docs/adr/0002, 0003 e 0004.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Privilégios mínimos do papel da aplicação. Sem DELETE em dados de negócio.
--    usuario_acesso (tentativas de login/bloqueio) só é acessível por funções.
-- -----------------------------------------------------------------------------
REVOKE ALL ON ALL TABLES IN SCHEMA fluxo FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA fluxo FROM ${app_role};

GRANT SELECT, UPDATE                 ON fluxo.unidade          TO ${app_role};
GRANT SELECT, INSERT, UPDATE         ON fluxo.setor            TO ${app_role};
GRANT SELECT                         ON fluxo.especialidade    TO ${app_role};
GRANT SELECT, INSERT, UPDATE         ON fluxo.usuario          TO ${app_role};
GRANT SELECT, INSERT, DELETE         ON fluxo.lotacao          TO ${app_role};
GRANT SELECT, INSERT, UPDATE         ON fluxo.etapa            TO ${app_role};
GRANT SELECT, INSERT, DELETE         ON fluxo.transicao_etapa  TO ${app_role};
GRANT SELECT, INSERT, UPDATE         ON fluxo.motivo_bloqueio  TO ${app_role};
GRANT SELECT, INSERT, UPDATE         ON fluxo.paciente         TO ${app_role};
GRANT SELECT, INSERT, UPDATE         ON fluxo.episodio         TO ${app_role};
GRANT SELECT, INSERT                 ON fluxo.evento_episodio  TO ${app_role};
GRANT SELECT, INSERT, UPDATE         ON fluxo.pendencia        TO ${app_role};

-- -----------------------------------------------------------------------------
-- 2. Row Level Security por unidade. Sem contexto => nenhuma linha (falha fechada).
--    O papel da aplicação não é dono das tabelas, então RLS sempre se aplica a ele.
-- -----------------------------------------------------------------------------
ALTER TABLE fluxo.unidade ENABLE ROW LEVEL SECURITY;
CREATE POLICY unidade_isolamento ON fluxo.unidade TO ${app_role}
    USING (id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]))
    WITH CHECK (id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['setor', 'lotacao', 'etapa', 'transicao_etapa', 'motivo_bloqueio',
                             'paciente', 'episodio', 'evento_episodio', 'pendencia'] LOOP
        EXECUTE format('ALTER TABLE fluxo.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY unidade_isolamento ON fluxo.%I TO ${app_role} '
            'USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[])) '
            'WITH CHECK (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]))', t);
    END LOOP;
END $$;

-- Usuários: visíveis/alteráveis apenas o próprio e os lotados nas unidades do
-- contexto (a subconsulta em lotacao já é filtrada pelo RLS de lotacao).
ALTER TABLE fluxo.usuario ENABLE ROW LEVEL SECURITY;
CREATE POLICY usuario_leitura ON fluxo.usuario FOR SELECT TO ${app_role}
    USING (id = (SELECT fluxo.ctx_usuario())
           OR EXISTS (SELECT 1 FROM fluxo.lotacao l WHERE l.usuario_id = usuario.id));
CREATE POLICY usuario_alteracao ON fluxo.usuario FOR UPDATE TO ${app_role}
    USING (id = (SELECT fluxo.ctx_usuario())
           OR EXISTS (SELECT 1 FROM fluxo.lotacao l WHERE l.usuario_id = usuario.id));
CREATE POLICY usuario_criacao ON fluxo.usuario FOR INSERT TO ${app_role}
    WITH CHECK ((SELECT fluxo.ctx_usuario()) IS NOT NULL);

-- -----------------------------------------------------------------------------
-- 3. Autenticação (antes de existir contexto). Funções SECURITY DEFINER estreitas.
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.credencial_para_login(p_login text)
    RETURNS TABLE (usuario_id uuid, senha_hash text, ativo boolean, deve_trocar_senha boolean,
                   bloqueado_ate timestamptz)
    LANGUAGE sql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT u.id, u.senha_hash, u.ativo, u.deve_trocar_senha, a.bloqueado_ate
      FROM fluxo.usuario u
      LEFT JOIN fluxo.usuario_acesso a ON a.usuario_id = u.id
     WHERE u.login OPERATOR(public.=) p_login::public.citext
$$;

CREATE FUNCTION fluxo.lotacoes_para_autenticacao(p_usuario_id uuid)
    RETURNS TABLE (unidade_id uuid, papel fluxo.papel)
    LANGUAGE plpgsql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF fluxo.ctx_usuario() IS NOT NULL AND fluxo.ctx_usuario() <> p_usuario_id THEN
        RAISE EXCEPTION 'consulta de lotações de outro usuário' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT l.unidade_id, l.papel
          FROM fluxo.lotacao l
          JOIN fluxo.unidade u ON u.id = l.unidade_id AND u.ativa
         WHERE l.usuario_id = p_usuario_id;
END $$;

-- Registra o resultado de uma tentativa de login, aplica bloqueio progressivo e
-- grava o evento de auditoria. Devolve o instante até o qual a conta fica bloqueada.
-- p_usuario_id NULL = login inexistente (o login digitado NÃO é gravado: pode ser
-- uma senha digitada no campo errado).
CREATE FUNCTION fluxo.registrar_tentativa_login(
    p_usuario_id uuid,
    p_sucesso    boolean,
    p_max_falhas integer,
    p_bloqueio   interval
) RETURNS timestamptz
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_falhas    integer;
    v_bloqueio  timestamptz;
    v_agora     timestamptz := clock_timestamp();
BEGIN
    IF fluxo.ctx_usuario() IS NOT NULL THEN
        RAISE EXCEPTION 'tentativa de login não pode ocorrer com usuário já no contexto' USING ERRCODE = '42501';
    END IF;
    IF p_max_falhas NOT BETWEEN 3 AND 20 OR p_bloqueio NOT BETWEEN interval '1 minute' AND interval '24 hours' THEN
        RAISE EXCEPTION 'parâmetros de bloqueio fora dos limites' USING ERRCODE = '22023';
    END IF;

    IF p_usuario_id IS NULL THEN
        INSERT INTO auditoria.registro (origem, usuario_id, origem_ip, correlacao_id, acao, recurso, dados)
        VALUES ('APLICACAO', NULL, fluxo.ctx_ip(), fluxo.ctx_correlacao(), 'LOGIN_FALHA', 'autenticacao',
                '{"motivo": "usuario_inexistente"}');
        RETURN NULL;
    END IF;

    INSERT INTO fluxo.usuario_acesso (usuario_id) VALUES (p_usuario_id) ON CONFLICT (usuario_id) DO NOTHING;

    IF p_sucesso THEN
        UPDATE fluxo.usuario_acesso
           SET falhas_consecutivas = 0, bloqueado_ate = NULL, ultimo_login_em = v_agora
         WHERE usuario_id = p_usuario_id;
        INSERT INTO auditoria.registro (origem, usuario_id, origem_ip, correlacao_id, acao, recurso, recurso_id)
        VALUES ('APLICACAO', p_usuario_id, fluxo.ctx_ip(), fluxo.ctx_correlacao(), 'LOGIN_SUCESSO',
                'autenticacao', p_usuario_id::text);
        RETURN NULL;
    END IF;

    UPDATE fluxo.usuario_acesso
       SET falhas_consecutivas = falhas_consecutivas + 1, ultima_falha_em = v_agora
     WHERE usuario_id = p_usuario_id
    RETURNING falhas_consecutivas INTO v_falhas;

    INSERT INTO auditoria.registro (origem, usuario_id, origem_ip, correlacao_id, acao, recurso, recurso_id, dados)
    VALUES ('APLICACAO', p_usuario_id, fluxo.ctx_ip(), fluxo.ctx_correlacao(), 'LOGIN_FALHA',
            'autenticacao', p_usuario_id::text, jsonb_build_object('falhas_consecutivas', v_falhas));

    IF v_falhas >= p_max_falhas AND v_falhas % p_max_falhas = 0 THEN
        -- Progressivo: 1x, 2x, 4x ... (teto 16x) a cada novo bloco de falhas.
        v_bloqueio := v_agora + p_bloqueio * least(power(2, v_falhas / p_max_falhas - 1), 16);
        UPDATE fluxo.usuario_acesso SET bloqueado_ate = v_bloqueio WHERE usuario_id = p_usuario_id;
        INSERT INTO auditoria.registro (origem, usuario_id, origem_ip, correlacao_id, acao, recurso, recurso_id, dados)
        VALUES ('APLICACAO', p_usuario_id, fluxo.ctx_ip(), fluxo.ctx_correlacao(), 'CONTA_BLOQUEADA',
                'autenticacao', p_usuario_id::text, jsonb_build_object('ate', v_bloqueio));
        RETURN v_bloqueio;
    END IF;
    RETURN (SELECT a.bloqueado_ate FROM fluxo.usuario_acesso a WHERE a.usuario_id = p_usuario_id);
END $$;

-- Desbloqueio administrativo: exige usuário no contexto e lotação compartilhada.
CREATE FUNCTION fluxo.desbloquear_usuario(p_usuario_id uuid) RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_autor uuid := fluxo.ctx_usuario();
BEGIN
    IF v_autor IS NULL THEN
        RAISE EXCEPTION 'contexto de usuário ausente' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fluxo.lotacao l
                    WHERE l.usuario_id = p_usuario_id AND l.unidade_id = ANY (fluxo.ctx_unidades())) THEN
        RAISE EXCEPTION 'usuário fora das unidades do contexto' USING ERRCODE = '42501';
    END IF;
    UPDATE fluxo.usuario_acesso SET falhas_consecutivas = 0, bloqueado_ate = NULL WHERE usuario_id = p_usuario_id;
    INSERT INTO auditoria.registro (origem, usuario_id, origem_ip, correlacao_id, acao, recurso, recurso_id)
    VALUES ('APLICACAO', v_autor, fluxo.ctx_ip(), fluxo.ctx_correlacao(), 'CONTA_DESBLOQUEADA',
            'autenticacao', p_usuario_id::text);
END $$;

GRANT EXECUTE ON FUNCTION fluxo.credencial_para_login(text),
                          fluxo.lotacoes_para_autenticacao(uuid),
                          fluxo.registrar_tentativa_login(uuid, boolean, integer, interval),
                          fluxo.desbloquear_usuario(uuid),
                          fluxo.provisionar_unidade(uuid, boolean),
                          fluxo.cns_valido(text) TO ${app_role};

-- -----------------------------------------------------------------------------
-- 4. Autoria definida pelo banco na concessão de perfis.
-- -----------------------------------------------------------------------------
CREATE TRIGGER lotacao_autoria BEFORE INSERT ON fluxo.lotacao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_autoria('concedido_por', 'concedido_em');

-- -----------------------------------------------------------------------------
-- 5. Captura de auditoria (RN-004). Argumentos = colunas com valor redigido.
--    evento_episodio não é capturado: já é append-only, com autor e horário do banco.
-- -----------------------------------------------------------------------------
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.unidade
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.setor
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.usuario
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar('senha_hash', 'nome', 'email', 'registro_profissional');
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.lotacao
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.etapa
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.transicao_etapa
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.motivo_bloqueio
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.paciente
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar('nome', 'data_nascimento', 'cns', 'identificador_institucional',
                                           'justificativa_reconciliacao');
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.episodio
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar('motivo_detalhe', 'destino_descricao', 'justificativa_encerramento',
                                           'justificativa_duplicidade');
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.pendencia
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar('descricao', 'resolucao');

-- -----------------------------------------------------------------------------
-- 6. Catálogo inicial de especialidades (ajustável pela gestão).
-- -----------------------------------------------------------------------------
INSERT INTO fluxo.especialidade (codigo, nome) VALUES
    ('CLINICA_MEDICA', 'Clínica médica'),
    ('CIRURGIA_GERAL', 'Cirurgia geral'),
    ('ORTOPEDIA', 'Ortopedia e traumatologia'),
    ('CARDIOLOGIA', 'Cardiologia'),
    ('NEUROLOGIA', 'Neurologia'),
    ('NEUROCIRURGIA', 'Neurocirurgia'),
    ('PEDIATRIA', 'Pediatria'),
    ('OBSTETRICIA', 'Obstetrícia'),
    ('PSIQUIATRIA', 'Psiquiatria'),
    ('UTI_ADULTO', 'UTI adulto'),
    ('UTI_PEDIATRICA', 'UTI pediátrica'),
    ('UTI_NEONATAL', 'UTI neonatal');
