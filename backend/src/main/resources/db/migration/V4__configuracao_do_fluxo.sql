-- =============================================================================
-- V4 — Configuração do fluxo por unidade: etapas, transições e motivos de bloqueio
-- (ERS §4.2, §4.3, RF-004, RF-008, RF-027, RN-003)
--
-- Etapas são DADOS (parametrizáveis por unidade), mas com semântica fixa via
-- "natureza" e "desfecho", para que regras como RN-008 (encerramento para os
-- relógios) não dependam de nomes digitados por um administrador.
-- =============================================================================

CREATE TYPE fluxo.natureza_etapa AS ENUM (
    'ATENDIMENTO',   -- paciente em avaliação/tratamento
    'ESPERA',        -- depende de ação futura (exame, decisão, leito, regulação...)
    'ACEITO',        -- destino confirmou aceite
    'TRANSPORTE',    -- apto ao deslocamento, depende de logística
    'DESFECHO'       -- encerra o episódio
);

CREATE TYPE fluxo.tipo_desfecho AS ENUM (
    'ALTA',
    'INTERNACAO',
    'TRANSFERENCIA',
    'OBITO',
    'EVASAO',
    'ENCERRAMENTO_ADMINISTRATIVO'
);

CREATE TYPE fluxo.categoria_bloqueio AS ENUM (
    'ASSISTENCIAL',
    'REGULACAO',
    'LOGISTICA',
    'LEITO_CAPACIDADE',
    'ADMINISTRATIVO',
    'OUTROS',
    'NAO_DEFINIDA'   -- RF-035: causa ainda não definida / em investigação (temporária)
);

CREATE TABLE fluxo.etapa (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    unidade_id               uuid NOT NULL REFERENCES fluxo.unidade (id),
    codigo                   text NOT NULL CHECK (codigo ~ '^[A-Z0-9_]{2,48}$'),
    nome                     text NOT NULL CHECK (length(btrim(nome)) BETWEEN 2 AND 80),
    ordem                    smallint NOT NULL DEFAULT 0,
    natureza                 fluxo.natureza_etapa NOT NULL,
    desfecho                 fluxo.tipo_desfecho,
    inicial                  boolean NOT NULL DEFAULT false,
    exige_motivo_bloqueio    boolean NOT NULL DEFAULT false,   -- RN-003
    exige_protocolo_externo  boolean NOT NULL DEFAULT false,   -- RF-009
    exige_justificativa      boolean NOT NULL DEFAULT false,   -- RF-015 (encerramento justificado)
    ativa                    boolean NOT NULL DEFAULT true,
    criado_em                timestamptz NOT NULL DEFAULT clock_timestamp(),
    atualizado_em            timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao                   integer NOT NULL DEFAULT 0,
    UNIQUE (unidade_id, codigo),
    UNIQUE (unidade_id, id),
    CONSTRAINT etapa_desfecho_coerente CHECK ((natureza = 'DESFECHO') = (desfecho IS NOT NULL)),
    CONSTRAINT etapa_desfecho_sem_bloqueio CHECK (NOT (natureza = 'DESFECHO' AND exige_motivo_bloqueio)),
    CONSTRAINT etapa_inicial_nao_terminal CHECK (NOT (inicial AND natureza = 'DESFECHO'))
);
-- Exatamente uma etapa inicial ativa por unidade (garantida também na aplicação).
CREATE UNIQUE INDEX etapa_inicial_unica ON fluxo.etapa (unidade_id) WHERE inicial AND ativa;

CREATE TABLE fluxo.transicao_etapa (
    unidade_id uuid NOT NULL,
    origem_id  uuid NOT NULL,
    destino_id uuid NOT NULL,
    PRIMARY KEY (unidade_id, origem_id, destino_id),
    FOREIGN KEY (unidade_id, origem_id)  REFERENCES fluxo.etapa (unidade_id, id),
    FOREIGN KEY (unidade_id, destino_id) REFERENCES fluxo.etapa (unidade_id, id),
    CHECK (origem_id <> destino_id)
);

-- Etapas de desfecho são terminais: não podem ter transição de saída.
CREATE FUNCTION fluxo.tg_transicao_valida() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM fluxo.etapa e WHERE e.id = NEW.origem_id AND e.natureza = 'DESFECHO') THEN
        RAISE EXCEPTION 'etapa de desfecho é terminal e não admite transição de saída'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER transicao_valida BEFORE INSERT OR UPDATE ON fluxo.transicao_etapa
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_transicao_valida();

