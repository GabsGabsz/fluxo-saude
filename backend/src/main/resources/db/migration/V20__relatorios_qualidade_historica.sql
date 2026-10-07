-- =============================================================================
-- V20 — Relatórios gerenciais: correções da revisão do PR #11 (extensão aprovada, issue #9)
-- (V19 não é reescrita; as funções são substituídas com a mesma assinatura e as mesmas permissões.)
--
-- 1. rel_intervalos: a categoria de um bloqueio é a registrada no INÍCIO do intervalo (evento que
--    o abriu). Redefinições do mesmo motivo (ex.: só o detalhe mudou) continuam o mesmo intervalo,
--    e a categoria não é mais escolhida por ordem alfabética entre os eventos do grupo.
-- 2. rel_qualidade:
--    - "Registros retroativos": o PERÍODO é o do registro (registrado_em); com filtro de setor, o
--      registro é atribuído ao setor em vigor NO INSTANTE DO FATO (ocorrido_em), pela linha do
--      tempo — nunca ao setor atual/final do episódio. Uma transferência posterior não move o
--      passado para outro setor.
--    - "Bloqueios iniciados sem causa": conta os INÍCIOS dos intervalos normalizados de
--      rel_intervalos (a mesma definição de Gargalos e Evolução), não cada BLOQUEIO_DEFINIDO.
--      Com filtro de setor, vale o setor em que o intervalo começou.
--    - Fatos sem setor determinável pela linha do tempo NÃO são atribuídos ao setor atual: ficam
--      fora do filtro e são contados na seção SETOR_NAO_ATRIBUIDO.
--    - Estoque (atualidade, causa em investigação, cobertura de campos) continua pelo setor ATUAL.
--    - Cobertura LINHA_DO_TEMPO: população e filtro próprios (ver comentário na consulta).
-- =============================================================================

CREATE OR REPLACE FUNCTION fluxo.rel_intervalos(p_unidade uuid, p_desde timestamptz, p_ate timestamptz)
    RETURNS TABLE (episodio_id uuid, tipo text, valor text, categoria text, ini timestamptz, fim timestamptz,
                   ultimo boolean)
    LANGUAGE sql STABLE
    ROWS 100000
    SET search_path = pg_catalog
AS $$
    WITH eps AS (
        SELECT e.id, e.encerrado_em
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade
           AND e.entrada_em < p_ate
           AND (e.encerrado_em IS NULL OR e.encerrado_em >= p_desde)
    ),
    ev AS (
        SELECT ev.episodio_id, eps.encerrado_em, ev.ocorrido_em, ev.registrado_em, ev.id, ev.tipo AS tipo_evento,
               t.tipo, t.valor, t.categoria
          FROM fluxo.evento_episodio ev
          JOIN eps ON eps.id = ev.episodio_id
          CROSS JOIN LATERAL (
              SELECT 'ETAPA' AS tipo,
                     CASE ev.tipo WHEN 'ETAPA_ALTERADA' THEN ev.dados ->> 'para' ELSE ev.dados ->> 'etapa' END AS valor,
                     NULL::text AS categoria
               WHERE ev.tipo IN ('EPISODIO_ABERTO', 'ETAPA_ALTERADA')
              UNION ALL
              SELECT 'SETOR',
                     CASE ev.tipo WHEN 'SETOR_ALTERADO' THEN ev.dados ->> 'para' ELSE ev.dados ->> 'setor_id' END, NULL
               WHERE ev.tipo IN ('EPISODIO_ABERTO', 'SETOR_ALTERADO')
              UNION ALL
              SELECT 'BLOQUEIO',
                     CASE ev.tipo WHEN 'BLOQUEIO_DEFINIDO' THEN ev.dados ->> 'motivo' END,
                     CASE ev.tipo WHEN 'BLOQUEIO_DEFINIDO' THEN ev.dados ->> 'categoria' END
               WHERE ev.tipo IN ('BLOQUEIO_DEFINIDO', 'BLOQUEIO_REMOVIDO')
          ) t
         WHERE ev.unidade_id = p_unidade
           AND ev.tipo IN ('EPISODIO_ABERTO', 'ETAPA_ALTERADA', 'SETOR_ALTERADO', 'BLOQUEIO_DEFINIDO', 'BLOQUEIO_REMOVIDO')
    ),
    seg AS (
        SELECT episodio_id, tipo, valor, categoria, ocorrido_em AS ini,
               coalesce(lead(ocorrido_em) OVER w, encerrado_em) AS fim,
               lead(ocorrido_em) OVER w IS NULL AS ultimo,
               CASE WHEN valor IS NOT DISTINCT FROM lag(valor) OVER w AND tipo = 'BLOQUEIO' THEN 0 ELSE 1 END AS novo,
               row_number() OVER w AS ordem
          FROM ev
        WINDOW w AS (PARTITION BY episodio_id, tipo ORDER BY ocorrido_em, registrado_em, id)
    ),
    grp AS (
        SELECT s.*, sum(novo) OVER (PARTITION BY episodio_id, tipo ORDER BY ordem) AS g FROM seg s
    )
    -- etapa e setor: cada evento abre um intervalo
    SELECT episodio_id, tipo, valor, categoria, ini, fim, ultimo FROM grp WHERE tipo <> 'BLOQUEIO'
    UNION ALL
    -- bloqueio: redefinições consecutivas do mesmo motivo = um intervalo (início e categoria do
    -- evento que o abriu); a remoção só delimita
    SELECT episodio_id, 'BLOQUEIO', min(valor), (array_agg(categoria ORDER BY ordem))[1], min(ini),
           CASE WHEN bool_or(fim IS NULL) THEN NULL ELSE max(fim) END, bool_or(ultimo)
      FROM grp
     WHERE tipo = 'BLOQUEIO' AND valor IS NOT NULL
     GROUP BY episodio_id, g
