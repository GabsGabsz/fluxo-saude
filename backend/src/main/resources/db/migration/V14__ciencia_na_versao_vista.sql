-- =============================================================================
-- V14 — Ciência na versão da regra VISTA pelo profissional (RF-022; revisão do PR #7)
-- =============================================================================
-- A ciência confirma uma ocorrência na versão da regra que o profissional viu (limite, ação
-- esperada...). Se a regra mudou desde a leitura, a confirmação é recusada (409) — nunca é
-- "promovida" para a versão nova, que ninguém viu.
--
-- Ordem de bloqueio, igual nas duas operações (sem deadlock):
--   1º linha de fluxo.regra_alerta  →  2º cabeça da cadeia de auditoria.
--   * alterar regra: o UPDATE bloqueia a linha (FOR NO KEY UPDATE) e só depois audita;
--   * registrar ciência: trava a regra (FOR SHARE, conflita com o UPDATE) ANTES de conferir a
--     versão e de gravar (o gatilho abaixo repete a trava e a conferência na própria inserção).
-- Se a alteração confirma primeiro, a ciência lê a versão nova e é recusada; se a ciência trava
-- primeiro, a alteração espera ela terminar. Nunca se confirma versão diferente da enviada.

-- Trava a regra (da unidade do contexto) e devolve versão e situação vigentes.
-- SECURITY DEFINER: quem registra ciência (enfermagem, médico...) não tem a política de
-- alteração da regra, que o FOR SHARE exigiria sob RLS; o alcance é a unidade do contexto.
CREATE FUNCTION fluxo.travar_regra_alerta(p_regra_id uuid)
    RETURNS TABLE (versao integer, ativa boolean)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF fluxo.ctx_usuario() IS NULL THEN
        RAISE EXCEPTION 'contexto de usuário ausente' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
    SELECT r.versao, r.ativa
      FROM fluxo.regra_alerta r
     WHERE r.id = p_regra_id AND r.unidade_id = ANY (fluxo.ctx_unidades())
       FOR SHARE;
END $$;

-- Última linha de defesa: a ciência só é gravada na versão VIGENTE da regra (travada).
-- Versão divergente → SQLSTATE próprio FX409 (a aplicação responde 409).
CREATE FUNCTION fluxo.tg_ciencia_alerta_versao_vigente() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_versao integer;
BEGIN
    -- Fora da unidade do contexto: nada a conferir aqui (o RLS recusa a inserção logo depois),
    -- e nada sobre a regra de outra unidade é revelado.
    IF NOT fluxo.sessao_do_dba() AND NOT (NEW.unidade_id = ANY (fluxo.ctx_unidades())) THEN
        RETURN NEW;
    END IF;
    SELECT r.versao INTO v_versao
      FROM fluxo.regra_alerta r
     WHERE r.id = NEW.regra_id AND r.unidade_id = NEW.unidade_id
       FOR SHARE;
    IF v_versao IS DISTINCT FROM NEW.regra_versao THEN
        RAISE EXCEPTION 'a regra mudou desde a leitura do alerta (versão vista %)', NEW.regra_versao
            USING ERRCODE = 'FX409';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ciencia_alerta_versao_vigente BEFORE INSERT ON fluxo.ciencia_alerta
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_ciencia_alerta_versao_vigente();

REVOKE ALL ON FUNCTION fluxo.travar_regra_alerta(uuid), fluxo.tg_ciencia_alerta_versao_vigente() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.travar_regra_alerta(uuid) TO ${app_role};