CREATE TABLE fluxo.motivo_bloqueio (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    unidade_id     uuid NOT NULL REFERENCES fluxo.unidade (id),
    categoria      fluxo.categoria_bloqueio NOT NULL,
    codigo         text NOT NULL CHECK (codigo ~ '^[A-Z0-9_]{2,48}$'),
    descricao      text NOT NULL CHECK (length(btrim(descricao)) BETWEEN 2 AND 120),
    exige_detalhe  boolean NOT NULL DEFAULT false,
    ativo          boolean NOT NULL DEFAULT true,
    criado_em      timestamptz NOT NULL DEFAULT clock_timestamp(),
    atualizado_em  timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao         integer NOT NULL DEFAULT 0,
    UNIQUE (unidade_id, codigo),
    UNIQUE (unidade_id, id),
    -- ERS §4.3: "Outros" somente mediante justificativa livre e auditável.
    CONSTRAINT motivo_outros_exige_detalhe CHECK (categoria NOT IN ('OUTROS', 'NAO_DEFINIDA') OR exige_detalhe)
);

-- Semântica de uma etapa é imutável (crie outra e desative a antiga); a etapa
-- inicial não pode ser desativada nem deixar de ser inicial. Evita episódios
-- ativos "dentro" de um desfecho e configuração sem etapa inicial.
CREATE FUNCTION fluxo.tg_etapa_imutavel() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.unidade_id <> OLD.unidade_id OR NEW.codigo <> OLD.codigo OR NEW.natureza <> OLD.natureza
       OR NEW.desfecho IS DISTINCT FROM OLD.desfecho THEN
        RAISE EXCEPTION 'código, natureza e desfecho de uma etapa não podem ser alterados; crie nova etapa'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.inicial AND OLD.ativa AND (NOT NEW.inicial OR NOT NEW.ativa) THEN
        RAISE EXCEPTION 'a etapa inicial não pode ser desativada' USING ERRCODE = '55000';
    END IF;
    IF NEW.inicial AND NOT OLD.inicial THEN
        RAISE EXCEPTION 'troca de etapa inicial não suportada nesta versão' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER etapa_imutavel BEFORE UPDATE ON fluxo.etapa
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_etapa_imutavel();

CREATE FUNCTION fluxo.tg_motivo_imutavel() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.unidade_id <> OLD.unidade_id OR NEW.codigo <> OLD.codigo OR NEW.categoria <> OLD.categoria THEN
        RAISE EXCEPTION 'código e categoria de um motivo não podem ser alterados' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER motivo_imutavel BEFORE UPDATE ON fluxo.motivo_bloqueio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_motivo_imutavel();

CREATE TRIGGER etapa_versao BEFORE UPDATE ON fluxo.etapa
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
CREATE TRIGGER motivo_versao BEFORE UPDATE ON fluxo.motivo_bloqueio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
CREATE TRIGGER etapa_atualizado_em BEFORE UPDATE ON fluxo.etapa
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();
CREATE TRIGGER motivo_atualizado_em BEFORE UPDATE ON fluxo.motivo_bloqueio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();

-- -----------------------------------------------------------------------------
-- Provisionamento da configuração padrão de uma unidade (ERS §4.2 e §4.3).
-- Idempotente para a configuração padrão. A CRIAÇÃO da unidade é operação de
-- plataforma (papel dono / script de operação); o ADMINISTRADOR da unidade pode
-- então invocar esta função para a sua unidade (RLS garante o escopo).
-- Os valores são ponto de partida e DEVEM ser validados com a equipe (ERS §19).
-- -----------------------------------------------------------------------------
-- p_internacao_encerra (RN-017, RF-015, V-07): numa implantação só na UPA, a
-- internação encerra o acompanhamento; onde o destino também é monitorado, a
-- internação é TRANSIÇÃO de cuidado e o episódio continua (etapa não terminal).
CREATE FUNCTION fluxo.provisionar_unidade(p_unidade_id uuid, p_internacao_encerra boolean DEFAULT true)
    RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
DECLARE
    v_nao_terminais text[] := ARRAY['EM_ATENDIMENTO','AGUARDANDO_EXAME_PARECER','AGUARDANDO_DECISAO',
        'AGUARDANDO_SOLICITACAO_TRANSFERENCIA','TRANSFERENCIA_SOLICITADA','AGUARDANDO_RECURSO_LEITO',
        'ACEITO','AGUARDANDO_TRANSPORTE']
        || CASE WHEN p_internacao_encerra THEN '{}'::text[] ELSE ARRAY['INTERNADO'] END;
