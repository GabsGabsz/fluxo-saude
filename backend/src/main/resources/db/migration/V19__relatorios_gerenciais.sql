-- =============================================================================
-- V19 — Relatórios gerenciais (extensão aprovada do projeto, issue #9; NÃO é requisito da ERS
-- original) — ver docs/adr/0010-relatorios-gerenciais.md e docs/relatorios.md
--
-- Tudo é agregado no banco, sobre TODOS os registros da unidade (nunca sobre a página da Torre),
-- por funções SECURITY INVOKER (o RLS por unidade vale). A aplicação chama as funções de um
-- relatório dentro de UMA transação REPEATABLE READ somente leitura (mesmo instantâneo).
--
-- Tempos históricos vêm da LINHA DO TEMPO (instante do fato), nunca do estado atual:
--   etapa    = EPISODIO_ABERTO.dados.etapa, depois ETAPA_ALTERADA.dados.para (códigos)
--   setor    = EPISODIO_ABERTO.dados.setor_id, depois SETOR_ALTERADO.dados.para
--   bloqueio = BLOQUEIO_DEFINIDO (motivo e categoria DA ÉPOCA) até o próximo BLOQUEIO_* ou o
--              encerramento; redefinições consecutivas do MESMO motivo formam um único intervalo
-- Ordem dos eventos: ocorrido_em, registrado_em, id (fatos retroativos entram no lugar certo).
-- Assim a espera é atribuída ao setor/etapa em que ocorreu, e não ao setor atual.
-- =============================================================================

-- Linha genérica dos relatórios (formato "longo": a mesma estrutura serve à tela e ao CSV).
CREATE TYPE fluxo.linha_relatorio AS (
    secao       text,              -- código da seção (ver docs/relatorios.md)
    chave       text,              -- código/id do grupo (etapa, setor, categoria, desfecho...) ou NULL (total)
    rotulo      text,              -- nome legível do grupo (configuração da unidade)
    grupo       text,              -- agrupamento secundário (ex.: categoria de um motivo)
    quantidade  bigint,            -- contagem principal (n)
    parte       bigint,            -- subconjunto de "quantidade" ou numerador (ver seção)
    base        bigint,            -- denominador, quando houver
    episodios   bigint,            -- episódios distintos envolvidos
    minutos     double precision,  -- soma de minutos (tempo sobreposto ao período)
    media       double precision,  -- minutos
    mediana     double precision,  -- minutos (percentil 50 contínuo)
    p90         double precision,  -- minutos (percentil 90 contínuo)
    maximo      double precision   -- minutos
);

-- -----------------------------------------------------------------------------
-- Intervalos reconstruídos da linha do tempo dos episódios que existiram em [p_desde, p_ate).
-- tipo: ETAPA (valor = código da etapa), SETOR (valor = id do setor), BLOQUEIO (valor = código do
-- motivo; categoria = categoria registrada no evento). fim NULL = em curso. ultimo = último
-- intervalo do tipo naquele episódio (o instante do encerramento pertence a ele).
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.rel_intervalos(p_unidade uuid, p_desde timestamptz, p_ate timestamptz)
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
    -- bloqueio: redefinições consecutivas do mesmo motivo = um intervalo; remoção só delimita
    SELECT episodio_id, 'BLOQUEIO', min(valor), min(categoria), min(ini),
           CASE WHEN bool_or(fim IS NULL) THEN NULL ELSE max(fim) END, bool_or(ultimo)
      FROM grp
     WHERE tipo = 'BLOQUEIO' AND valor IS NOT NULL
     GROUP BY episodio_id, g
$$;

-- Etapa (código) ou setor (id) de um episódio NUM INSTANTE, pela linha do tempo: o último evento
-- de etapa/setor ocorrido até o instante (empates pela ordem de registro). NULL = sem histórico.
CREATE FUNCTION fluxo.rel_valor_em(p_episodio uuid, p_tipo text, p_instante timestamptz) RETURNS text
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    SELECT CASE
             WHEN p_tipo = 'SETOR' THEN CASE ev.tipo WHEN 'SETOR_ALTERADO' THEN ev.dados ->> 'para' ELSE ev.dados ->> 'setor_id' END
             ELSE CASE ev.tipo WHEN 'ETAPA_ALTERADA' THEN ev.dados ->> 'para' ELSE ev.dados ->> 'etapa' END
           END
      FROM fluxo.evento_episodio ev
     WHERE ev.episodio_id = p_episodio AND ev.ocorrido_em <= p_instante
       AND ev.tipo IN ('EPISODIO_ABERTO', CASE WHEN p_tipo = 'SETOR' THEN 'SETOR_ALTERADO' ELSE 'ETAPA_ALTERADA' END)
     ORDER BY ev.ocorrido_em DESC, ev.registrado_em DESC, ev.id DESC
     LIMIT 1
$$;

-- Minutos de um intervalo de tempo (NULL/vazio = 0).
CREATE FUNCTION fluxo.rel_minutos(p_r tstzrange) RETURNS double precision
    LANGUAGE sql IMMUTABLE
    SET search_path = pg_catalog
AS $$ SELECT CASE WHEN p_r IS NULL OR isempty(p_r) THEN 0
                  ELSE extract(epoch FROM upper(p_r) - lower(p_r)) / 60.0 END $$;

