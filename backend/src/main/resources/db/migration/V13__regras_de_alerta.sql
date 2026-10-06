-- =============================================================================
-- V13 — Regras de alerta e ciência (M04, RF-011, RF-018, RF-022, RF-027, RN-006,
-- RN-007, RN-013, RN-014, CA-05, CA-06) — ver docs/adr/0007-alertas-e-travados.md
-- =============================================================================
-- "Paciente travado" é uma categoria OPERACIONAL derivada de regras de tempo, pendência ou
-- falta de atualização (ERS Anexo B) — nunca classificação de risco clínico (RN-007/013).
-- As regras são parâmetros da unidade (RN-006); NENHUMA vem cadastrada e nenhum tempo é
-- fixado no código (RN-014). Os alertas são CALCULADOS na leitura (servidor), a partir das
-- regras ativas e do estado dos episódios abertos; aqui se guardam só as regras e as
-- confirmações de ciência (RF-022), que não encerram pendência alguma.

CREATE TYPE fluxo.tipo_regra_alerta AS ENUM (
    'TEMPO_NA_ETAPA',     -- etapa atual há mais que o limite (opcional: etapa específica)
    'TEMPO_TOTAL',        -- episódio aberto há mais que o limite
    'TEMPO_BLOQUEADO',    -- bloqueado há mais que o limite (opcional: categoria do motivo)
    'SEM_ATUALIZACAO',    -- nenhum registro na linha do tempo há mais que o limite
    'PENDENCIA_VENCIDA'   -- pendência aberta com prazo vencido (o prazo é da própria pendência)
);

CREATE TABLE fluxo.regra_alerta (
    id             uuid PRIMARY KEY,
    unidade_id     uuid NOT NULL REFERENCES fluxo.unidade (id),
    nome           text NOT NULL CHECK (length(btrim(nome)) BETWEEN 3 AND 120),
    tipo           fluxo.tipo_regra_alerta NOT NULL,
    etapa_id       uuid,
    categoria      fluxo.categoria_bloqueio,
    limite         interval,
    acao_esperada  text CHECK (acao_esperada IS NULL OR length(btrim(acao_esperada)) BETWEEN 3 AND 200),
    ativa          boolean NOT NULL DEFAULT true,
    criado_por     uuid NOT NULL DEFAULT fluxo.ctx_usuario() REFERENCES fluxo.usuario (id),
    criado_em      timestamptz NOT NULL DEFAULT clock_timestamp(),
    atualizado_em  timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao         integer NOT NULL DEFAULT 0,
    UNIQUE (unidade_id, id),
    FOREIGN KEY (unidade_id, etapa_id) REFERENCES fluxo.etapa (unidade_id, id),
    -- Pendência vencida usa o prazo da pendência; os demais tipos exigem limite.
    CHECK ((tipo = 'PENDENCIA_VENCIDA') = (limite IS NULL)),
    CHECK (limite IS NULL OR (limite >= interval '1 minute' AND limite <= interval '30 days')),
    CHECK (categoria IS NULL OR tipo IN ('TEMPO_BLOQUEADO', 'PENDENCIA_VENCIDA')),
    CHECK (etapa_id IS NULL OR tipo <> 'PENDENCIA_VENCIDA')
);
CREATE INDEX regra_alerta_unidade_idx ON fluxo.regra_alerta (unidade_id) WHERE ativa;

-- Unidade e tipo não mudam (uma regra "nova" é outra regra; a anterior é desativada).
CREATE FUNCTION fluxo.tg_regra_alerta_imutavel() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.unidade_id <> OLD.unidade_id OR NEW.tipo <> OLD.tipo OR NEW.criado_por <> OLD.criado_por
       OR NEW.criado_em <> OLD.criado_em THEN
        RAISE EXCEPTION 'unidade, tipo e autoria de uma regra de alerta não podem ser alterados' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER regra_alerta_autoria BEFORE INSERT ON fluxo.regra_alerta
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_autoria('criado_por', 'criado_em');
CREATE TRIGGER regra_alerta_imutavel BEFORE UPDATE ON fluxo.regra_alerta
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_regra_alerta_imutavel();
CREATE TRIGGER regra_alerta_versao BEFORE UPDATE ON fluxo.regra_alerta
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
CREATE TRIGGER regra_alerta_atualizado_em BEFORE UPDATE ON fluxo.regra_alerta
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();
CREATE TRIGGER regra_alerta_sem_truncate BEFORE TRUNCATE ON fluxo.regra_alerta
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.regra_alerta
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar();

ALTER TABLE fluxo.regra_alerta ENABLE ROW LEVEL SECURITY;
CREATE POLICY unidade_isolamento ON fluxo.regra_alerta TO ${app_role}
    USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]))
    WITH CHECK (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));
