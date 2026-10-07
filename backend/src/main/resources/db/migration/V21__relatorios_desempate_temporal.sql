-- =============================================================================
-- V21 — Relatórios gerenciais: desempate temporal do setor no início do bloqueio (revisão do PR #11)
-- (extensão aprovada, issue #9; V19 e V20 não são reescritas — a função é substituída com a mesma
-- assinatura e as mesmas permissões)
--
-- Em rel_qualidade (V20), o setor vigente no início de cada bloqueio era obtido por uma janela
-- ordenada por (ini, k, setor_ev). Com duas transferências no MESMO ocorrido_em, o empate caía no
-- UUID textual do setor, e não na ordem da linha do tempo (ocorrido_em → registrado_em → id): um
-- bloqueio podia ser atribuído ao setor substituído. Agora os intervalos de setor vazios (substituídos
-- no mesmo instante, pela ordem já aplicada em rel_intervalos) são descartados e a janela ordena só
-- por (ini, k). A atribuição dos REGISTROS (CTEs lin/marc/atrib) já usava a ordem completa e não muda.
-- =============================================================================

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
    -- Com filtro de setor: setor em vigor no INÍCIO do bloqueio = o intervalo de setor que contém
    -- esse instante, como nos pedaços de Gargalos/Evolução (tstzrange(ini, fim) @> início).
    -- rel_intervalos já ordena os eventos de setor por (ocorrido_em, registrado_em, id): quando duas
    -- transferências têm o mesmo ocorrido_em, a que veio ANTES nessa ordem gera um intervalo VAZIO
    -- (ini = fim) — foi substituída no mesmo instante e não vigorou. Esses intervalos vazios são
    -- descartados; os restantes têm inícios estritamente crescentes, então a ordem (ini, k) é total e
    -- NADA desempata pelo identificador do setor (V20 usava o UUID do setor, revisão do PR #11).
    -- k = 0 (setor) antes de k = 1 (bloqueio): transferência no mesmo instante do início do bloqueio
    -- prevalece. Feito por janela, sem junção por faixa (desempenho).
    marcos AS (
        SELECT episodio_id, NULL::text AS categoria, ini, valor AS setor_ev, 0 AS k
          FROM iv WHERE tipo = 'SETOR' AND p_setor IS NOT NULL AND (fim IS NULL OR fim > ini)
        UNION ALL
        SELECT episodio_id, categoria, ini, NULL, 1 FROM binic WHERE p_setor IS NOT NULL
    ),
    marcos_g AS (
        SELECT m.*, count(setor_ev) OVER (PARTITION BY episodio_id ORDER BY ini, k) AS g FROM marcos m
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
