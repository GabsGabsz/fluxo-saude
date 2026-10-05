-- =============================================================================
-- V5 — Paciente (mínimo), episódio, linha do tempo e pendências
-- (ERS §4, §9, RF-002..RF-015, RN-003, RN-005, RN-008, RN-009, RNF-010)
--
-- Princípio: o domínio Java é a primeira linha de validação; estes triggers são a
-- última. Nenhuma regra crítica depende só da aplicação estar correta.
-- =============================================================================

-- Validação do Cartão Nacional de Saúde: 15 dígitos, prefixo 1/2 (definitivo) ou
-- 7/8/9 (provisório) e soma ponderada (pesos 15..1) divisível por 11.
CREATE FUNCTION fluxo.cns_valido(p text) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    SET search_path = pg_catalog
AS $$
    SELECT p ~ '^[12789][0-9]{14}$'
       AND (SELECT sum(substr(p, i, 1)::int * (16 - i)) FROM generate_series(1, 15) AS i) % 11 = 0
$$;
GRANT EXECUTE ON FUNCTION fluxo.cns_valido(text) TO ${app_role};

-- -----------------------------------------------------------------------------
-- Paciente: SOMENTE o necessário para identificar o caso no fluxo (RN-009).
-- Sem endereço, telefone, CPF, diagnóstico etc.
-- -----------------------------------------------------------------------------
CREATE TABLE fluxo.paciente (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    unidade_id                uuid NOT NULL REFERENCES fluxo.unidade (id),
    nome                      text NOT NULL CHECK (length(btrim(nome)) BETWEEN 2 AND 200),
    data_nascimento           date CHECK (data_nascimento IS NULL OR data_nascimento >= DATE '1900-01-01'),
    cns                       text CHECK (cns IS NULL OR fluxo.cns_valido(cns)),
    -- Nº de prontuário/atendimento do sistema hospitalar local (fonte da verdade externa).
    identificador_institucional text CHECK (identificador_institucional IS NULL
                                            OR identificador_institucional ~ '^[A-Za-z0-9./-]{1,40}$'),
    -- RF-037: reconciliação de cadastro duplicado. O duplicado NÃO é apagado: aponta
    -- para o cadastro principal e deixa de aceitar novos episódios; histórico preservado.
    reconciliado_com_id       uuid,
    reconciliado_em           timestamptz,
    reconciliado_por          uuid REFERENCES fluxo.usuario (id),
    justificativa_reconciliacao text CHECK (justificativa_reconciliacao IS NULL
                                            OR length(btrim(justificativa_reconciliacao)) BETWEEN 3 AND 500),
    criado_em                 timestamptz NOT NULL DEFAULT clock_timestamp(),
    atualizado_em             timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao                    integer NOT NULL DEFAULT 0,
    UNIQUE (unidade_id, id),
    FOREIGN KEY (unidade_id, reconciliado_com_id) REFERENCES fluxo.paciente (unidade_id, id),
    CONSTRAINT paciente_reconciliacao_coerente CHECK (
        (reconciliado_com_id IS NULL) = (reconciliado_em IS NULL)
        AND (reconciliado_com_id IS NULL) = (justificativa_reconciliacao IS NULL)
        AND reconciliado_com_id IS DISTINCT FROM id)
);
CREATE UNIQUE INDEX paciente_cns_unico ON fluxo.paciente (unidade_id, cns) WHERE cns IS NOT NULL;
CREATE UNIQUE INDEX paciente_ident_unico ON fluxo.paciente (unidade_id, identificador_institucional)
    WHERE identificador_institucional IS NOT NULL;
CREATE INDEX paciente_nome_trgm ON fluxo.paciente
    USING gin (fluxo.normalizar_texto(nome) public.gin_trgm_ops);
CREATE TRIGGER paciente_atualizado_em BEFORE UPDATE ON fluxo.paciente
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();
CREATE TRIGGER paciente_versao BEFORE UPDATE ON fluxo.paciente
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();