-- -----------------------------------------------------------------------------
-- R1 — Resumo gerencial: eventos do PERÍODO separados do ESTOQUE no instante de referência.
-- Setor: entradas pelo setor de ENTRADA (linha do tempo), encerramentos pelo setor FINAL,
-- estoque pelo setor ATUAL.
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.rel_resumo(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_agora timestamptz,
                                 p_setor uuid)
    RETURNS SETOF fluxo.linha_relatorio
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH entradas AS (
        SELECT e.id, (SELECT ev.dados ->> 'setor_id' FROM fluxo.evento_episodio ev
                       WHERE ev.episodio_id = e.id AND ev.tipo = 'EPISODIO_ABERTO'
                       ORDER BY ev.ocorrido_em, ev.registrado_em, ev.id LIMIT 1) AS setor_entrada
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.entrada_em >= p_inicio AND e.entrada_em < p_fim
    ),
    encerrados AS (
        SELECT e.id, e.desfecho::text AS desfecho
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.encerrado_em >= p_inicio AND e.encerrado_em < p_fim
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    abertos AS (
        SELECT e.id, e.etapa_id, e.setor_id, e.motivo_bloqueio_id
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.encerrado_em IS NULL AND e.entrada_em <= p_agora
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    pend AS (
        SELECT p.id, p.episodio_id, p.prazo < p_agora AS vencida
          FROM fluxo.pendencia p JOIN abertos a ON a.id = p.episodio_id
         WHERE p.status = 'ABERTA'
    )
    SELECT 'ENTRADAS'::text, NULL::text, NULL::text, NULL::text,
           count(*) FILTER (WHERE p_setor IS NULL OR setor_entrada = p_setor::text),
           count(*) FILTER (WHERE setor_entrada IS NULL), NULL::bigint, NULL::bigint, NULL::double precision, NULL::double precision, NULL::double precision, NULL::double precision, NULL::double precision
      FROM entradas
    UNION ALL
    SELECT 'ENCERRAMENTOS', desfecho, NULL, NULL, count(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
      FROM encerrados GROUP BY desfecho
    UNION ALL
    SELECT 'ENCERRAMENTOS_TOTAL', NULL, NULL, NULL, count(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL FROM encerrados
    UNION ALL
    SELECT 'ABERTOS', NULL, NULL, NULL, count(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL FROM abertos
    UNION ALL
    SELECT 'ABERTOS_ETAPA', et.codigo, et.nome, NULL, count(*), NULL, (SELECT count(*) FROM abertos), NULL,
           NULL, NULL, NULL, NULL, NULL
      FROM abertos a JOIN fluxo.etapa et ON et.id = a.etapa_id GROUP BY et.codigo, et.nome, et.ordem
    UNION ALL
    SELECT 'ABERTOS_SETOR', s.id::text, s.nome, NULL, count(*), NULL, (SELECT count(*) FROM abertos), NULL,
           NULL, NULL, NULL, NULL, NULL
      FROM abertos a JOIN fluxo.setor s ON s.id = a.setor_id GROUP BY s.id, s.nome
    UNION ALL
    SELECT 'BLOQUEADOS_AGORA', NULL, NULL, NULL, count(*) FILTER (WHERE motivo_bloqueio_id IS NOT NULL), NULL, count(*),
           NULL, NULL, NULL, NULL, NULL, NULL
      FROM abertos
    UNION ALL
    SELECT 'PENDENCIAS_ABERTAS', NULL, NULL, NULL, count(*), count(*) FILTER (WHERE vencida), NULL,
           count(DISTINCT episodio_id), NULL, NULL, NULL, NULL, NULL
      FROM pend
    UNION ALL
    SELECT 'CASOS_COM_VENCIDA', NULL, NULL, NULL, (SELECT count(DISTINCT episodio_id) FROM pend WHERE vencida), NULL,
           (SELECT count(*) FROM abertos), NULL, NULL, NULL, NULL, NULL, NULL
$$;

-- -----------------------------------------------------------------------------
-- R2 — Gargalos: duração de etapas CONCLUÍDAS no período × idade das etapas EM CURSO (nunca
-- misturadas); tempo por setor; bloqueios por categoria e motivo; pendências abertas por
-- categoria e tipo de responsável. Filtros: setor e etapa são aplicados pela LINHA DO TEMPO
-- (onde o tempo ocorreu); categoria, pelo registro da época.
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.rel_gargalos(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_agora timestamptz,
                                   p_setor uuid, p_etapa uuid, p_categoria text)
    RETURNS SETOF fluxo.linha_relatorio
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH iv AS MATERIALIZED (
        SELECT * FROM fluxo.rel_intervalos(p_unidade, p_inicio, greatest(p_fim, p_agora))
    ),
    periodo AS (SELECT tstzrange(p_inicio, least(p_fim, p_agora), '[)') AS r),
    etapa_filtro AS (SELECT codigo FROM fluxo.etapa WHERE id = p_etapa),
    -- setor/etapa em que cada intervalo COMEÇOU (pela linha do tempo)
    et AS (
        -- (só consultado quando há filtro e o intervalo pode entrar no resultado: concluído no período)
        SELECT i.*,
               CASE WHEN p_setor IS NOT NULL AND i.fim >= p_inicio AND i.fim < p_fim
                    THEN fluxo.rel_valor_em(i.episodio_id, 'SETOR', i.ini) END AS setor_ini,
               CASE WHEN p_etapa IS NOT NULL AND i.tipo = 'BLOQUEIO' AND i.fim >= p_inicio AND i.fim < p_fim
                    THEN fluxo.rel_valor_em(i.episodio_id, 'ETAPA', i.ini) END AS etapa_ini,
               e.encerrado_em IS NULL AS aberto, e.setor_id::text AS setor_atual
          FROM iv i JOIN fluxo.episodio e ON e.id = i.episodio_id
         WHERE i.tipo IN ('ETAPA', 'BLOQUEIO')
    ),
    -- trechos de tempo em que o episódio estava no setor e/ou na etapa filtrados (vazio sem filtro)
    fs AS (SELECT episodio_id, tstzrange(ini, coalesce(fim, p_agora), '[)') AS r
             FROM iv WHERE tipo = 'SETOR' AND valor = p_setor::text),
    fe AS (SELECT episodio_id, tstzrange(ini, coalesce(fim, p_agora), '[)') AS r
             FROM iv WHERE tipo = 'ETAPA' AND valor = (SELECT codigo FROM etapa_filtro)),
    filtro AS (
        SELECT episodio_id, r FROM fs WHERE p_etapa IS NULL
        UNION ALL
        SELECT episodio_id, r FROM fe WHERE p_setor IS NULL
        UNION ALL
        SELECT fs.episodio_id, fs.r * fe.r FROM fs JOIN fe ON fe.episodio_id = fs.episodio_id AND fs.r && fe.r
    ),
    -- pedaços de tempo restritos ao setor/etapa filtrados (interseção exata de intervalos)
    pedaco AS (
        SELECT i.episodio_id, i.tipo, i.valor, i.categoria, i.ini, tstzrange(i.ini, coalesce(i.fim, p_agora), '[)') AS r
          FROM iv i
         WHERE i.tipo IN ('SETOR', 'BLOQUEIO') AND p_setor IS NULL AND p_etapa IS NULL
        UNION ALL
        SELECT i.episodio_id, i.tipo, i.valor, i.categoria, i.ini, tstzrange(i.ini, coalesce(i.fim, p_agora), '[)') * f.r
          FROM iv i JOIN filtro f ON f.episodio_id = i.episodio_id
         WHERE i.tipo IN ('SETOR', 'BLOQUEIO') AND (p_setor IS NOT NULL OR p_etapa IS NOT NULL)
    ),
    conc AS (   -- etapas concluídas no período (fim no período), excluídas as etapas de desfecho
        SELECT t.valor, extract(epoch FROM t.fim - t.ini) / 60.0 AS min, t.episodio_id
          FROM et t JOIN fluxo.etapa e ON e.unidade_id = p_unidade AND e.codigo = t.valor
         WHERE t.tipo = 'ETAPA' AND e.natureza <> 'DESFECHO'
           AND t.fim >= p_inicio AND t.fim < p_fim AND t.fim <= p_agora
           AND (p_setor IS NULL OR t.setor_ini = p_setor::text)
           AND (p_etapa IS NULL OR e.id = p_etapa)
    ),
    curso AS (  -- etapas em curso agora (episódios abertos), idade = agora − início
        SELECT t.valor, extract(epoch FROM p_agora - t.ini) / 60.0 AS min, t.episodio_id
          FROM et t JOIN fluxo.etapa e ON e.unidade_id = p_unidade AND e.codigo = t.valor
         WHERE t.tipo = 'ETAPA' AND t.fim IS NULL AND t.aberto AND e.natureza <> 'DESFECHO' AND t.ini <= p_agora
           AND (p_setor IS NULL OR t.setor_atual = p_setor::text)
           AND (p_etapa IS NULL OR e.id = p_etapa)
    ),
    bconc AS (  -- bloqueios concluídos no período: duração inteira do intervalo
        SELECT t.categoria, extract(epoch FROM t.fim - t.ini) / 60.0 AS min, t.episodio_id
          FROM et t
         WHERE t.tipo = 'BLOQUEIO' AND t.fim >= p_inicio AND t.fim < p_fim AND t.fim <= p_agora
           AND (p_setor IS NULL OR t.setor_ini = p_setor::text)
           AND (p_etapa IS NULL OR t.etapa_ini = (SELECT codigo FROM etapa_filtro))
           AND (p_categoria IS NULL OR t.categoria = p_categoria)
    ),
    bcurso AS ( -- bloqueios em curso agora: idade
        SELECT t.categoria, extract(epoch FROM p_agora - t.ini) / 60.0 AS min, t.episodio_id
          FROM et t JOIN fluxo.episodio e ON e.id = t.episodio_id
         WHERE t.tipo = 'BLOQUEIO' AND t.fim IS NULL AND t.aberto AND t.ini <= p_agora
           AND (p_setor IS NULL OR t.setor_atual = p_setor::text)
           AND (p_etapa IS NULL OR e.etapa_id = p_etapa)
           AND (p_categoria IS NULL OR t.categoria = p_categoria)
    ),
    bper AS (   -- tempo bloqueado SOBREPOSTO ao período (no setor/etapa filtrados), por intervalo
        SELECT p.episodio_id, p.valor, p.categoria, p.ini,
               sum(fluxo.rel_minutos(p.r * (SELECT r FROM periodo))) AS min,
               bool_or(p.ini >= p_inicio AND p.ini < p_fim AND p.r @> p.ini) AS iniciado
          FROM pedaco p
         WHERE p.tipo = 'BLOQUEIO' AND (p_categoria IS NULL OR p.categoria = p_categoria)
         GROUP BY p.episodio_id, p.valor, p.categoria, p.ini
    ),
    sper AS (   -- tempo em cada setor sobreposto ao período (na etapa filtrada)
        SELECT p.valor, p.episodio_id, sum(fluxo.rel_minutos(p.r * (SELECT r FROM periodo))) AS min
          FROM pedaco p
         WHERE p.tipo = 'SETOR' AND (p_setor IS NULL OR p.valor = p_setor::text)
         GROUP BY p.valor, p.episodio_id, p.ini
    ),
    pend AS (
        SELECT p.categoria::text AS categoria,
               CASE WHEN p.responsavel_usuario_id IS NOT NULL THEN 'USUARIO'
                    WHEN p.responsavel_setor_id IS NOT NULL THEN 'SETOR' ELSE 'PERFIL' END AS tipo_resp,
               p.prazo < p_agora AS vencida, extract(epoch FROM p_agora - p.criada_em) / 60.0 AS idade, p.episodio_id
          FROM fluxo.pendencia p JOIN fluxo.episodio e ON e.id = p.episodio_id
         WHERE p.unidade_id = p_unidade AND p.status = 'ABERTA' AND e.encerrado_em IS NULL AND p.criada_em <= p_agora
           AND (p_setor IS NULL OR e.setor_id = p_setor)
           AND (p_etapa IS NULL OR e.etapa_id = p_etapa)
           AND (p_categoria IS NULL OR p.categoria::text = p_categoria)
    )
    SELECT 'ETAPA_CONCLUIDA'::text, c.valor, e.nome, NULL::text, count(*), NULL::bigint, NULL::bigint, count(DISTINCT c.episodio_id), sum(c.min),
           avg(c.min), percentile_cont(0.5) WITHIN GROUP (ORDER BY c.min),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY c.min), max(c.min)
      FROM conc c JOIN fluxo.etapa e ON e.unidade_id = p_unidade AND e.codigo = c.valor
     GROUP BY c.valor, e.nome, e.ordem
    UNION ALL
    SELECT 'ETAPA_EM_CURSO', c.valor, e.nome, NULL, count(*), NULL, NULL, count(DISTINCT c.episodio_id), NULL,
           avg(c.min), percentile_cont(0.5) WITHIN GROUP (ORDER BY c.min),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY c.min), max(c.min)
      FROM curso c JOIN fluxo.etapa e ON e.unidade_id = p_unidade AND e.codigo = c.valor
     GROUP BY c.valor, e.nome, e.ordem
    UNION ALL
    SELECT 'SETOR_TEMPO', s.valor, st.nome, NULL, count(*) FILTER (WHERE s.min > 0), NULL, NULL,
           count(DISTINCT s.episodio_id) FILTER (WHERE s.min > 0), sum(s.min), NULL, NULL, NULL, NULL
      FROM sper s LEFT JOIN fluxo.setor st ON st.id::text = s.valor
     GROUP BY s.valor, st.nome
    HAVING sum(s.min) > 0
    UNION ALL
    SELECT 'BLOQUEIO_CATEGORIA', b.categoria, NULL, NULL, count(*) FILTER (WHERE b.min > 0), count(*) FILTER (WHERE b.iniciado),
           NULL, count(DISTINCT b.episodio_id) FILTER (WHERE b.min > 0), sum(b.min), NULL, NULL, NULL, NULL
      FROM bper b GROUP BY b.categoria
    HAVING sum(b.min) > 0 OR count(*) FILTER (WHERE b.iniciado) > 0
    UNION ALL
    SELECT 'BLOQUEIO_MOTIVO', b.valor, m.descricao, b.categoria, count(*) FILTER (WHERE b.min > 0),
           count(*) FILTER (WHERE b.iniciado), NULL, count(DISTINCT b.episodio_id) FILTER (WHERE b.min > 0), sum(b.min),
           NULL, NULL, NULL, NULL
      FROM bper b LEFT JOIN fluxo.motivo_bloqueio m ON m.unidade_id = p_unidade AND m.codigo = b.valor
     GROUP BY b.valor, m.descricao, b.categoria
    HAVING sum(b.min) > 0 OR count(*) FILTER (WHERE b.iniciado) > 0
    UNION ALL
    SELECT 'BLOQUEIO_CONCLUIDO', b.categoria, NULL, NULL, count(*), NULL, NULL, count(DISTINCT b.episodio_id), sum(b.min),
           avg(b.min), percentile_cont(0.5) WITHIN GROUP (ORDER BY b.min),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY b.min), max(b.min)
      FROM bconc b GROUP BY b.categoria
    UNION ALL
    SELECT 'BLOQUEIO_EM_CURSO', b.categoria, NULL, NULL, count(*), NULL, NULL, count(DISTINCT b.episodio_id), NULL,
           avg(b.min), percentile_cont(0.5) WITHIN GROUP (ORDER BY b.min),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY b.min), max(b.min)
      FROM bcurso b GROUP BY b.categoria
    UNION ALL
    SELECT 'PENDENCIA_CATEGORIA', p.categoria, NULL, NULL, count(*), count(*) FILTER (WHERE p.vencida), NULL,
           count(DISTINCT p.episodio_id), NULL, avg(p.idade), percentile_cont(0.5) WITHIN GROUP (ORDER BY p.idade),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY p.idade), max(p.idade)
      FROM pend p GROUP BY p.categoria
    UNION ALL
    SELECT 'PENDENCIA_RESPONSAVEL', p.tipo_resp, NULL, NULL, count(*), count(*) FILTER (WHERE p.vencida), NULL,
           count(DISTINCT p.episodio_id), NULL, avg(p.idade), percentile_cont(0.5) WITHIN GROUP (ORDER BY p.idade),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY p.idade), max(p.idade)
      FROM pend p GROUP BY p.tipo_resp
