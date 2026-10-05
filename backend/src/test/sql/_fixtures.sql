-- Fixtures dos testes SQL (executadas UMA vez, como dono do banco).
-- UUIDs fixos para legibilidade. Senhas são hashes fictícios (não usados aqui).

CREATE SCHEMA teste;
GRANT USAGE ON SCHEMA teste TO fluxo_app;

-- Executa SQL e exige que falhe com mensagem que contenha p_trecho.
CREATE FUNCTION teste.espera_erro(p_sql text, p_trecho text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE p_sql;
    EXCEPTION WHEN OTHERS THEN
        IF position(lower(p_trecho) IN lower(SQLERRM)) = 0 THEN
            RAISE EXCEPTION 'erro inesperado. esperado "%", obtido "%" (SQL: %)', p_trecho, SQLERRM, p_sql;
        END IF;
        RETURN;
    END;
    RAISE EXCEPTION 'era esperado erro "%", mas executou com sucesso: %', p_trecho, p_sql;
END $$;

CREATE FUNCTION teste.afirma(p_cond boolean, p_msg text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    IF p_cond IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'asserção falhou: %', p_msg;
    END IF;
END $$;

-- Define o contexto da transação como a aplicação faz.
CREATE FUNCTION teste.ctx(p_usuario uuid, p_unidades text) RETURNS void
    LANGUAGE sql AS $$
    SELECT set_config('fluxo.usuario_id', coalesce(p_usuario::text, ''), true),
           set_config('fluxo.unidade_ids', coalesce(p_unidades, ''), true),
           set_config('fluxo.origem_ip', '10.0.0.1', true),
           set_config('fluxo.correlacao_id', 'teste', true);
$$;

-- Atalhos para IDs de configuração
CREATE FUNCTION teste.etapa(p_unidade uuid, p_codigo text) RETURNS uuid
    LANGUAGE sql STABLE AS $$ SELECT id FROM fluxo.etapa WHERE unidade_id = p_unidade AND codigo = p_codigo $$;
CREATE FUNCTION teste.motivo(p_unidade uuid, p_codigo text) RETURNS uuid
    LANGUAGE sql STABLE AS $$ SELECT id FROM fluxo.motivo_bloqueio WHERE unidade_id = p_unidade AND codigo = p_codigo $$;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA teste TO fluxo_app;

INSERT INTO fluxo.unidade (id, codigo, nome, tipo) VALUES
    ('00000000-0000-0000-0000-00000000000a', 'UPA_BJ', 'UPA Bom Jesus', 'UPA'),
    ('00000000-0000-0000-0000-00000000000b', 'HOSP_B', 'Hospital B',    'HOSPITAL');

SELECT fluxo.provisionar_unidade('00000000-0000-0000-0000-00000000000a');
SELECT fluxo.provisionar_unidade('00000000-0000-0000-0000-00000000000b');

INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES
    ('00000000-0000-0000-0000-0000000005a1', '00000000-0000-0000-0000-00000000000a', 'OBSERVACAO', 'Observação'),
    ('00000000-0000-0000-0000-0000000005a2', '00000000-0000-0000-0000-00000000000a', 'NIR', 'NIR'),
    ('00000000-0000-0000-0000-0000000005b1', '00000000-0000-0000-0000-00000000000b', 'EMERGENCIA', 'Emergência');

INSERT INTO fluxo.usuario (id, login, nome, senha_hash) VALUES
    ('11111111-1111-1111-1111-000000000001', 'admin.a', 'Admin A',       '{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$ZmljdGljaW8$ZmljdGljaW8'),
    ('11111111-1111-1111-1111-000000000002', 'enf.a',   'Enfermeira A',  '{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$ZmljdGljaW8$ZmljdGljaW8'),
    ('11111111-1111-1111-1111-000000000003', 'coord.b', 'Coordenação B', '{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$ZmljdGljaW8$ZmljdGljaW8');

INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES
    ('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a', 'ADMINISTRADOR'),
    ('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000a', 'ENFERMAGEM'),
    ('11111111-1111-1111-1111-000000000003', '00000000-0000-0000-0000-00000000000b', 'COORDENACAO_FLUXO');

-- Unidade A amplia a retroatividade (parâmetro por unidade, RNF-017/RNF-018);
-- a unidade B mantém o padrão (24 h).
UPDATE fluxo.unidade SET retroatividade_maxima = interval '48 hours', versao = versao + 1
 WHERE id = '00000000-0000-0000-0000-00000000000a';
