-- =============================================================================
-- V9 — Observação operacional (RF-028) e índices de leitura da Torre de Controle
-- =============================================================================

-- Observação operacional: texto curto, append-only, NÃO substitui a evolução clínica do
-- prontuário (RF-028). Fica fora de evento_episodio porque o evento é imutável, replicado na
-- linha do tempo e limitado a metadados curtos (LGPD, RN-009): o evento OBSERVACAO_REGISTRADA
-- só referencia o id. (Única exceção hoje: a justificativa curta de ajuste de horário, que o
-- RNF-017 exige junto ao fato — ver docs/decisoes-a-validar.md.)
CREATE TABLE fluxo.observacao (
    id             uuid PRIMARY KEY,
    unidade_id     uuid NOT NULL,
    episodio_id    uuid NOT NULL,
    texto          text NOT NULL CHECK (length(btrim(texto)) BETWEEN 3 AND 1000),
    autor_id       uuid NOT NULL DEFAULT fluxo.ctx_usuario() REFERENCES fluxo.usuario (id),
    registrada_em  timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (unidade_id, episodio_id) REFERENCES fluxo.episodio (unidade_id, id)
);
CREATE INDEX observacao_episodio_idx ON fluxo.observacao (episodio_id, registrada_em);

CREATE TRIGGER observacao_autoria BEFORE INSERT ON fluxo.observacao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_autoria('autor_id', 'registrada_em');
CREATE TRIGGER observacao_imutavel BEFORE UPDATE OR DELETE ON fluxo.observacao
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER observacao_sem_truncate BEFORE TRUNCATE ON fluxo.observacao
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.observacao
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar('texto');

ALTER TABLE fluxo.observacao ENABLE ROW LEVEL SECURITY;
CREATE POLICY unidade_isolamento ON fluxo.observacao TO ${app_role}
    USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]))
    WITH CHECK (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));

REVOKE ALL ON fluxo.observacao FROM PUBLIC;
GRANT SELECT, INSERT ON fluxo.observacao TO ${app_role};

-- Leitura da Torre: pendências abertas por episódio (contagem, vencidas, próximo prazo).
CREATE INDEX pendencia_abertas_episodio_idx ON fluxo.pendencia (episodio_id, prazo) WHERE status = 'ABERTA';
-- Filtro por responsável (RF-012).
CREATE INDEX pendencia_resp_setor_idx ON fluxo.pendencia (responsavel_setor_id) WHERE status = 'ABERTA';