-- Só o Administrador ativo da unidade configura regras (ERS §3; CONFIGURACAO_GERENCIAR).
CREATE POLICY regra_alerta_admin_insercao ON fluxo.regra_alerta AS RESTRICTIVE FOR INSERT TO ${app_role}
    WITH CHECK (fluxo.ctx_administra(unidade_id));
CREATE POLICY regra_alerta_admin_alteracao ON fluxo.regra_alerta AS RESTRICTIVE FOR UPDATE TO ${app_role}
    USING (fluxo.ctx_administra(unidade_id))
    WITH CHECK (fluxo.ctx_administra(unidade_id));

REVOKE ALL ON fluxo.regra_alerta FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON fluxo.regra_alerta TO ${app_role};   -- sem DELETE: desativa-se

-- -----------------------------------------------------------------------------
-- Ciência do alerta (RF-022): "estou ciente" de UMA ocorrência do alerta. A ocorrência é
-- identificada por (episódio, regra, versão da regra, instante de referência, pendência):
-- quando a situação muda (nova etapa, novo bloqueio, novo prazo...) ou a regra é alterada
-- (ex.: limite mais rígido), surge uma ocorrência nova, sem ciência.
-- Imutável, auditada, não encerra pendência nem altera o episódio.
-- -----------------------------------------------------------------------------
CREATE TABLE fluxo.ciencia_alerta (
    id             uuid PRIMARY KEY,
    unidade_id     uuid NOT NULL,
    episodio_id    uuid NOT NULL,
    regra_id       uuid NOT NULL,
    regra_versao   integer NOT NULL CHECK (regra_versao >= 0),
    referencia_em  timestamptz NOT NULL,
    pendencia_id   uuid REFERENCES fluxo.pendencia (id),
    autor_id       uuid NOT NULL DEFAULT fluxo.ctx_usuario() REFERENCES fluxo.usuario (id),
    registrada_em  timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (unidade_id, episodio_id) REFERENCES fluxo.episodio (unidade_id, id),
    FOREIGN KEY (unidade_id, regra_id) REFERENCES fluxo.regra_alerta (unidade_id, id)
);
CREATE UNIQUE INDEX ciencia_alerta_ocorrencia_uq ON fluxo.ciencia_alerta
    (episodio_id, regra_id, regra_versao, referencia_em,
     coalesce(pendencia_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Episódio encerrado não recebe ciência (os cronômetros pararam, RN-008); pendência do mesmo episódio.
CREATE FUNCTION fluxo.tg_ciencia_alerta_episodio_aberto() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    -- Sem bloqueio de propósito: um encerramento CONCORRENTE pode confirmar logo depois desta
    -- conferência e a ciência ficar registrada num episódio recém-encerrado. É inofensivo (a
    -- ciência é só informativa e auditada) e evita deadlock com o encerramento, que atualiza
    -- pendências (e a cadeia de auditoria) antes da linha do episódio.
    IF EXISTS (SELECT 1 FROM fluxo.episodio e WHERE e.id = NEW.episodio_id AND e.encerrado_em IS NOT NULL) THEN
        RAISE EXCEPTION 'episódio encerrado não recebe ciência de alerta' USING ERRCODE = '55000';
    END IF;
    IF NEW.pendencia_id IS NOT NULL AND NOT EXISTS (
           SELECT 1 FROM fluxo.pendencia p WHERE p.id = NEW.pendencia_id AND p.episodio_id = NEW.episodio_id) THEN
        RAISE EXCEPTION 'pendência não pertence ao episódio' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ciencia_alerta_autoria BEFORE INSERT ON fluxo.ciencia_alerta
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_autoria('autor_id', 'registrada_em');
CREATE TRIGGER ciencia_alerta_episodio_aberto BEFORE INSERT ON fluxo.ciencia_alerta
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_ciencia_alerta_episodio_aberto();
CREATE TRIGGER ciencia_alerta_imutavel BEFORE UPDATE OR DELETE ON fluxo.ciencia_alerta
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER ciencia_alerta_sem_truncate BEFORE TRUNCATE ON fluxo.ciencia_alerta
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.ciencia_alerta
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar();

ALTER TABLE fluxo.ciencia_alerta ENABLE ROW LEVEL SECURITY;
CREATE POLICY unidade_isolamento ON fluxo.ciencia_alerta TO ${app_role}
    USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]))
    WITH CHECK (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));

REVOKE ALL ON fluxo.ciencia_alerta FROM PUBLIC;
GRANT SELECT, INSERT ON fluxo.ciencia_alerta TO ${app_role};

-- Leitura do "último registro" de cada episódio (regra SEM_ATUALIZACAO).
CREATE INDEX evento_episodio_registro_idx ON fluxo.evento_episodio (episodio_id, registrado_em DESC);