BEGIN
    INSERT INTO fluxo.etapa (unidade_id, codigo, nome, ordem, natureza, desfecho, inicial,
                             exige_motivo_bloqueio, exige_protocolo_externo, exige_justificativa)
    VALUES
        (p_unidade_id, 'EM_ATENDIMENTO',                       'Em atendimento',                        10, 'ATENDIMENTO', NULL, true,  false, false, false),
        (p_unidade_id, 'AGUARDANDO_EXAME_PARECER',             'Aguardando exame/parecer',              20, 'ESPERA',      NULL, false, true,  false, false),
        (p_unidade_id, 'AGUARDANDO_DECISAO',                   'Aguardando decisão',                    30, 'ESPERA',      NULL, false, true,  false, false),
        (p_unidade_id, 'AGUARDANDO_SOLICITACAO_TRANSFERENCIA', 'Aguardando solicitação de transferência',40, 'ESPERA',     NULL, false, true,  false, false),
        (p_unidade_id, 'TRANSFERENCIA_SOLICITADA',             'Transferência solicitada',              50, 'ESPERA',      NULL, false, true,  true,  false),
        (p_unidade_id, 'AGUARDANDO_RECURSO_LEITO',             'Aguardando recurso/leito',              60, 'ESPERA',      NULL, false, true,  false, false),
        (p_unidade_id, 'ACEITO',                               'Aceito',                                70, 'ACEITO',      NULL, false, false, false, false),
        (p_unidade_id, 'AGUARDANDO_TRANSPORTE',                'Aguardando transporte',                 80, 'TRANSPORTE',  NULL, false, true,  false, false),
        (p_unidade_id, 'TRANSFERIDO',                          'Transferido',                           90, 'DESFECHO', 'TRANSFERENCIA',               false, false, false, false),
        (p_unidade_id, 'INTERNADO',
            CASE WHEN p_internacao_encerra THEN 'Internação (fora do escopo monitorado)' ELSE 'Internado (em acompanhamento)' END,
            91,
            CASE WHEN p_internacao_encerra THEN 'DESFECHO' ELSE 'ATENDIMENTO' END::fluxo.natureza_etapa,
            CASE WHEN p_internacao_encerra THEN 'INTERNACAO' END::fluxo.tipo_desfecho,
            false, false, false, false),
        (p_unidade_id, 'ALTA',                                 'Alta',                                  92, 'DESFECHO', 'ALTA',                        false, false, false, false),
        (p_unidade_id, 'OBITO',                                'Óbito',                                 93, 'DESFECHO', 'OBITO',                       false, false, false, false),
        (p_unidade_id, 'EVASAO',                               'Evasão',                                94, 'DESFECHO', 'EVASAO',                      false, false, false, true),
        (p_unidade_id, 'CANCELADO_ENCERRADO',                  'Cancelado/encerrado',                   95, 'DESFECHO', 'ENCERRAMENTO_ADMINISTRATIVO', false, false, false, true)
    ON CONFLICT (unidade_id, codigo) DO NOTHING;

    -- Transições específicas do fluxo
    INSERT INTO fluxo.transicao_etapa (unidade_id, origem_id, destino_id)
    SELECT p_unidade_id, o.id, d.id
      FROM (VALUES
            ('EM_ATENDIMENTO', 'AGUARDANDO_EXAME_PARECER'),
            ('EM_ATENDIMENTO', 'AGUARDANDO_DECISAO'),
            ('EM_ATENDIMENTO', 'AGUARDANDO_SOLICITACAO_TRANSFERENCIA'),
            ('EM_ATENDIMENTO', 'AGUARDANDO_RECURSO_LEITO'),
            ('AGUARDANDO_EXAME_PARECER', 'EM_ATENDIMENTO'),
            ('AGUARDANDO_EXAME_PARECER', 'AGUARDANDO_DECISAO'),
            ('AGUARDANDO_EXAME_PARECER', 'AGUARDANDO_SOLICITACAO_TRANSFERENCIA'),
            ('AGUARDANDO_DECISAO', 'EM_ATENDIMENTO'),
            ('AGUARDANDO_DECISAO', 'AGUARDANDO_EXAME_PARECER'),
            ('AGUARDANDO_DECISAO', 'AGUARDANDO_SOLICITACAO_TRANSFERENCIA'),
            ('AGUARDANDO_DECISAO', 'AGUARDANDO_RECURSO_LEITO'),
            ('AGUARDANDO_SOLICITACAO_TRANSFERENCIA', 'TRANSFERENCIA_SOLICITADA'),
            ('AGUARDANDO_SOLICITACAO_TRANSFERENCIA', 'EM_ATENDIMENTO'),
            ('TRANSFERENCIA_SOLICITADA', 'AGUARDANDO_RECURSO_LEITO'),
            ('TRANSFERENCIA_SOLICITADA', 'ACEITO'),
            ('TRANSFERENCIA_SOLICITADA', 'EM_ATENDIMENTO'),
            ('AGUARDANDO_RECURSO_LEITO', 'ACEITO'),
            ('AGUARDANDO_RECURSO_LEITO', 'TRANSFERENCIA_SOLICITADA'),
            ('AGUARDANDO_RECURSO_LEITO', 'EM_ATENDIMENTO'),
            ('ACEITO', 'AGUARDANDO_TRANSPORTE'),
            ('ACEITO', 'TRANSFERIDO'),
            ('ACEITO', 'EM_ATENDIMENTO'),
            ('AGUARDANDO_TRANSPORTE', 'TRANSFERIDO'),
            ('AGUARDANDO_TRANSPORTE', 'ACEITO'),
            ('AGUARDANDO_TRANSPORTE', 'EM_ATENDIMENTO'),
            ('EM_ATENDIMENTO', 'INTERNADO'),
            ('AGUARDANDO_DECISAO', 'INTERNADO'),
            ('AGUARDANDO_RECURSO_LEITO', 'INTERNADO')
           ) AS t(origem, destino)
      JOIN fluxo.etapa o ON o.unidade_id = p_unidade_id AND o.codigo = t.origem
      JOIN fluxo.etapa d ON d.unidade_id = p_unidade_id AND d.codigo = t.destino
    ON CONFLICT DO NOTHING;

    -- Desfechos universais: de qualquer etapa não terminal para ALTA, ÓBITO, EVASÃO, CANCELADO.
    INSERT INTO fluxo.transicao_etapa (unidade_id, origem_id, destino_id)
    SELECT p_unidade_id, o.id, d.id
      FROM fluxo.etapa o
      JOIN fluxo.etapa d ON d.unidade_id = o.unidade_id
     WHERE o.unidade_id = p_unidade_id
       AND o.codigo = ANY (v_nao_terminais)
       AND d.codigo IN ('ALTA', 'OBITO', 'EVASAO', 'CANCELADO_ENCERRADO')
    ON CONFLICT DO NOTHING;

    INSERT INTO fluxo.motivo_bloqueio (unidade_id, categoria, codigo, descricao, exige_detalhe)
    VALUES
        (p_unidade_id, 'ASSISTENCIAL',     'AGUARDANDO_AVALIACAO',     'Aguardando avaliação',                false),
        (p_unidade_id, 'ASSISTENCIAL',     'AGUARDANDO_PARECER',       'Aguardando parecer',                  false),
        (p_unidade_id, 'ASSISTENCIAL',     'AGUARDANDO_EXAME',         'Aguardando exame',                    false),
        (p_unidade_id, 'ASSISTENCIAL',     'AGUARDANDO_RESULTADO',     'Aguardando resultado',                false),
        (p_unidade_id, 'ASSISTENCIAL',     'AGUARDANDO_PROCEDIMENTO',  'Aguardando procedimento',             false),
        (p_unidade_id, 'REGULACAO',        'SOLICITACAO_NAO_ENVIADA',  'Solicitação não enviada',             false),
        (p_unidade_id, 'REGULACAO',        'ATUALIZACAO_PENDENTE',     'Atualização pendente',                false),
        (p_unidade_id, 'REGULACAO',        'AGUARDANDO_ANALISE_ACEITE','Aguardando análise/aceite',           false),
        (p_unidade_id, 'REGULACAO',        'SEM_VAGA',                 'Sem vaga',                            false),
        (p_unidade_id, 'LOGISTICA',        'TRANSPORTE_PENDENTE',      'Ambulância/transporte pendente',      false),
        (p_unidade_id, 'LOGISTICA',        'DOCUMENTACAO_TRANSPORTE',  'Documentação de transporte',          false),
        (p_unidade_id, 'LOGISTICA',        'ACOMPANHANTE',             'Acompanhante',                        false),
        (p_unidade_id, 'LEITO_CAPACIDADE', 'SEM_LEITO_ESPECIALIDADE',  'Sem leito na especialidade',          false),
        (p_unidade_id, 'LEITO_CAPACIDADE', 'LEITO_EM_HIGIENIZACAO',    'Leito em higienização',               false),
        (p_unidade_id, 'LEITO_CAPACIDADE', 'DESTINO_SEM_CAPACIDADE',   'Unidade de destino sem capacidade',   false),
        (p_unidade_id, 'ADMINISTRATIVO',   'DOCUMENTO_PENDENTE',       'Documento obrigatório pendente',      false),
        (p_unidade_id, 'ADMINISTRATIVO',   'CADASTRO_INCOMPLETO',      'Cadastro incompleto',                 false),
        (p_unidade_id, 'ADMINISTRATIVO',   'CONTATO_NAO_REALIZADO',    'Contato não realizado',               false),
        (p_unidade_id, 'OUTROS',           'OUTROS',                   'Outros (justificar)',                 true),
        (p_unidade_id, 'NAO_DEFINIDA',     'CAUSA_EM_INVESTIGACAO',    'Causa ainda não definida / em investigação', true)
    ON CONFLICT (unidade_id, codigo) DO NOTHING;
END $$;

REVOKE ALL ON FUNCTION fluxo.provisionar_unidade(uuid, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.provisionar_unidade(uuid, boolean) TO ${app_role};