-- Cadastro reconciliado é definitivo (não "desfaz" nem muda de principal).
CREATE FUNCTION fluxo.tg_paciente_reconciliacao() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF OLD.reconciliado_com_id IS NOT NULL THEN
        RAISE EXCEPTION 'cadastro reconciliado é imutável' USING ERRCODE = '55000';
    END IF;
    IF NEW.reconciliado_com_id IS NOT NULL THEN
        NEW.reconciliado_por := fluxo.exigir_usuario();
        NEW.reconciliado_em  := clock_timestamp();
        IF EXISTS (SELECT 1 FROM fluxo.paciente p WHERE p.id = NEW.reconciliado_com_id AND p.reconciliado_com_id IS NOT NULL) THEN
            RAISE EXCEPTION 'o cadastro principal também está reconciliado; use o principal final' USING ERRCODE = '23514';
        END IF;
        IF EXISTS (SELECT 1 FROM fluxo.episodio e WHERE e.paciente_id = NEW.id AND e.encerrado_em IS NULL) THEN
            RAISE EXCEPTION 'cadastro duplicado com episódio ativo: encerre ou justifique o episódio antes de reconciliar'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER paciente_reconciliacao BEFORE UPDATE ON fluxo.paciente
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_paciente_reconciliacao();

-- Limite inferior para instantes informados manualmente (parâmetro da unidade).
CREATE FUNCTION fluxo.limite_passado(p_unidade_id uuid) RETURNS timestamptz
    LANGUAGE sql VOLATILE
    SET search_path = pg_catalog
AS $$ SELECT clock_timestamp() - u.retroatividade_maxima FROM fluxo.unidade u WHERE u.id = p_unidade_id $$;
GRANT EXECUTE ON FUNCTION fluxo.limite_passado(uuid) TO ${app_role};

-- -----------------------------------------------------------------------------
-- Episódio
-- -----------------------------------------------------------------------------
CREATE TABLE fluxo.episodio (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    unidade_id                  uuid NOT NULL REFERENCES fluxo.unidade (id),
    paciente_id                 uuid NOT NULL,
    setor_id                    uuid NOT NULL,
    entrada_em                  timestamptz NOT NULL,
    etapa_id                    uuid NOT NULL,
    etapa_desde                 timestamptz NOT NULL,
    motivo_bloqueio_id          uuid,
    motivo_detalhe              text CHECK (motivo_detalhe IS NULL OR length(btrim(motivo_detalhe)) BETWEEN 3 AND 500),
    bloqueio_desde              timestamptz,
    especialidade_requerida_id  uuid REFERENCES fluxo.especialidade (id),
    destino_descricao           text CHECK (destino_descricao IS NULL OR length(destino_descricao) <= 200),
    protocolo_sistema           text CHECK (protocolo_sistema IS NULL OR protocolo_sistema ~ '^[A-Z0-9_]{2,32}$'),
    protocolo_numero            text CHECK (protocolo_numero IS NULL OR protocolo_numero ~ '^[A-Za-z0-9./-]{1,60}$'),
    desfecho                    fluxo.tipo_desfecho,
    encerrado_em                timestamptz,
    -- RF-003: segundo episódio ativo do mesmo paciente é PERMITIDO, mas exige justificativa.
    justificativa_duplicidade   text CHECK (justificativa_duplicidade IS NULL
                                            OR length(btrim(justificativa_duplicidade)) BETWEEN 3 AND 300),
    -- RN-017: continuidade entre unidades (ex.: UPA -> hospital monitorado) sem fundir episódios.
    episodio_origem_id          uuid REFERENCES fluxo.episodio (id),
    justificativa_encerramento  text CHECK (justificativa_encerramento IS NULL
                                            OR length(btrim(justificativa_encerramento)) BETWEEN 3 AND 1000),
    criado_em                   timestamptz NOT NULL DEFAULT clock_timestamp(),
    criado_por                  uuid NOT NULL DEFAULT fluxo.ctx_usuario() REFERENCES fluxo.usuario (id),
    atualizado_em               timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao                      integer NOT NULL DEFAULT 0,
    UNIQUE (unidade_id, id),
    FOREIGN KEY (unidade_id, paciente_id)        REFERENCES fluxo.paciente (unidade_id, id),
    FOREIGN KEY (unidade_id, setor_id)           REFERENCES fluxo.setor (unidade_id, id),
    FOREIGN KEY (unidade_id, etapa_id)           REFERENCES fluxo.etapa (unidade_id, id),
    FOREIGN KEY (unidade_id, motivo_bloqueio_id) REFERENCES fluxo.motivo_bloqueio (unidade_id, id),
    CONSTRAINT episodio_encerramento_coerente CHECK ((desfecho IS NULL) = (encerrado_em IS NULL)),
    CONSTRAINT episodio_bloqueio_coerente     CHECK ((motivo_bloqueio_id IS NULL) = (bloqueio_desde IS NULL)),
    CONSTRAINT episodio_detalhe_requer_motivo CHECK (motivo_detalhe IS NULL OR motivo_bloqueio_id IS NOT NULL),
    CONSTRAINT episodio_protocolo_par         CHECK ((protocolo_sistema IS NULL) = (protocolo_numero IS NULL)),
    CONSTRAINT episodio_cronologia CHECK (
        etapa_desde >= entrada_em
        AND (bloqueio_desde IS NULL OR bloqueio_desde >= entrada_em)
        AND (encerrado_em IS NULL OR encerrado_em >= etapa_desde))
);

