-- V20 (revisão do PR #11): Qualidade atribui fatos ao setor DA ÉPOCA e conta bloqueios pelos
-- intervalos normalizados — a mesma definição de Gargalos e Evolução. Regressões da V19:
--   * registros e bloqueios iniciados eram filtrados pelo setor ATUAL/FINAL do episódio;
--   * cada BLOQUEIO_DEFINIDO contava como bloqueio iniciado (inclusive "só mudou o detalhe");
--   * a categoria de um intervalo era a menor (alfabética) entre as redefinições, não a do início.
-- Valores calculados à mão. Base B = hora cheia de 40 h atrás; período P = [B+10h, B+30h);
-- referência = B+38h. Registros (feitos "agora" pelo banco) usam P2 = [N−1h, N+1h), N = início.
-- Tudo é medido como DIFERENÇA em relação ao estado antes da carga (fixtures compartilhadas).
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set S1 '''00000000-0000-0000-0000-0000000005a1'''
\set S2 '''00000000-0000-0000-0000-0000000005a2'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set AJ '''{"ajuste_manual": true, "ajuste_justificativa": "carga de teste"}'''
\set Q1 '''77777777-0000-0000-0000-000000000001'''
\set Q2 '''77777777-0000-0000-0000-000000000002'''
\set Q3 '''77777777-0000-0000-0000-000000000003'''
\set Q4 '''77777777-0000-0000-0000-000000000004'''
\set Q5 '''77777777-0000-0000-0000-000000000005'''

SELECT date_trunc('hour', now()) - interval '40 hours' AS base, now() AS n \gset
SELECT (:'base'::timestamptz + interval '10 hours') AS p_ini, (:'base'::timestamptz + interval '30 hours') AS p_fim,
       (:'base'::timestamptz + interval '38 hours') AS agora,
       (:'n'::timestamptz - interval '1 hour') AS p2_ini, (:'n'::timestamptz + interval '1 hour') AS p2_fim \gset

SELECT teste.ctx(:ENF, :A);

-- Valor de uma seção/chave do relatório de qualidade (quantidade/base) num recorte.
CREATE TEMP TABLE q (recorte text, secao text, chave text, quantidade bigint, parte bigint, base bigint);
CREATE FUNCTION pg_temp.medir(p_recorte text, p_ini timestamptz, p_fim timestamptz, p_agora timestamptz, p_setor uuid)
    RETURNS void LANGUAGE sql AS $$
    INSERT INTO q SELECT p_recorte, secao, coalesce(chave, ''), quantidade, parte, base
      FROM fluxo.rel_qualidade('00000000-0000-0000-0000-00000000000a', p_ini, p_fim, p_agora, p_setor) $$;
CREATE FUNCTION pg_temp.v(p_recorte text, p_secao text, p_chave text DEFAULT '') RETURNS text
    LANGUAGE sql AS $$ SELECT coalesce(max(quantidade), 0) || '/' || coalesce(max(base), 0)
                         FROM q WHERE recorte = p_recorte AND secao = p_secao AND chave = p_chave $$;
-- diferença "depois − antes" da parte (ex.: registros retroativos)
CREATE FUNCTION pg_temp.dp(p_antes text, p_depois text, p_secao text, p_chave text DEFAULT '') RETURNS bigint
    LANGUAGE sql AS $$
    SELECT coalesce((SELECT max(parte) FROM q WHERE recorte = p_depois AND secao = p_secao AND chave = p_chave), 0)
         - coalesce((SELECT max(parte) FROM q WHERE recorte = p_antes AND secao = p_secao AND chave = p_chave), 0) $$;
-- diferença "depois − antes" de quantidade/base
CREATE FUNCTION pg_temp.d(p_antes text, p_depois text, p_secao text, p_chave text DEFAULT '') RETURNS text
    LANGUAGE sql AS $$
    SELECT (split_part(pg_temp.v(p_depois, p_secao, p_chave), '/', 1)::bigint
            - split_part(pg_temp.v(p_antes, p_secao, p_chave), '/', 1)::bigint) || '/' ||
           (split_part(pg_temp.v(p_depois, p_secao, p_chave), '/', 2)::bigint
            - split_part(pg_temp.v(p_antes, p_secao, p_chave), '/', 2)::bigint) $$;

-- ------------------------------------------------------------------ antes da carga
SELECT pg_temp.medir('P0', :'p_ini', :'p_fim', :'agora', NULL);
SELECT pg_temp.medir('P0_S1', :'p_ini', :'p_fim', :'agora', :S1);
SELECT pg_temp.medir('P0_S2', :'p_ini', :'p_fim', :'agora', :S2);
SELECT pg_temp.medir('R0', :'p2_ini', :'p2_fim', :'p2_fim', NULL);
SELECT pg_temp.medir('R0_S1', :'p2_ini', :'p2_fim', :'p2_fim', :S1);
SELECT pg_temp.medir('R0_S2', :'p2_ini', :'p2_fim', :'p2_fim', :S2);
CREATE TEMP TABLE g0 AS SELECT * FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', NULL, NULL, NULL);
CREATE TEMP TABLE g0s2 AS SELECT * FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', :S2, NULL, NULL);
CREATE TEMP TABLE m0 AS SELECT * FROM fluxo.rel_metricas_periodo(:A, :'p_ini', :'p_fim', :'agora', NULL);

-- ------------------------------------------------------------------ carga
INSERT INTO fluxo.paciente (id, unidade_id, nome)
SELECT ('99999999-0000-0000-0000-0000000001' || lpad(n::text, 2, '0'))::uuid, :A, 'Paciente Qualidade ' || n
  FROM generate_series(1, 5) n;
CREATE TEMP TABLE ev (ep uuid, tipo text, h interval, dados jsonb);

-- Q1 (aberto; hoje em S2, sem bloqueio): entra B+8h em S1; causa não definida B+12h (S1); detalhe
-- alterado B+13h (MESMO motivo: não é novo bloqueio); transferido para S2 B+14h; detalhe alterado
-- B+16h (S2); removido B+18h; NOVO bloqueio do mesmo motivo B+20h (S2); removido B+22h.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:Q1, :A, '99999999-0000-0000-0000-000000000101', :S2, :'base'::timestamptz + interval '8 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '22 hours');
INSERT INTO ev VALUES
 (:Q1, 'EPISODIO_ABERTO', '8 hours', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S1)),
 (:Q1, 'ETAPA_ALTERADA', '12 hours', '{"de": "EM_ATENDIMENTO", "para": "AGUARDANDO_DECISAO"}'),
 (:Q1, 'BLOQUEIO_DEFINIDO', '12 hours', '{"motivo": "CAUSA_EM_INVESTIGACAO", "categoria": "NAO_DEFINIDA"}'),
 (:Q1, 'BLOQUEIO_DEFINIDO', '13 hours', '{"motivo": "CAUSA_EM_INVESTIGACAO", "categoria": "NAO_DEFINIDA"}'),
 (:Q1, 'SETOR_ALTERADO', '14 hours', jsonb_build_object('de', :S1, 'para', :S2)),
 (:Q1, 'BLOQUEIO_DEFINIDO', '16 hours', '{"motivo": "CAUSA_EM_INVESTIGACAO", "categoria": "NAO_DEFINIDA"}'),
 (:Q1, 'BLOQUEIO_REMOVIDO', '18 hours', '{"motivo": "CAUSA_EM_INVESTIGACAO"}'),
 (:Q1, 'BLOQUEIO_DEFINIDO', '20 hours', '{"motivo": "CAUSA_EM_INVESTIGACAO", "categoria": "NAO_DEFINIDA"}'),
 (:Q1, 'BLOQUEIO_REMOVIDO', '22 hours', '{"motivo": "CAUSA_EM_INVESTIGACAO"}'),
 (:Q1, 'ETAPA_ALTERADA', '22 hours', '{"de": "AGUARDANDO_DECISAO", "para": "EM_ATENDIMENTO"}');
-- Q2 (aberto em S1, bloqueado): bloqueio SEM_VAGA iniciado ANTES do período (B+5h); detalhe alterado
-- DENTRO do período (B+11h) — não é início no período.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde, motivo_bloqueio_id,
                            bloqueio_desde)
VALUES (:Q2, :A, '99999999-0000-0000-0000-000000000102', :S1, :'base'::timestamptz + interval '2 hours',
        teste.etapa(:A, 'AGUARDANDO_DECISAO'), :'base'::timestamptz + interval '5 hours', teste.motivo(:A, 'SEM_VAGA'),
        :'base'::timestamptz + interval '5 hours');
INSERT INTO ev VALUES
 (:Q2, 'EPISODIO_ABERTO', '2 hours', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S1)),
 (:Q2, 'ETAPA_ALTERADA', '5 hours', '{"de": "EM_ATENDIMENTO", "para": "AGUARDANDO_DECISAO"}'),
 (:Q2, 'BLOQUEIO_DEFINIDO', '5 hours', '{"motivo": "SEM_VAGA", "categoria": "REGULACAO"}'),
 (:Q2, 'BLOQUEIO_DEFINIDO', '11 hours', '{"motivo": "SEM_VAGA", "categoria": "REGULACAO"}');
-- Q3 (aberto em S1): bloqueio iniciado B+21h com categoria REGULACAO; redefinido B+23h já com outra
-- categoria registrada (cadastro do motivo alterado depois): vale a categoria DO INÍCIO.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:Q3, :A, '99999999-0000-0000-0000-000000000103', :S1, :'base'::timestamptz + interval '20 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '24 hours');
INSERT INTO ev VALUES
 (:Q3, 'EPISODIO_ABERTO', '20 hours', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S1)),
 (:Q3, 'ETAPA_ALTERADA', '21 hours', '{"de": "EM_ATENDIMENTO", "para": "AGUARDANDO_DECISAO"}'),
 (:Q3, 'BLOQUEIO_DEFINIDO', '21 hours', '{"motivo": "SEM_VAGA", "categoria": "REGULACAO"}'),
 (:Q3, 'BLOQUEIO_DEFINIDO', '23 hours', '{"motivo": "SEM_VAGA", "categoria": "NAO_DEFINIDA"}'),
 (:Q3, 'BLOQUEIO_REMOVIDO', '24 hours', '{"motivo": "SEM_VAGA"}'),
 (:Q3, 'ETAPA_ALTERADA', '24 hours', '{"de": "AGUARDANDO_DECISAO", "para": "EM_ATENDIMENTO"}');
-- Q5 (aberto, setor atual S1) SEM evento de abertura: uma observação retroativa não tem setor
-- determinável pela linha do tempo — não pode ser atribuída silenciosamente ao setor atual.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:Q5, :A, '99999999-0000-0000-0000-000000000105', :S1, :'base'::timestamptz + interval '25 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '25 hours');
INSERT INTO ev VALUES (:Q5, 'OBSERVACAO_REGISTRADA', '26 hours', '{}');

INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
SELECT gen_random_uuid(), :A, ep, tipo, :'base'::timestamptz + h, :AJ::jsonb || dados FROM ev;

-- ------------------------------------------------------------------ intervalos normalizados
SELECT teste.afirma(count(*) = 2
                    AND min(ini) = :'base'::timestamptz + interval '12 hours' AND min(fim) = :'base'::timestamptz + interval '18 hours'
                    AND max(ini) = :'base'::timestamptz + interval '20 hours' AND max(fim) = :'base'::timestamptz + interval '22 hours',
                    'Q1: detalhe alterado não abre intervalo; remoção seguida de novo bloqueio = dois intervalos')
  FROM fluxo.rel_intervalos(:A, :'p_ini', :'agora') WHERE episodio_id = :Q1 AND tipo = 'BLOQUEIO';
SELECT teste.afirma(count(*) = 1 AND min(ini) = :'base'::timestamptz + interval '5 hours',
                    'Q2: bloqueio iniciado antes do período continua com o início original')
  FROM fluxo.rel_intervalos(:A, :'p_ini', :'agora') WHERE episodio_id = :Q2 AND tipo = 'BLOQUEIO';
SELECT teste.afirma(count(*) = 1 AND min(categoria) = 'REGULACAO',
                    'Q3: categoria do intervalo = a registrada no início (não a menor entre as redefinições)')
  FROM fluxo.rel_intervalos(:A, :'p_ini', :'agora') WHERE episodio_id = :Q3 AND tipo = 'BLOQUEIO';

-- ------------------------------------------------------------------ bloqueios iniciados (período P)
SELECT pg_temp.medir('P1', :'p_ini', :'p_fim', :'agora', NULL);
SELECT pg_temp.medir('P1_S1', :'p_ini', :'p_fim', :'agora', :S1);
SELECT pg_temp.medir('P1_S2', :'p_ini', :'p_fim', :'agora', :S2);
-- Inícios em P: Q1 B+12h (S1, sem causa), Q1 B+20h (S2, sem causa), Q3 B+21h (S1, REGULACAO).
-- Q2 (início B+5h) e as redefinições de Q1/Q2/Q3 não contam. A V19 contava 7 (4 de Q1, 1 de Q2, 2 de Q3).
SELECT teste.afirma(pg_temp.d('P0', 'P1', 'BLOQUEIOS_INICIADOS_SEM_CAUSA') = '2/3',
                    'sem filtro: 2 sem causa em 3 bloqueios iniciados — obtido: ' || pg_temp.d('P0', 'P1', 'BLOQUEIOS_INICIADOS_SEM_CAUSA'));
SELECT teste.afirma(pg_temp.d('P0_S1', 'P1_S1', 'BLOQUEIOS_INICIADOS_SEM_CAUSA') = '1/2',
                    'S1 (setor no início): Q1 B+12h e Q3 — obtido: ' || pg_temp.d('P0_S1', 'P1_S1', 'BLOQUEIOS_INICIADOS_SEM_CAUSA'));
SELECT teste.afirma(pg_temp.d('P0_S2', 'P1_S2', 'BLOQUEIOS_INICIADOS_SEM_CAUSA') = '1/1',
                    'S2: só o bloqueio de Q1 iniciado DEPOIS da transferência — obtido: '
                    || pg_temp.d('P0_S2', 'P1_S2', 'BLOQUEIOS_INICIADOS_SEM_CAUSA'));
SELECT teste.afirma(pg_temp.d('P0_S1', 'P1_S1', 'SETOR_NAO_ATRIBUIDO', 'BLOQUEIOS_INICIADOS') = '0/3',
                    'os 3 inícios do período têm setor determinável (base = todos os inícios, antes do filtro)');
-- Mesma definição em Gargalos e Evolução: soma dos "iniciados no período" por categoria = 3.
SELECT teste.afirma((SELECT sum(parte) FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', NULL, NULL, NULL)
                      WHERE secao = 'BLOQUEIO_CATEGORIA')
                    - coalesce((SELECT sum(parte) FROM g0 WHERE secao = 'BLOQUEIO_CATEGORIA'), 0) = 3
                    AND (SELECT parte FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', NULL, NULL, NULL)
                          WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'NAO_DEFINIDA')
                        - coalesce((SELECT parte FROM g0 WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'NAO_DEFINIDA'), 0) = 2
                    AND (SELECT parte FROM fluxo.rel_metricas_periodo(:A, :'p_ini', :'p_fim', :'agora', NULL)
                          WHERE secao = 'BLOQUEIO_MINUTOS') - (SELECT parte FROM m0 WHERE secao = 'BLOQUEIO_MINUTOS') = 3,
                    'Qualidade, Gargalos e Evolução concordam: 3 bloqueios iniciados, 2 sem causa');
SELECT teste.afirma((SELECT parte FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', :S2, NULL, NULL)
                      WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'NAO_DEFINIDA')
                    - coalesce((SELECT parte FROM g0s2 WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'NAO_DEFINIDA'), 0) = 1,
                    'Gargalos em S2 conta 1 início sem causa (Q1 B+20h), como a Qualidade');

-- ------------------------------------------------------------------ registros: período do REGISTRO,
-- setor do FATO. Todos os eventos da carga foram registrados agora (em P2).
SELECT pg_temp.medir('R1', :'p2_ini', :'p2_fim', :'p2_fim', NULL);
SELECT pg_temp.medir('R1_S1', :'p2_ini', :'p2_fim', :'p2_fim', :S1);
SELECT pg_temp.medir('R1_S2', :'p2_ini', :'p2_fim', :'p2_fim', :S2);
-- Setor do fato: Q1 = 4 em S1 (abertura, etapa e bloqueio às 12h, detalhe às 13h) e 6 em S2 (a
-- transferência pertence ao destino, e tudo depois dela); Q2 = 4 em S1; Q3 = 6 em S1; Q5 = sem setor.
-- A V19 usava o setor ATUAL: 10 em S2 (todo o Q1) e 11 em S1 (Q2, Q3 e Q5).
SELECT teste.afirma(pg_temp.d('R0', 'R1', 'REGISTROS_RETROATIVOS') = '21/21' AND pg_temp.dp('R0', 'R1', 'REGISTROS_RETROATIVOS') = 21,
                    'sem filtro: 21 registros no período — obtido: ' || pg_temp.d('R0', 'R1', 'REGISTROS_RETROATIVOS'));
SELECT teste.afirma(pg_temp.d('R0_S1', 'R1_S1', 'REGISTROS_RETROATIVOS') = '14/14',
                    'S1 pelo instante do fato — obtido: ' || pg_temp.d('R0_S1', 'R1_S1', 'REGISTROS_RETROATIVOS'));
SELECT teste.afirma(pg_temp.d('R0_S2', 'R1_S2', 'REGISTROS_RETROATIVOS') = '6/6',
                    'S2 pelo instante do fato — obtido: ' || pg_temp.d('R0_S2', 'R1_S2', 'REGISTROS_RETROATIVOS'));
SELECT teste.afirma(pg_temp.d('R0_S1', 'R1_S1', 'SETOR_NAO_ATRIBUIDO', 'REGISTROS') = '1/21',
                    'Q5 sem linha do tempo: sinalizado como não atribuível (não vai para o setor atual) — obtido: '
                    || pg_temp.d('R0_S1', 'R1_S1', 'SETOR_NAO_ATRIBUIDO', 'REGISTROS'));

-- ------------------------------------------------------------------ "consultar de novo" após transferência
-- Q4: entra em S1 (retroativo, B+31h) e recebe uma observação NÃO retroativa (há 30 s) em S1. Depois
-- é transferido para S2 (há 10 s). A nova consulta do MESMO período não pode mover o passado.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:Q4, :A, '99999999-0000-0000-0000-000000000104', :S1, :'base'::timestamptz + interval '31 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '31 hours');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES
 (gen_random_uuid(), :A, :Q4, 'EPISODIO_ABERTO', :'base'::timestamptz + interval '31 hours',
  :AJ::jsonb || jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S1)),
 (gen_random_uuid(), :A, :Q4, 'OBSERVACAO_REGISTRADA', clock_timestamp() - interval '30 seconds', '{}');
SELECT pg_temp.medir('R2_S1', :'p2_ini', :'p2_fim', :'p2_fim', :S1);
SELECT pg_temp.medir('R2_S2', :'p2_ini', :'p2_fim', :'p2_fim', :S2);
SELECT teste.afirma(pg_temp.d('R1_S1', 'R2_S1', 'REGISTROS_RETROATIVOS') = '2/2'
                    AND pg_temp.dp('R1_S1', 'R2_S1', 'REGISTROS_RETROATIVOS') = 1 AND pg_temp.d('R1_S2', 'R2_S2', 'REGISTROS_RETROATIVOS') = '0/0',
                    'antes da transferência: os 2 registros de Q4 (1 retroativo) estão em S1');

INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES
 (gen_random_uuid(), :A, :Q4, 'SETOR_ALTERADO', clock_timestamp() - interval '10 seconds',
  jsonb_build_object('de', :S1, 'para', :S2));
UPDATE fluxo.episodio SET setor_id = :S2, versao = versao + 1 WHERE id = :Q4;
SELECT pg_temp.medir('R3_S1', :'p2_ini', :'p2_fim', :'p2_fim', :S1);
SELECT pg_temp.medir('R3_S2', :'p2_ini', :'p2_fim', :'p2_fim', :S2);
SELECT teste.afirma(pg_temp.v('R3_S1', 'REGISTROS_RETROATIVOS') = pg_temp.v('R2_S1', 'REGISTROS_RETROATIVOS'),
                    'depois da transferência: S1 não perde nada — antes ' || pg_temp.v('R2_S1', 'REGISTROS_RETROATIVOS')
                    || ', depois ' || pg_temp.v('R3_S1', 'REGISTROS_RETROATIVOS'));
SELECT teste.afirma(pg_temp.d('R2_S2', 'R3_S2', 'REGISTROS_RETROATIVOS') = '1/1'
                    AND pg_temp.dp('R2_S2', 'R3_S2', 'REGISTROS_RETROATIVOS') = 0,
                    'S2 ganha só o registro da própria transferência (não retroativo) — obtido: '
                    || pg_temp.d('R2_S2', 'R3_S2', 'REGISTROS_RETROATIVOS'));

-- Estoque continua pelo setor ATUAL: Q4 conta agora na atualidade de S2.
SELECT teste.afirma(pg_temp.d('R2_S2', 'R3_S2', 'ATUALIDADE') = '1/1', 'estoque (atualidade) pelo setor atual');
ROLLBACK;
