-- =============================================================================
-- V18 — Histórico imutável das versões das regras de alerta (revisão do PR #10, alertas no
-- recebimento) — ver docs/adr/0009-plantao-e-indicadores.md
--
-- A passagem de plantão grava, para cada alerta, a regra e a VERSÃO vigente na entrega. Para
-- exibir um conteúdo histórico, a tela precisa do nome, do limite e da ação esperada DAQUELA
-- versão — não da configuração atual, que pode ter sido alterada ou desativada depois.
-- Cada INSERT/UPDATE de fluxo.regra_alerta grava aqui uma cópia da versão resultante (versão
-- única por regra: fluxo.tg_versao exige versao = anterior + 1). As versões existentes antes
-- desta migração só têm a versão VIGENTE copiada; versões anteriores aparecem como
-- "detalhes da versão indisponíveis", nunca com os dados atuais no lugar.
-- =============================================================================

CREATE TABLE fluxo.regra_alerta_versao (
    regra_id       uuid        NOT NULL,
    versao         integer     NOT NULL CHECK (versao >= 0),
    unidade_id     uuid        NOT NULL,
    nome           text        NOT NULL,
    tipo           fluxo.tipo_regra_alerta NOT NULL,
    etapa_id       uuid,
    categoria      fluxo.categoria_bloqueio,
    limite         interval,
    acao_esperada  text,
    ativa          boolean     NOT NULL,
    vigente_desde  timestamptz NOT NULL,
    PRIMARY KEY (regra_id, versao),
    FOREIGN KEY (unidade_id, regra_id) REFERENCES fluxo.regra_alerta (unidade_id, id)
);
COMMENT ON TABLE fluxo.regra_alerta_versao IS
    'Cópia imutável de cada versão de fluxo.regra_alerta (nome, tipo, limite, ação esperada, ativa), gravada pelo gatilho';

CREATE TRIGGER regra_alerta_versao_imutavel BEFORE UPDATE OR DELETE ON fluxo.regra_alerta_versao
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER regra_alerta_versao_sem_truncate BEFORE TRUNCATE ON fluxo.regra_alerta_versao
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();

-- Gravação só pelo gatilho (definer): a aplicação não escreve no histórico.
CREATE FUNCTION fluxo.tg_regra_alerta_registrar_versao() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    INSERT INTO fluxo.regra_alerta_versao (regra_id, versao, unidade_id, nome, tipo, etapa_id, categoria, limite,
                                           acao_esperada, ativa, vigente_desde)
    VALUES (NEW.id, NEW.versao, NEW.unidade_id, NEW.nome, NEW.tipo, NEW.etapa_id, NEW.categoria, NEW.limite,
            NEW.acao_esperada, NEW.ativa, NEW.atualizado_em);
    RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION fluxo.tg_regra_alerta_registrar_versao() FROM PUBLIC;
CREATE TRIGGER regra_alerta_registrar_versao AFTER INSERT OR UPDATE ON fluxo.regra_alerta
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_regra_alerta_registrar_versao();

-- Versão vigente das regras já existentes (as anteriores não têm cópia confiável).
INSERT INTO fluxo.regra_alerta_versao (regra_id, versao, unidade_id, nome, tipo, etapa_id, categoria, limite,
                                       acao_esperada, ativa, vigente_desde)
SELECT id, versao, unidade_id, nome, tipo, etapa_id, categoria, limite, acao_esperada, ativa, atualizado_em
  FROM fluxo.regra_alerta;

ALTER TABLE fluxo.regra_alerta_versao ENABLE ROW LEVEL SECURITY;
CREATE POLICY unidade_isolamento ON fluxo.regra_alerta_versao FOR SELECT TO ${app_role}
    USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));
REVOKE ALL ON fluxo.regra_alerta_versao FROM PUBLIC;
GRANT SELECT ON fluxo.regra_alerta_versao TO ${app_role};