-- RF-003 (v1.1): duplicidade é DETECTADA (índice para a consulta), não bloqueada.
CREATE INDEX episodio_ativo_paciente_idx ON fluxo.episodio (unidade_id, paciente_id) WHERE encerrado_em IS NULL;
-- Torre de Controle (RF-010): casos ativos por unidade.
CREATE INDEX episodio_ativos_idx ON fluxo.episodio (unidade_id, etapa_id, etapa_desde) WHERE encerrado_em IS NULL;
CREATE INDEX episodio_encerrados_idx ON fluxo.episodio (unidade_id, encerrado_em) WHERE encerrado_em IS NOT NULL;
CREATE INDEX episodio_protocolo_idx ON fluxo.episodio (unidade_id, protocolo_numero) WHERE protocolo_numero IS NOT NULL;

CREATE FUNCTION fluxo.tg_episodio_regras() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
DECLARE
    v_etapa       fluxo.etapa;
    v_motivo      fluxo.motivo_bloqueio;
    v_mudou_etapa boolean;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.encerrado_em IS NOT NULL THEN
            RAISE EXCEPTION 'episódio encerrado é imutável; correções devem ser registradas como novo evento'
                USING ERRCODE = '55000';
        END IF;
        IF NEW.unidade_id <> OLD.unidade_id OR NEW.paciente_id <> OLD.paciente_id
           OR NEW.entrada_em <> OLD.entrada_em OR NEW.criado_por <> OLD.criado_por
           OR NEW.criado_em <> OLD.criado_em
           OR NEW.justificativa_duplicidade IS DISTINCT FROM OLD.justificativa_duplicidade
           OR NEW.episodio_origem_id IS DISTINCT FROM OLD.episodio_origem_id THEN
            RAISE EXCEPTION 'campos de identidade do episódio não podem ser alterados' USING ERRCODE = '55000';
        END IF;
    END IF;

    SELECT * INTO STRICT v_etapa FROM fluxo.etapa WHERE id = NEW.etapa_id;

    IF TG_OP = 'INSERT' THEN
        -- RF-003/RF-037: serializa aberturas do mesmo paciente (FOR UPDATE) para que a
        -- detecção de duplicidade seja confiável mesmo com duas aberturas simultâneas.
        IF EXISTS (SELECT 1 FROM fluxo.paciente p WHERE p.id = NEW.paciente_id AND p.reconciliado_com_id IS NOT NULL) THEN
            RAISE EXCEPTION 'cadastro reconciliado: abra o episódio no cadastro principal' USING ERRCODE = '23514';
        END IF;
        PERFORM 1 FROM fluxo.paciente p WHERE p.id = NEW.paciente_id FOR UPDATE;
        IF NEW.justificativa_duplicidade IS NULL AND EXISTS (
               SELECT 1 FROM fluxo.episodio e
                WHERE e.unidade_id = NEW.unidade_id AND e.paciente_id = NEW.paciente_id AND e.encerrado_em IS NULL) THEN
            RAISE EXCEPTION 'possível duplicidade: o paciente já tem episódio ativo nesta unidade; confirme com justificativa'
                USING ERRCODE = '23505';
        END IF;
        IF NEW.entrada_em < fluxo.limite_passado(NEW.unidade_id) THEN
            RAISE EXCEPTION 'data/hora de entrada além da retroatividade permitida pela unidade' USING ERRCODE = '22007';
        END IF;
        IF v_etapa.natureza = 'DESFECHO' THEN
            RAISE EXCEPTION 'episódio não pode ser aberto em etapa de desfecho' USING ERRCODE = '23514';
        END IF;
        IF NOT v_etapa.ativa THEN
            RAISE EXCEPTION 'etapa inativa: %', v_etapa.codigo USING ERRCODE = '23514';
        END IF;
        IF NEW.entrada_em > fluxo.limite_futuro() THEN
            RAISE EXCEPTION 'data/hora de entrada no futuro' USING ERRCODE = '22007';
        END IF;
    ELSIF NEW.etapa_id <> OLD.etapa_id THEN
        IF NOT v_etapa.ativa THEN
            RAISE EXCEPTION 'etapa inativa: %', v_etapa.codigo USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM fluxo.transicao_etapa t
                        WHERE t.unidade_id = NEW.unidade_id
                          AND t.origem_id = OLD.etapa_id AND t.destino_id = NEW.etapa_id) THEN
            RAISE EXCEPTION 'transição de etapa não permitida' USING ERRCODE = '23514';
        END IF;
        IF NEW.etapa_desde < OLD.etapa_desde THEN
            RAISE EXCEPTION 'nova etapa não pode começar antes da etapa anterior' USING ERRCODE = '22007';
        END IF;
    ELSIF NEW.etapa_desde <> OLD.etapa_desde THEN
        RAISE EXCEPTION 'início da etapa só muda com mudança de etapa' USING ERRCODE = '55000';
    END IF;

    IF (TG_OP = 'UPDATE' AND NEW.etapa_desde <> OLD.etapa_desde AND NEW.etapa_desde < fluxo.limite_passado(NEW.unidade_id))
       OR (NEW.bloqueio_desde IS DISTINCT FROM (CASE WHEN TG_OP = 'UPDATE' THEN OLD.bloqueio_desde END)
           AND NEW.bloqueio_desde < fluxo.limite_passado(NEW.unidade_id)) THEN
        RAISE EXCEPTION 'data/hora além da retroatividade permitida pela unidade' USING ERRCODE = '22007';
    END IF;

    IF NEW.etapa_desde > fluxo.limite_futuro()
       OR coalesce(NEW.bloqueio_desde, '-infinity') > fluxo.limite_futuro()
       OR coalesce(NEW.encerrado_em, '-infinity') > fluxo.limite_futuro() THEN
        RAISE EXCEPTION 'data/hora no futuro' USING ERRCODE = '22007';
    END IF;

    -- As exigências da etapa são verificadas quando a etapa, o motivo ou o protocolo
    -- mudam — assim, endurecer a configuração de uma etapa não "trava" episódios que
    -- já estavam nela (ex.: impedir troca de setor).
    v_mudou_etapa := TG_OP = 'INSERT' OR NEW.etapa_id <> OLD.etapa_id;

    -- RN-003: estado de espera configurado exige motivo de bloqueio.
    IF v_etapa.exige_motivo_bloqueio AND NEW.motivo_bloqueio_id IS NULL
       AND (v_mudou_etapa OR OLD.motivo_bloqueio_id IS NOT NULL) THEN
        RAISE EXCEPTION 'a etapa "%" exige motivo de bloqueio', v_etapa.nome USING ERRCODE = '23514';
    END IF;

    IF NEW.motivo_bloqueio_id IS NOT NULL THEN
        SELECT * INTO STRICT v_motivo FROM fluxo.motivo_bloqueio WHERE id = NEW.motivo_bloqueio_id;
        IF (TG_OP = 'INSERT' OR NEW.motivo_bloqueio_id IS DISTINCT FROM OLD.motivo_bloqueio_id) AND NOT v_motivo.ativo THEN
            RAISE EXCEPTION 'motivo de bloqueio inativo: %', v_motivo.codigo USING ERRCODE = '23514';
        END IF;
        IF v_motivo.exige_detalhe AND NEW.motivo_detalhe IS NULL
           AND (TG_OP = 'INSERT' OR NEW.motivo_bloqueio_id IS DISTINCT FROM OLD.motivo_bloqueio_id
                OR OLD.motivo_detalhe IS NOT NULL) THEN
            RAISE EXCEPTION 'o motivo "%" exige detalhamento', v_motivo.descricao USING ERRCODE = '23514';
        END IF;
    END IF;

    -- RF-009
    IF v_etapa.exige_protocolo_externo AND NEW.protocolo_numero IS NULL
       AND (v_mudou_etapa OR OLD.protocolo_numero IS NOT NULL) THEN
        RAISE EXCEPTION 'a etapa "%" exige protocolo externo', v_etapa.nome USING ERRCODE = '23514';
    END IF;

    -- RF-015 / RN-008
    IF v_etapa.natureza = 'DESFECHO' THEN
        IF NEW.desfecho IS DISTINCT FROM v_etapa.desfecho OR NEW.encerrado_em IS DISTINCT FROM NEW.etapa_desde THEN
            RAISE EXCEPTION 'encerramento incoerente com a etapa de desfecho' USING ERRCODE = '23514';
        END IF;
        IF NEW.motivo_bloqueio_id IS NOT NULL THEN
            RAISE EXCEPTION 'episódio encerrado não pode manter motivo de bloqueio' USING ERRCODE = '23514';
        END IF;
        IF v_etapa.exige_justificativa AND NEW.justificativa_encerramento IS NULL THEN
            RAISE EXCEPTION 'o desfecho "%" exige justificativa', v_etapa.nome USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.desfecho IS NOT NULL OR NEW.justificativa_encerramento IS NOT NULL THEN
        RAISE EXCEPTION 'desfecho só pode ser registrado em etapa de desfecho' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER episodio_autoria BEFORE INSERT ON fluxo.episodio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_autoria('criado_por', 'criado_em');