$$;

CREATE OR REPLACE FUNCTION fluxo.rel_qualidade(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz,
                                               p_agora timestamptz, p_setor uuid)
    RETURNS SETOF fluxo.linha_relatorio
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH abertos AS (   -- ESTOQUE no instante de referência: setor ATUAL
        SELECT e.id, e.etapa_id, e.setor_id, e.motivo_bloqueio_id, e.protocolo_numero,
               e.especialidade_requerida_id, e.destino_descricao,
               (SELECT max(ev.registrado_em) FROM fluxo.evento_episodio ev
                 WHERE ev.episodio_id = e.id AND ev.registrado_em <= p_agora) AS ultimo
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.encerrado_em IS NULL AND e.entrada_em <= p_agora
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    -- Cobertura LINHA_DO_TEMPO. População: episódios abertos no instante de referência, que entraram
    -- no período ou que encerraram no período. Filtro de setor: setor ATUAL (abertos) ou FINAL
    -- (encerrados) — é justamente a falta de linha do tempo que impede a atribuição histórica.
    escopo AS (
        SELECT e.id
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.entrada_em <= p_agora
           AND (e.encerrado_em IS NULL OR (e.encerrado_em >= p_inicio AND e.encerrado_em < p_fim)
                OR (e.entrada_em >= p_inicio AND e.entrada_em < p_fim))
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    -- Registros cujo REGISTRO (registrado_em) caiu no período.
    reg AS (
        SELECT ev.id, ev.episodio_id, ev.dados ->> 'ajuste_manual' = 'true' AS retroativo,
               extract(epoch FROM ev.registrado_em - ev.ocorrido_em) / 60.0 AS atraso
          FROM fluxo.evento_episodio ev
         WHERE ev.unidade_id = p_unidade AND ev.registrado_em >= p_inicio AND ev.registrado_em < p_fim
           AND ev.registrado_em <= p_agora
    ),
    -- Com filtro de setor: setor em vigor no INSTANTE DO FATO (ocorrido_em), pela ordem da linha do
    -- tempo (ocorrido_em, registrado_em, id). O próprio evento de transferência pertence ao setor de
    -- destino. Antes do primeiro evento de setor do episódio: NULL (não atribuível).
    lin AS (
        SELECT ev.episodio_id, ev.id, ev.ocorrido_em, ev.registrado_em,
               CASE ev.tipo WHEN 'SETOR_ALTERADO' THEN ev.dados ->> 'para'
                            WHEN 'EPISODIO_ABERTO' THEN ev.dados ->> 'setor_id' END AS setor_ev
          FROM fluxo.evento_episodio ev
         WHERE p_setor IS NOT NULL AND ev.unidade_id = p_unidade
           AND ev.episodio_id IN (SELECT episodio_id FROM reg)
    ),
    marc AS (
        SELECT l.*, count(setor_ev) OVER (PARTITION BY episodio_id ORDER BY ocorrido_em, registrado_em, id) AS g
          FROM lin l
    ),
    atrib AS (
        SELECT id, max(setor_ev) OVER (PARTITION BY episodio_id, g) AS setor_fato FROM marc
    ),
    registros AS (
        SELECT r.retroativo, r.atraso, NULL::text AS setor_fato FROM reg r WHERE p_setor IS NULL
        UNION ALL
        SELECT r.retroativo, r.atraso, a.setor_fato FROM reg r JOIN atrib a ON a.id = r.id WHERE p_setor IS NOT NULL
    ),
    -- Bloqueios INICIADOS no período = inícios dos intervalos normalizados (mesma definição de
    -- Gargalos e Evolução), com a categoria registrada no início.
    iv AS MATERIALIZED (
        SELECT * FROM fluxo.rel_intervalos(p_unidade, p_inicio, greatest(p_fim, p_agora))
         WHERE tipo IN ('BLOQUEIO', 'SETOR')
    ),
    binic AS (
        SELECT b.episodio_id, b.categoria, b.ini FROM iv b
         WHERE b.tipo = 'BLOQUEIO' AND b.ini >= p_inicio AND b.ini < p_fim AND b.ini <= p_agora
    ),
    -- Com filtro de setor: setor em vigor no INÍCIO do bloqueio = o último intervalo de setor iniciado
    -- até esse instante (os intervalos de setor são contíguos; uma transferência no mesmo instante
    -- prevalece, como nos pedaços de Gargalos). Feito por janela, sem junção por faixa (desempenho).
    marcos AS (
        SELECT episodio_id, NULL::text AS categoria, ini, valor AS setor_ev, 0 AS k
          FROM iv WHERE tipo = 'SETOR' AND p_setor IS NOT NULL
        UNION ALL
        SELECT episodio_id, categoria, ini, NULL, 1 FROM binic WHERE p_setor IS NOT NULL
    ),
    marcos_g AS (
        SELECT m.*, count(setor_ev) OVER (PARTITION BY episodio_id ORDER BY ini, k, setor_ev) AS g FROM marcos m
    ),
    marcos_s AS (
        SELECT categoria, k, max(setor_ev) OVER (PARTITION BY episodio_id, g) AS setor_ini FROM marcos_g
    ),
    bloqueios_iniciados AS (
        SELECT b.categoria, NULL::text AS setor_ini FROM binic b WHERE p_setor IS NULL
        UNION ALL
        SELECT categoria, setor_ini FROM marcos_s WHERE k = 1
    ),
    transf AS (
        SELECT a.* FROM abertos a JOIN fluxo.etapa et ON et.id = a.etapa_id
         WHERE et.natureza IN ('ACEITO', 'TRANSPORTE') OR et.exige_protocolo_externo
    )
    SELECT 'ATUALIDADE'::text, NULL::text, NULL::text, NULL::text, count(ultimo), NULL::bigint, count(*), NULL::bigint, NULL::double precision,
           avg(extract(epoch FROM p_agora - ultimo) / 60.0),
           percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM p_agora - ultimo) / 60.0),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM p_agora - ultimo) / 60.0),
           max(extract(epoch FROM p_agora - ultimo) / 60.0)
      FROM abertos
    UNION ALL
    SELECT 'ATUALIDADE_SETOR', s.id::text, s.nome, NULL, count(a.ultimo), NULL, count(*), NULL, NULL,
           avg(extract(epoch FROM p_agora - a.ultimo) / 60.0),
           percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM p_agora - a.ultimo) / 60.0),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM p_agora - a.ultimo) / 60.0),
           max(extract(epoch FROM p_agora - a.ultimo) / 60.0)
      FROM abertos a JOIN fluxo.setor s ON s.id = a.setor_id GROUP BY s.id, s.nome
    UNION ALL
    SELECT 'SEM_ATUALIZACAO_REGRA', r.id::text, r.nome, 'v' || r.versao,
           count(a.id) FILTER (WHERE a.ultimo IS NOT NULL AND p_agora - a.ultimo >= r.limite
                                 AND (r.etapa_id IS NULL OR r.etapa_id = a.etapa_id)),
           NULL, count(a.id) FILTER (WHERE r.etapa_id IS NULL OR r.etapa_id = a.etapa_id), NULL,
           extract(epoch FROM r.limite) / 60.0, NULL, NULL, NULL, NULL
      FROM fluxo.regra_alerta r LEFT JOIN abertos a ON true
     WHERE r.unidade_id = p_unidade AND r.ativa AND r.tipo = 'SEM_ATUALIZACAO'
     GROUP BY r.id, r.nome, r.versao, r.limite
    UNION ALL
    SELECT 'REGISTROS_RETROATIVOS', NULL, NULL, NULL, count(*), count(*) FILTER (WHERE retroativo), count(*), NULL, NULL,
           avg(atraso) FILTER (WHERE retroativo),
           percentile_cont(0.5) WITHIN GROUP (ORDER BY atraso) FILTER (WHERE retroativo),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY atraso) FILTER (WHERE retroativo),
           max(atraso) FILTER (WHERE retroativo)
      FROM registros
     WHERE p_setor IS NULL OR setor_fato = p_setor::text
    UNION ALL
    SELECT 'CAUSA_EM_INVESTIGACAO_AGORA', NULL, NULL, NULL,
           count(*) FILTER (WHERE m.categoria = 'NAO_DEFINIDA'), NULL, count(*), NULL, NULL, NULL, NULL, NULL, NULL
      FROM abertos a JOIN fluxo.motivo_bloqueio m ON m.id = a.motivo_bloqueio_id
    UNION ALL
    SELECT 'BLOQUEIOS_INICIADOS_SEM_CAUSA', NULL, NULL, NULL, count(*) FILTER (WHERE categoria = 'NAO_DEFINIDA'), NULL,
           count(*), NULL, NULL, NULL, NULL, NULL, NULL
      FROM bloqueios_iniciados
     WHERE p_setor IS NULL OR setor_ini = p_setor::text
    UNION ALL
    -- Fatos do período sem setor determinável pela linha do tempo (só com filtro de setor): ficam
    -- FORA do filtro, sinalizados aqui — nunca atribuídos ao setor atual.
    SELECT 'SETOR_NAO_ATRIBUIDO', 'REGISTROS', NULL, NULL, count(*) FILTER (WHERE setor_fato IS NULL), NULL, count(*),
           NULL, NULL, NULL, NULL, NULL, NULL
      FROM registros WHERE p_setor IS NOT NULL
    UNION ALL
    SELECT 'SETOR_NAO_ATRIBUIDO', 'BLOQUEIOS_INICIADOS', NULL, NULL, count(*) FILTER (WHERE setor_ini IS NULL), NULL,
           count(*), NULL, NULL, NULL, NULL, NULL, NULL
      FROM bloqueios_iniciados WHERE p_setor IS NOT NULL
    UNION ALL
    SELECT 'DESTINO_EM_TRANSFERENCIA', NULL, NULL, 'OPCIONAL',
           count(*) FILTER (WHERE especialidade_requerida_id IS NOT NULL OR destino_descricao IS NOT NULL), NULL, count(*),
           NULL, NULL, NULL, NULL, NULL, NULL
      FROM transf
    UNION ALL
    SELECT 'PROTOCOLO_EM_TRANSFERENCIA', NULL, NULL, 'OPCIONAL', count(*) FILTER (WHERE protocolo_numero IS NOT NULL), NULL,
           count(*), NULL, NULL, NULL, NULL, NULL, NULL
      FROM transf
    UNION ALL
    SELECT 'LINHA_DO_TEMPO', NULL, NULL, NULL,
           count(*) FILTER (WHERE EXISTS (SELECT 1 FROM fluxo.evento_episodio ev
                                           WHERE ev.episodio_id = s.id AND ev.tipo = 'EPISODIO_ABERTO')),
           NULL, count(*), NULL, NULL, NULL, NULL, NULL, NULL
      FROM escopo s
$$;