$$;

-- -----------------------------------------------------------------------------
-- R3 — Pendências: criadas e encerradas no período (pelo setor do episódio no instante do
-- fato, pela linha do tempo), abertas agora (setor atual). "No prazo" usa o ÚLTIMO prazo
-- registrado (alterações de prazo ficam no histórico de eventos e são contadas à parte).
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.rel_pendencias(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_agora timestamptz,
                                     p_setor uuid, p_categoria text)
    RETURNS SETOF fluxo.linha_relatorio
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH base AS (
        SELECT p.*, e.setor_id AS setor_atual, e.encerrado_em AS ep_encerrado,
               fluxo.rel_valor_em(p.episodio_id, 'SETOR', p.criada_em) AS setor_criacao,
               CASE WHEN p.encerrada_em IS NOT NULL
                    THEN fluxo.rel_valor_em(p.episodio_id, 'SETOR', p.encerrada_em) END AS setor_encerramento,
               EXISTS (SELECT 1 FROM fluxo.evento_episodio ev
                        WHERE ev.episodio_id = p.episodio_id AND ev.tipo = 'PENDENCIA_ATUALIZADA'
                          AND ev.dados ->> 'pendencia_id' = p.id::text AND ev.dados ->> 'campo' = 'prazo') AS prazo_alterado
          FROM fluxo.pendencia p JOIN fluxo.episodio e ON e.id = p.episodio_id
         WHERE p.unidade_id = p_unidade
           AND (p_categoria IS NULL OR p.categoria::text = p_categoria)
           AND ((p.criada_em >= p_inicio AND p.criada_em < p_fim)
                OR (p.encerrada_em >= p_inicio AND p.encerrada_em < p_fim)
                OR (p.status = 'ABERTA' AND e.encerrado_em IS NULL))
    ),
    criadas AS (
        SELECT * FROM base WHERE criada_em >= p_inicio AND criada_em < p_fim
                             AND (p_setor IS NULL OR setor_criacao = p_setor::text)
    ),
    encerradas AS (
        SELECT b.*, extract(epoch FROM encerrada_em - criada_em) / 60.0 AS min, encerrada_em <= prazo AS no_prazo
          FROM base b WHERE encerrada_em >= p_inicio AND encerrada_em < p_fim AND encerrada_em <= p_agora
                        AND (p_setor IS NULL OR setor_encerramento = p_setor::text)
    ),
    abertas AS (
        SELECT b.*, extract(epoch FROM p_agora - criada_em) / 60.0 AS idade, prazo < p_agora AS vencida,
               CASE WHEN responsavel_usuario_id IS NOT NULL THEN 'USUARIO'
                    WHEN responsavel_setor_id IS NOT NULL THEN 'SETOR' ELSE 'PERFIL' END AS tipo_resp
          FROM base b WHERE status = 'ABERTA' AND ep_encerrado IS NULL AND criada_em <= p_agora
                        AND (p_setor IS NULL OR setor_atual = p_setor)
    )
    SELECT 'CRIADAS'::text, categoria::text, NULL::text, NULL::text, count(*), NULL::bigint, NULL::bigint, count(DISTINCT episodio_id),
           NULL::double precision, NULL::double precision, NULL::double precision, NULL::double precision, NULL::double precision
      FROM criadas GROUP BY categoria
    UNION ALL
    SELECT 'CRIADAS_TOTAL', NULL, NULL, NULL, count(*), count(*) FILTER (WHERE prazo_alterado), NULL,
           count(DISTINCT episodio_id), NULL, NULL, NULL, NULL, NULL
      FROM criadas
    UNION ALL
    SELECT 'ENCERRADAS', status::text, NULL, NULL, count(*), count(*) FILTER (WHERE no_prazo), count(*),
           count(DISTINCT episodio_id), NULL, avg(min), percentile_cont(0.5) WITHIN GROUP (ORDER BY min),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY min), max(min)
      FROM encerradas GROUP BY status
    UNION ALL
    SELECT 'ENCERRADAS_CATEGORIA', categoria::text, NULL, NULL, count(*), count(*) FILTER (WHERE no_prazo), count(*),
           count(DISTINCT episodio_id), NULL, avg(min), percentile_cont(0.5) WITHIN GROUP (ORDER BY min),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY min), max(min)
      FROM encerradas GROUP BY categoria
    UNION ALL
    SELECT 'ABERTAS', categoria::text, NULL, NULL, count(*), count(*) FILTER (WHERE vencida), NULL,
           count(DISTINCT episodio_id), NULL, avg(idade), percentile_cont(0.5) WITHIN GROUP (ORDER BY idade),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY idade), max(idade)
      FROM abertas GROUP BY categoria
    UNION ALL
    SELECT 'ABERTAS_TOTAL', NULL, NULL, NULL, count(*), count(*) FILTER (WHERE vencida), NULL,
           count(DISTINCT episodio_id), NULL, avg(idade), percentile_cont(0.5) WITHIN GROUP (ORDER BY idade),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY idade), max(idade)
      FROM abertas
    UNION ALL
    SELECT 'ABERTAS_CRITICIDADE', criticidade_operacional::text, NULL, NULL, count(*), count(*) FILTER (WHERE vencida),
           NULL, count(DISTINCT episodio_id), NULL, NULL, NULL, NULL, NULL
      FROM abertas GROUP BY criticidade_operacional
    UNION ALL
    SELECT 'ABERTAS_RESPONSAVEL', tipo_resp, NULL, NULL, count(*), count(*) FILTER (WHERE vencida), NULL,
           count(DISTINCT episodio_id), NULL, NULL, NULL, NULL, NULL
      FROM abertas GROUP BY tipo_resp
    UNION ALL
    SELECT 'VENCIDAS_ATRASO', NULL, NULL, NULL, count(*), NULL, NULL, count(DISTINCT episodio_id), NULL,
           avg(extract(epoch FROM p_agora - prazo) / 60.0),
           percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM p_agora - prazo) / 60.0),
           percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM p_agora - prazo) / 60.0),
           max(extract(epoch FROM p_agora - prazo) / 60.0)
      FROM abertas WHERE vencida
