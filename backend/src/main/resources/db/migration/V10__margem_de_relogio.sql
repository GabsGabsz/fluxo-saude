-- =============================================================================
-- V10 — Margem de relógio entre aplicação e banco (RNF-017)
-- =============================================================================
-- A aplicação decide se um horário informado é AJUSTE MANUAL (e exige justificativa e a
-- permissão HORARIO_AJUSTAR) pelo relógio dela, no início do caso de uso; o banco confere
-- de novo, como última linha de defesa, pelo próprio relógio no momento do INSERT/UPDATE.
-- Sem margem, latência (espera de lock) ou pequena diferença entre os relógios fariam o
-- banco recusar, com erro genérico, um fato que a aplicação validou corretamente.
-- A margem de 1 minuto só torna o BANCO mais tolerante que a aplicação na fronteira; não
-- abre brecha relevante (a aplicação continua aplicando os limites exatos) e diferenças de
-- relógio acima disso são falha operacional (NTP), que deve aparecer como erro.

CREATE OR REPLACE FUNCTION fluxo.limite_passado(p_unidade_id uuid) RETURNS timestamptz
    LANGUAGE sql VOLATILE
    SET search_path = pg_catalog
AS $$ SELECT clock_timestamp() - u.retroatividade_maxima - interval '1 minute'
        FROM fluxo.unidade u WHERE u.id = p_unidade_id $$;

CREATE OR REPLACE FUNCTION fluxo.tg_evento_ajuste_manual() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
DECLARE
    v_unidade fluxo.unidade;
    v_atraso  interval;
BEGIN
    SELECT * INTO STRICT v_unidade FROM fluxo.unidade WHERE id = NEW.unidade_id;
    v_atraso := NEW.registrado_em - NEW.ocorrido_em;
    IF v_atraso > v_unidade.retroatividade_maxima + interval '1 minute' THEN
        RAISE EXCEPTION 'evento além da retroatividade permitida pela unidade' USING ERRCODE = '22007';
    END IF;
    -- Marcação/justificativa: obrigatórias acima do limiar (+ margem). Abaixo dele, um evento
    -- já marcado pela aplicação também precisa vir justificado (nunca marcação "vazia").
    IF v_atraso > v_unidade.limiar_ajuste_manual + interval '1 minute'
       OR NEW.dados ->> 'ajuste_manual' = 'true' THEN
        IF NEW.dados ->> 'ajuste_manual' IS DISTINCT FROM 'true'
           OR length(btrim(coalesce(NEW.dados ->> 'ajuste_justificativa', ''))) < 3 THEN
            RAISE EXCEPTION 'horário informado manualmente exige marcação e justificativa de ajuste (RNF-017)'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