CREATE TRIGGER episodio_regras BEFORE INSERT OR UPDATE ON fluxo.episodio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_episodio_regras();
CREATE TRIGGER episodio_atualizado_em BEFORE UPDATE ON fluxo.episodio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();
CREATE TRIGGER episodio_versao BEFORE UPDATE ON fluxo.episodio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
CREATE TRIGGER episodio_sem_exclusao BEFORE DELETE OR TRUNCATE ON fluxo.episodio
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();

CREATE FUNCTION fluxo.dados_evento_validos(p jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    SET search_path = pg_catalog
AS $$
    SELECT jsonb_typeof(p) = 'object'
       AND (SELECT count(*) FROM jsonb_each(p)) <= 20
       AND NOT EXISTS (SELECT 1 FROM jsonb_each(p) e
                        WHERE e.key !~ '^[a-z_]{1,40}$'
                           OR jsonb_typeof(e.value) NOT IN ('string', 'number', 'boolean')
                           OR length(e.value #>> '{}') > 120)
$$;
GRANT EXECUTE ON FUNCTION fluxo.dados_evento_validos(jsonb) TO ${app_role};

-- -----------------------------------------------------------------------------
-- Linha do tempo (RF-014): append-only. Correções = novo evento (RNF-010).
-- id é UUIDv7 gerado na aplicação (ordenável no tempo).
-- -----------------------------------------------------------------------------
CREATE TABLE fluxo.evento_episodio (
    id                 uuid PRIMARY KEY,
    unidade_id         uuid NOT NULL,
    episodio_id        uuid NOT NULL,
    tipo               text NOT NULL CHECK (tipo ~ '^[A-Z_]{3,64}$'),
    ocorrido_em        timestamptz NOT NULL,           -- instante do fato (pode ser retroativo)
    registrado_em      timestamptz NOT NULL DEFAULT clock_timestamp(),  -- instante do registro
    autor_id           uuid NOT NULL DEFAULT fluxo.ctx_usuario() REFERENCES fluxo.usuario (id),
    -- Apenas identificadores/códigos: objeto plano, até 20 chaves, valores escalares curtos.
    -- Texto livre (descrições, observações) NÃO vai para o evento (imutável) — LGPD.
    dados              jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (fluxo.dados_evento_validos(dados)),
    corrige_evento_id  uuid,
    UNIQUE (episodio_id, id),
    FOREIGN KEY (unidade_id, episodio_id) REFERENCES fluxo.episodio (unidade_id, id),
    -- Correção só pode apontar para evento do MESMO episódio.
    FOREIGN KEY (episodio_id, corrige_evento_id) REFERENCES fluxo.evento_episodio (episodio_id, id),
    CHECK (ocorrido_em <= registrado_em + interval '2 minutes')
);
CREATE INDEX evento_episodio_linha_idx ON fluxo.evento_episodio (episodio_id, ocorrido_em, id);

-- Autor e instante de registro definidos pelo banco (sobrescrevem o que vier).
CREATE TRIGGER evento_autoria BEFORE INSERT ON fluxo.evento_episodio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_autoria('autor_id', 'registrado_em');
-- RNF-017: fato informado com horário anterior ao do servidor (além do limiar da
-- unidade) é AJUSTE MANUAL: precisa estar marcado e justificado no próprio evento,
-- e respeitar a retroatividade máxima da unidade. (Nome "evento_verificar_..."
-- garante execução após "evento_autoria", que fixa registrado_em.)
CREATE FUNCTION fluxo.tg_evento_ajuste_manual() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
DECLARE
    v_unidade fluxo.unidade;
BEGIN
    SELECT * INTO STRICT v_unidade FROM fluxo.unidade WHERE id = NEW.unidade_id;
    IF NEW.registrado_em - NEW.ocorrido_em > v_unidade.limiar_ajuste_manual THEN
        IF NEW.registrado_em - NEW.ocorrido_em > v_unidade.retroatividade_maxima THEN
            RAISE EXCEPTION 'evento além da retroatividade permitida pela unidade' USING ERRCODE = '22007';
        END IF;
        IF NEW.dados ->> 'ajuste_manual' IS DISTINCT FROM 'true'
           OR length(btrim(coalesce(NEW.dados ->> 'ajuste_justificativa', ''))) < 3 THEN
            RAISE EXCEPTION 'horário informado manualmente exige marcação e justificativa de ajuste (RNF-017)'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER evento_verificar_ajuste_manual BEFORE INSERT ON fluxo.evento_episodio
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_evento_ajuste_manual();
CREATE TRIGGER evento_imutavel BEFORE UPDATE OR DELETE ON fluxo.evento_episodio
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER evento_sem_truncate BEFORE TRUNCATE ON fluxo.evento_episodio
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();

-- -----------------------------------------------------------------------------
-- Pendências (RF-007, RF-013, RN-005)
-- -----------------------------------------------------------------------------
-- RN-007 / RN-013: criticidade OPERACIONAL (atraso/urgência de ação). Nunca é
-- classificação de risco clínico e nunca é derivada de dado clínico.
CREATE TYPE fluxo.criticidade_operacional AS ENUM ('BAIXA', 'MEDIA', 'ALTA', 'CRITICA');
CREATE TYPE fluxo.status_pendencia AS ENUM ('ABERTA', 'RESOLVIDA', 'CANCELADA', 'ENCERRADA_POR_DESFECHO');

CREATE TABLE fluxo.pendencia (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    unidade_id              uuid NOT NULL,
    episodio_id             uuid NOT NULL,
    categoria               fluxo.categoria_bloqueio NOT NULL,
    descricao               text NOT NULL CHECK (length(btrim(descricao)) BETWEEN 3 AND 500),
    responsavel_usuario_id  uuid REFERENCES fluxo.usuario (id),
    responsavel_setor_id    uuid,
    responsavel_papel       fluxo.papel,
    prazo                   timestamptz NOT NULL,
    criticidade_operacional fluxo.criticidade_operacional NOT NULL,
    status                  fluxo.status_pendencia NOT NULL DEFAULT 'ABERTA',
    criada_em               timestamptz NOT NULL DEFAULT clock_timestamp(),
    criada_por              uuid NOT NULL DEFAULT fluxo.ctx_usuario() REFERENCES fluxo.usuario (id),
    resolucao               text CHECK (resolucao IS NULL OR length(btrim(resolucao)) BETWEEN 3 AND 1000),
    encerrada_em            timestamptz,
    encerrada_por           uuid REFERENCES fluxo.usuario (id),
    atualizado_em           timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao                  integer NOT NULL DEFAULT 0,
    FOREIGN KEY (unidade_id, episodio_id)          REFERENCES fluxo.episodio (unidade_id, id),
    FOREIGN KEY (unidade_id, responsavel_setor_id) REFERENCES fluxo.setor (unidade_id, id),
    -- Responsável: exatamente um entre usuário, setor ou perfil (ERS §9).
    CONSTRAINT pendencia_um_responsavel CHECK (num_nonnulls(responsavel_usuario_id, responsavel_setor_id, responsavel_papel) = 1),
    -- RN-005: só encerra com resolução/justificativa.
    CONSTRAINT pendencia_encerramento_coerente CHECK (
        (status = 'ABERTA' AND encerrada_em IS NULL AND resolucao IS NULL)
        OR (status <> 'ABERTA' AND encerrada_em IS NOT NULL AND resolucao IS NOT NULL)),
    CONSTRAINT pendencia_cronologia CHECK (encerrada_em IS NULL OR encerrada_em >= criada_em)
);
CREATE INDEX pendencia_abertas_idx ON fluxo.pendencia (unidade_id, prazo) WHERE status = 'ABERTA';
CREATE INDEX pendencia_episodio_idx ON fluxo.pendencia (episodio_id, status);
CREATE INDEX pendencia_resp_usuario_idx ON fluxo.pendencia (responsavel_usuario_id) WHERE status = 'ABERTA';

CREATE FUNCTION fluxo.tg_pendencia_regras() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
DECLARE
    v_encerrado timestamptz;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'ABERTA' THEN
            RAISE EXCEPTION 'pendência encerrada é imutável' USING ERRCODE = '55000';
        END IF;
        IF NEW.unidade_id <> OLD.unidade_id OR NEW.episodio_id <> OLD.episodio_id
           OR NEW.criada_em <> OLD.criada_em OR NEW.criada_por <> OLD.criada_por THEN
            RAISE EXCEPTION 'campos de identidade da pendência não podem ser alterados' USING ERRCODE = '55000';
        END IF;
    END IF;

    -- FOR SHARE: conflita com o UPDATE que encerra o episódio. Sem isto, uma
    -- pendência criada em paralelo ao encerramento poderia ficar aberta (corrida).
    SELECT e.encerrado_em INTO STRICT v_encerrado FROM fluxo.episodio e WHERE e.id = NEW.episodio_id FOR SHARE;
    -- Após o encerramento, só é permitido encerrar pendência abertas pelo desfecho.
    IF v_encerrado IS NOT NULL AND NOT (TG_OP = 'UPDATE' AND NEW.status = 'ENCERRADA_POR_DESFECHO') THEN
        RAISE EXCEPTION 'episódio encerrado não aceita alteração de pendências' USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ABERTA' THEN
            RAISE EXCEPTION 'pendência deve nascer aberta' USING ERRCODE = '23514';
        END IF;
        IF NEW.prazo < clock_timestamp() - interval '5 minutes' THEN
            RAISE EXCEPTION 'prazo da pendência no passado' USING ERRCODE = '22007';
        END IF;
    END IF;

    -- Encerramento: autor e instante são definidos pelo banco (não forjáveis e
    -- imunes a diferença de relógio entre servidores).
    IF NEW.status <> 'ABERTA' THEN
        NEW.encerrada_por := coalesce(fluxo.ctx_usuario(), NEW.encerrada_por);
        NEW.encerrada_em  := greatest(clock_timestamp(), NEW.criada_em);
    END IF;

    -- Usuário responsável precisa estar lotado na unidade (mínimo privilégio).
    IF NEW.responsavel_usuario_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR NEW.responsavel_usuario_id IS DISTINCT FROM OLD.responsavel_usuario_id)
       AND NOT EXISTS (SELECT 1 FROM fluxo.lotacao l
                        WHERE l.usuario_id = NEW.responsavel_usuario_id AND l.unidade_id = NEW.unidade_id) THEN
        RAISE EXCEPTION 'usuário responsável não pertence à unidade' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END $$;
CREATE TRIGGER pendencia_autoria BEFORE INSERT ON fluxo.pendencia
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_autoria('criada_por', 'criada_em');
CREATE TRIGGER pendencia_regras BEFORE INSERT OR UPDATE ON fluxo.pendencia
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_pendencia_regras();
CREATE TRIGGER pendencia_atualizado_em BEFORE UPDATE ON fluxo.pendencia
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();
CREATE TRIGGER pendencia_versao BEFORE UPDATE ON fluxo.pendencia
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
CREATE TRIGGER pendencia_sem_exclusao BEFORE DELETE OR TRUNCATE ON fluxo.pendencia
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();

-- RN-008: episódio encerrado não pode ter pendência aberta, e pendência
-- "encerrada por desfecho" exige episódio encerrado. Verificado no COMMIT
-- (constraint trigger adiável): a aplicação encerra as pendências e o episódio na
-- mesma transação, em qualquer ordem, e cada encerramento gera seu evento.
CREATE FUNCTION fluxo.tg_consistencia_desfecho() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_TABLE_NAME = 'episodio' THEN
        IF EXISTS (SELECT 1 FROM fluxo.pendencia p WHERE p.episodio_id = NEW.id AND p.status = 'ABERTA') THEN
            RAISE EXCEPTION 'episódio encerrado com pendências abertas (encerre-as na mesma transação)'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.status = 'ENCERRADA_POR_DESFECHO'
          AND NOT EXISTS (SELECT 1 FROM fluxo.episodio e WHERE e.id = NEW.episodio_id AND e.encerrado_em IS NOT NULL) THEN
        RAISE EXCEPTION 'pendência encerrada por desfecho exige episódio encerrado' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER episodio_sem_pendencia_aberta AFTER UPDATE OF encerrado_em ON fluxo.episodio
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.encerrado_em IS NOT NULL)
    EXECUTE FUNCTION fluxo.tg_consistencia_desfecho();
CREATE CONSTRAINT TRIGGER pendencia_desfecho_coerente AFTER INSERT OR UPDATE OF status ON fluxo.pendencia
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.status = 'ENCERRADA_POR_DESFECHO')
    EXECUTE FUNCTION fluxo.tg_consistencia_desfecho();