$$;

-- Lista OPERACIONAL nominal das pendências abertas (só para perfis com acesso nominal; a
-- aplicação confere a permissão e o limite — nunca uma lista parcial).
CREATE FUNCTION fluxo.rel_pendencias_lista(p_unidade uuid, p_agora timestamptz, p_setor uuid, p_categoria text,
                                           p_limite integer)
    RETURNS TABLE (pendencia_id uuid, episodio_id uuid, paciente_nome text, setor_nome text, etapa_nome text,
                   motivo_descricao text, descricao text, categoria text, criticidade text, responsavel_tipo text,
                   responsavel_nome text, prazo timestamptz, criada_em timestamptz, vencida boolean)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    SELECT p.id, e.id, pa.nome, s.nome, et.nome, m.descricao, p.descricao, p.categoria::text,
           p.criticidade_operacional::text,
           CASE WHEN p.responsavel_usuario_id IS NOT NULL THEN 'USUARIO'
                WHEN p.responsavel_setor_id IS NOT NULL THEN 'SETOR' ELSE 'PERFIL' END,
           coalesce(u.nome, rs.nome, p.responsavel_papel::text), p.prazo, p.criada_em, p.prazo < p_agora
      FROM fluxo.pendencia p
      JOIN fluxo.episodio e ON e.id = p.episodio_id
      JOIN fluxo.paciente pa ON pa.id = e.paciente_id
      JOIN fluxo.setor s ON s.id = e.setor_id
      JOIN fluxo.etapa et ON et.id = e.etapa_id
      LEFT JOIN fluxo.motivo_bloqueio m ON m.id = e.motivo_bloqueio_id
      LEFT JOIN fluxo.usuario u ON u.id = p.responsavel_usuario_id
      LEFT JOIN fluxo.setor rs ON rs.id = p.responsavel_setor_id
     WHERE p.unidade_id = p_unidade AND p.status = 'ABERTA' AND e.encerrado_em IS NULL AND p.criada_em <= p_agora
       AND (p_setor IS NULL OR e.setor_id = p_setor)
       AND (p_categoria IS NULL OR p.categoria::text = p_categoria)
     ORDER BY p.prazo, p.id
     LIMIT p_limite
$$;

-- -----------------------------------------------------------------------------
-- R4 — Métricas de UM período, para a comparação entre períodos de mesma duração (a aplicação
-- chama duas vezes no mesmo instantâneo). Só fatos do período; alertas NÃO entram (não há
-- histórico de alertas e eles não são reconstruídos com as regras atuais).
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.rel_metricas_periodo(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz,
                                           p_agora timestamptz, p_setor uuid)
    RETURNS SETOF fluxo.linha_relatorio
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH iv AS MATERIALIZED (
        SELECT * FROM fluxo.rel_intervalos(p_unidade, p_inicio, p_fim)
    ),
    periodo AS (SELECT tstzrange(p_inicio, least(p_fim, p_agora), '[)') AS r),
    entradas AS (
        SELECT e.id FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.entrada_em >= p_inicio AND e.entrada_em < p_fim
           AND (p_setor IS NULL OR EXISTS (
                SELECT 1 FROM fluxo.evento_episodio ev
                 WHERE ev.episodio_id = e.id AND ev.tipo = 'EPISODIO_ABERTO' AND ev.dados ->> 'setor_id' = p_setor::text))
    ),
    encerrados AS (
        SELECT e.desfecho::text AS desfecho, extract(epoch FROM e.encerrado_em - e.entrada_em) / 60.0 AS min
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.encerrado_em >= p_inicio AND e.encerrado_em < p_fim
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    pend AS (
        SELECT p.*, fluxo.rel_valor_em(p.episodio_id, 'SETOR', p.criada_em) AS setor_criacao,
               CASE WHEN p.encerrada_em IS NOT NULL
                    THEN fluxo.rel_valor_em(p.episodio_id, 'SETOR', p.encerrada_em) END AS setor_encerramento
          FROM fluxo.pendencia p
         WHERE p.unidade_id = p_unidade
           AND ((p.criada_em >= p_inicio AND p.criada_em < p_fim) OR (p.encerrada_em >= p_inicio AND p.encerrada_em < p_fim))
    ),
    fs AS (SELECT episodio_id, tstzrange(ini, coalesce(fim, p_agora), '[)') AS r
             FROM iv WHERE tipo = 'SETOR' AND valor = p_setor::text),
    pedaco AS (
        SELECT b.episodio_id, b.categoria, b.ini, tstzrange(b.ini, coalesce(b.fim, p_agora), '[)') AS r
          FROM iv b WHERE b.tipo = 'BLOQUEIO' AND p_setor IS NULL
        UNION ALL
        SELECT b.episodio_id, b.categoria, b.ini, tstzrange(b.ini, coalesce(b.fim, p_agora), '[)') * f.r
          FROM iv b JOIN fs f ON f.episodio_id = b.episodio_id
         WHERE b.tipo = 'BLOQUEIO' AND p_setor IS NOT NULL
    ),
    bloq AS (
        SELECT p.categoria, p.ini, p.episodio_id, sum(fluxo.rel_minutos(p.r * (SELECT r FROM periodo))) AS min,
               bool_or(p.ini >= p_inicio AND p.ini < p_fim AND p.r @> p.ini) AS iniciado
          FROM pedaco p
         GROUP BY p.episodio_id, p.categoria, p.ini
    )
    SELECT 'ENTRADAS'::text, NULL::text, NULL::text, NULL::text, (SELECT count(*) FROM entradas), NULL::bigint, NULL::bigint, NULL::bigint, NULL::double precision, NULL::double precision, NULL::double precision, NULL::double precision, NULL::double precision
    UNION ALL
    SELECT 'ENCERRAMENTOS', NULL, NULL, NULL, count(*), count(*) FILTER (WHERE desfecho = 'TRANSFERENCIA'), NULL,
           NULL, NULL, NULL, NULL, NULL, NULL
      FROM encerrados
    UNION ALL
    SELECT 'PERMANENCIA', NULL, NULL, NULL, count(*), NULL, NULL, NULL, NULL, avg(min),
           percentile_cont(0.5) WITHIN GROUP (ORDER BY min), percentile_cont(0.9) WITHIN GROUP (ORDER BY min), max(min)
      FROM encerrados WHERE desfecho <> 'ENCERRAMENTO_ADMINISTRATIVO'
    UNION ALL
    SELECT 'PENDENCIAS_CRIADAS', NULL, NULL, NULL,
           count(*) FILTER (WHERE criada_em >= p_inicio AND criada_em < p_fim
                              AND (p_setor IS NULL OR setor_criacao = p_setor::text)),
           NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
      FROM pend
    UNION ALL
    SELECT 'PENDENCIAS_ENCERRADAS', NULL, NULL, NULL, count(*), count(*) FILTER (WHERE encerrada_em <= prazo), count(*),
           NULL, NULL, NULL, NULL, NULL, NULL
      FROM pend
     WHERE encerrada_em >= p_inicio AND encerrada_em < p_fim AND encerrada_em <= p_agora
       AND (p_setor IS NULL OR setor_encerramento = p_setor::text)
    UNION ALL
    SELECT 'BLOQUEIO_MINUTOS', NULL, NULL, NULL, count(*) FILTER (WHERE min > 0), count(*) FILTER (WHERE iniciado), NULL,
           count(DISTINCT episodio_id) FILTER (WHERE min > 0), coalesce(sum(min), 0), NULL, NULL, NULL, NULL
      FROM bloq
    UNION ALL
    SELECT 'BLOQUEIO_MINUTOS_CATEGORIA', categoria, NULL, NULL, count(*) FILTER (WHERE min > 0),
           count(*) FILTER (WHERE iniciado), NULL, count(DISTINCT episodio_id) FILTER (WHERE min > 0), sum(min),
           NULL, NULL, NULL, NULL
      FROM bloq GROUP BY categoria
    HAVING sum(min) > 0 OR count(*) FILTER (WHERE iniciado) > 0
$$;

-- Alterações de regras de alerta registradas no histórico de versões (V18) num intervalo, e
-- desde quando há histórico: limitação mostrada na comparação entre períodos.
CREATE FUNCTION fluxo.rel_mudancas_regras(p_unidade uuid, p_desde timestamptz, p_ate timestamptz,
                                          OUT alteracoes bigint, OUT historico_desde timestamptz)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    SELECT count(*) FILTER (WHERE v.vigente_desde >= p_desde AND v.vigente_desde < p_ate),
           min(v.vigente_desde)
      FROM fluxo.regra_alerta_versao v
     WHERE v.unidade_id = p_unidade
$$;

-- -----------------------------------------------------------------------------
-- R5 — Qualidade e atualidade dos registros: só fatos verificáveis. Nenhum prazo oficial de
-- desatualização é presumido: contagens "além do limite" usam apenas regras SEM_ATUALIZACAO
-- configuradas. Campos opcionais aparecem como COBERTURA (grupo OPCIONAL), não como falha.
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.rel_qualidade(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_agora timestamptz,
                                    p_setor uuid)
    RETURNS SETOF fluxo.linha_relatorio
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH abertos AS (
        SELECT e.id, e.etapa_id, e.setor_id, e.motivo_bloqueio_id, e.protocolo_numero,
               e.especialidade_requerida_id, e.destino_descricao,
               (SELECT max(ev.registrado_em) FROM fluxo.evento_episodio ev
                 WHERE ev.episodio_id = e.id AND ev.registrado_em <= p_agora) AS ultimo
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.encerrado_em IS NULL AND e.entrada_em <= p_agora
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    escopo AS (   -- episódios abertos agora, que entraram ou que encerraram no período
        SELECT e.id
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.entrada_em <= p_agora
           AND (e.encerrado_em IS NULL OR (e.encerrado_em >= p_inicio AND e.encerrado_em < p_fim)
                OR (e.entrada_em >= p_inicio AND e.entrada_em < p_fim))
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    registros AS (
        SELECT ev.dados ->> 'ajuste_manual' = 'true' AS retroativo,
               extract(epoch FROM ev.registrado_em - ev.ocorrido_em) / 60.0 AS atraso
          FROM fluxo.evento_episodio ev JOIN fluxo.episodio e ON e.id = ev.episodio_id
         WHERE ev.unidade_id = p_unidade AND ev.registrado_em >= p_inicio AND ev.registrado_em < p_fim
           AND ev.registrado_em <= p_agora
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    bloqueios_iniciados AS (
        SELECT ev.dados ->> 'categoria' AS categoria
          FROM fluxo.evento_episodio ev JOIN fluxo.episodio e ON e.id = ev.episodio_id
         WHERE ev.unidade_id = p_unidade AND ev.tipo = 'BLOQUEIO_DEFINIDO'
           AND ev.ocorrido_em >= p_inicio AND ev.ocorrido_em < p_fim
           AND (p_setor IS NULL OR e.setor_id = p_setor)
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
    UNION ALL
    SELECT 'CAUSA_EM_INVESTIGACAO_AGORA', NULL, NULL, NULL,
           count(*) FILTER (WHERE m.categoria = 'NAO_DEFINIDA'), NULL, count(*), NULL, NULL, NULL, NULL, NULL, NULL
      FROM abertos a JOIN fluxo.motivo_bloqueio m ON m.id = a.motivo_bloqueio_id
    UNION ALL
    SELECT 'BLOQUEIOS_INICIADOS_SEM_CAUSA', NULL, NULL, NULL, count(*) FILTER (WHERE categoria = 'NAO_DEFINIDA'), NULL,
           count(*), NULL, NULL, NULL, NULL, NULL, NULL
      FROM bloqueios_iniciados
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

REVOKE ALL ON FUNCTION fluxo.rel_intervalos(uuid, timestamptz, timestamptz), fluxo.rel_minutos(tstzrange),
    fluxo.rel_valor_em(uuid, text, timestamptz),
    fluxo.rel_resumo(uuid, timestamptz, timestamptz, timestamptz, uuid),
    fluxo.rel_gargalos(uuid, timestamptz, timestamptz, timestamptz, uuid, uuid, text),
    fluxo.rel_pendencias(uuid, timestamptz, timestamptz, timestamptz, uuid, text),
    fluxo.rel_pendencias_lista(uuid, timestamptz, uuid, text, integer),
    fluxo.rel_metricas_periodo(uuid, timestamptz, timestamptz, timestamptz, uuid),
    fluxo.rel_mudancas_regras(uuid, timestamptz, timestamptz),
    fluxo.rel_qualidade(uuid, timestamptz, timestamptz, timestamptz, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.rel_intervalos(uuid, timestamptz, timestamptz), fluxo.rel_minutos(tstzrange),
    fluxo.rel_valor_em(uuid, text, timestamptz),
    fluxo.rel_resumo(uuid, timestamptz, timestamptz, timestamptz, uuid),
    fluxo.rel_gargalos(uuid, timestamptz, timestamptz, timestamptz, uuid, uuid, text),
    fluxo.rel_pendencias(uuid, timestamptz, timestamptz, timestamptz, uuid, text),
    fluxo.rel_pendencias_lista(uuid, timestamptz, uuid, text, integer),
    fluxo.rel_metricas_periodo(uuid, timestamptz, timestamptz, timestamptz, uuid),
    fluxo.rel_mudancas_regras(uuid, timestamptz, timestamptz),
    fluxo.rel_qualidade(uuid, timestamptz, timestamptz, timestamptz, uuid) TO ${app_role};
GRANT USAGE ON TYPE fluxo.linha_relatorio TO ${app_role};
