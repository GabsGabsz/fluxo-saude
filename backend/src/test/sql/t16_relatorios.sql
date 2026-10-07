-- V19: relatórios gerenciais — valores calculados à mão sobre linhas do tempo controladas.
-- Base B = hora cheia de 40 h atrás (retroatividade de 48 h da unidade A). Período P = [B+10h, B+30h);
-- "agora" (instante de referência) = B+38h, passado como parâmetro (resultado determinístico).
-- Pendências e registros (criados "agora" pelo banco) usam um segundo bloco: período P2 = [N−1h, N+1h)
-- e referência N+5h, com N = instante da carga.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set S1 '''00000000-0000-0000-0000-0000000005a1'''
\set S2 '''00000000-0000-0000-0000-0000000005a2'''
\set ADM_A '''11111111-1111-1111-1111-000000000001'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set COORD_B '''11111111-1111-1111-1111-000000000003'''
\set AJ '''{"ajuste_manual": true, "ajuste_justificativa": "carga de teste"}'''
\set R1 '''88888888-0000-0000-0000-000000000001'''
\set R2 '''88888888-0000-0000-0000-000000000002'''
\set R3 '''88888888-0000-0000-0000-000000000003'''
\set R4 '''88888888-0000-0000-0000-000000000004'''
\set R5 '''88888888-0000-0000-0000-000000000005'''
\set R6 '''88888888-0000-0000-0000-000000000006'''

SELECT date_trunc('hour', now()) - interval '40 hours' AS base \gset
SELECT (:'base'::timestamptz + interval '10 hours') AS p_ini, (:'base'::timestamptz + interval '30 hours') AS p_fim,
       (:'base'::timestamptz + interval '38 hours') AS agora,
       (:'base'::timestamptz - interval '10 hours') AS p0_ini \gset

-- Função auxiliar do teste: linha de uma seção/chave.
CREATE TEMP TABLE r (LIKE fluxo.linha_relatorio);

-- ------------------------------------------------------------------ carga (unidade A, como a aplicação)
SELECT teste.ctx(:ENF, :A);
INSERT INTO fluxo.paciente (id, unidade_id, nome)
SELECT ('99999999-0000-0000-0000-00000000000' || n)::uuid, :A, 'Paciente Relatorio ' || n FROM generate_series(1, 6) n;

CREATE TEMP TABLE ev (ep uuid, tipo text, h interval, dados jsonb);
-- R1: entra B+8h (S1, atendimento); exame B+12h com bloqueio ASSISTENCIAL; muda para S2 B+14h; mesmo
-- motivo redefinido B+16h (não abre novo intervalo); volta ao atendimento B+20h (bloqueio removido);
-- alta B+24h no setor S2.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:R1, :A, '99999999-0000-0000-0000-000000000001', :S2, :'base'::timestamptz + interval '8 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '20 hours');
INSERT INTO ev VALUES
 (:R1, 'EPISODIO_ABERTO', '8 hours', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S1)),
 (:R1, 'ETAPA_ALTERADA', '12 hours', '{"de": "EM_ATENDIMENTO", "para": "AGUARDANDO_EXAME_PARECER"}'),
 (:R1, 'BLOQUEIO_DEFINIDO', '12 hours', '{"motivo": "AGUARDANDO_EXAME", "categoria": "ASSISTENCIAL"}'),
 (:R1, 'SETOR_ALTERADO', '14 hours', jsonb_build_object('de', :S1, 'para', :S2)),
 (:R1, 'BLOQUEIO_DEFINIDO', '16 hours', '{"motivo": "AGUARDANDO_EXAME", "categoria": "ASSISTENCIAL"}'),
 (:R1, 'ETAPA_ALTERADA', '20 hours', '{"de": "AGUARDANDO_EXAME_PARECER", "para": "EM_ATENDIMENTO"}'),
 (:R1, 'BLOQUEIO_REMOVIDO', '20 hours', '{"motivo": "AGUARDANDO_EXAME"}'),
 (:R1, 'ETAPA_ALTERADA', '24 hours', '{"de": "EM_ATENDIMENTO", "para": "ALTA"}');
-- R2: entra B+2h (S1); aguardando decisão B+4h com bloqueio SEM_VAGA (REGULACAO); continua aberto e bloqueado.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde, motivo_bloqueio_id,
                            bloqueio_desde)
VALUES (:R2, :A, '99999999-0000-0000-0000-000000000002', :S1, :'base'::timestamptz + interval '2 hours',
        teste.etapa(:A, 'AGUARDANDO_DECISAO'), :'base'::timestamptz + interval '4 hours', teste.motivo(:A, 'SEM_VAGA'),
        :'base'::timestamptz + interval '4 hours');
INSERT INTO ev VALUES
 (:R2, 'EPISODIO_ABERTO', '2 hours', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S1)),
 (:R2, 'ETAPA_ALTERADA', '4 hours', '{"de": "EM_ATENDIMENTO", "para": "AGUARDANDO_DECISAO"}'),
 (:R2, 'BLOQUEIO_DEFINIDO', '4 hours', '{"motivo": "SEM_VAGA", "categoria": "REGULACAO"}');
-- R3: entra B+15h (S2) bloqueado SEM_VAGA; troca para DESTINO_SEM_CAPACIDADE B+17h (novo intervalo);
-- desbloqueia B+18h; transferido B+26h.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:R3, :A, '99999999-0000-0000-0000-000000000003', :S2, :'base'::timestamptz + interval '15 hours',
        teste.etapa(:A, 'ACEITO'), :'base'::timestamptz + interval '15 hours');
INSERT INTO ev VALUES
 (:R3, 'EPISODIO_ABERTO', '15 hours', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S2)),
 (:R3, 'BLOQUEIO_DEFINIDO', '15 hours', '{"motivo": "SEM_VAGA", "categoria": "REGULACAO"}'),
 (:R3, 'BLOQUEIO_DEFINIDO', '17 hours', '{"motivo": "DESTINO_SEM_CAPACIDADE", "categoria": "LEITO_CAPACIDADE"}'),
 (:R3, 'BLOQUEIO_REMOVIDO', '18 hours', '{"motivo": "DESTINO_SEM_CAPACIDADE"}'),
 (:R3, 'ETAPA_ALTERADA', '26 hours', '{"de": "EM_ATENDIMENTO", "para": "TRANSFERIDO"}');
-- R4: entra B+20h (S1) SEM linha do tempo (registro legado: cobertura incompleta); aberto.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:R4, :A, '99999999-0000-0000-0000-000000000004', :S1, :'base'::timestamptz + interval '20 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '20 hours');
-- R5: entra B+29h (S1), alta EXATAMENTE no fim do período (B+30h: fora).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:R5, :A, '99999999-0000-0000-0000-000000000005', :S1, :'base'::timestamptz + interval '29 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '29 hours');
INSERT INTO ev VALUES
 (:R5, 'EPISODIO_ABERTO', '29 hours', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S1)),
 (:R5, 'ETAPA_ALTERADA', '30 hours', '{"de": "EM_ATENDIMENTO", "para": "ALTA"}');
-- R6: entra B+0h (S1), alta EXATAMENTE no início do período (B+10h: dentro).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:R6, :A, '99999999-0000-0000-0000-000000000006', :S1, :'base'::timestamptz,
        teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz);
INSERT INTO ev VALUES
 (:R6, 'EPISODIO_ABERTO', '0 hours', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :S1)),
 (:R6, 'ETAPA_ALTERADA', '10 hours', '{"de": "EM_ATENDIMENTO", "para": "ALTA"}');

INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
SELECT gen_random_uuid(), :A, ep, tipo, :'base'::timestamptz + h, :AJ::jsonb || dados FROM ev;

UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'ALTA'), etapa_desde = :'base'::timestamptz + interval '24 hours',
       desfecho = 'ALTA', encerrado_em = :'base'::timestamptz + interval '24 hours', versao = versao + 1 WHERE id = :R1;
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'TRANSFERIDO'), etapa_desde = :'base'::timestamptz + interval '26 hours',
       desfecho = 'TRANSFERENCIA', encerrado_em = :'base'::timestamptz + interval '26 hours', versao = versao + 1 WHERE id = :R3;
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'ALTA'), etapa_desde = :'base'::timestamptz + interval '30 hours',
       desfecho = 'ALTA', encerrado_em = :'base'::timestamptz + interval '30 hours', versao = versao + 1 WHERE id = :R5;
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'ALTA'), etapa_desde = :'base'::timestamptz + interval '10 hours',
       desfecho = 'ALTA', encerrado_em = :'base'::timestamptz + interval '10 hours', versao = versao + 1 WHERE id = :R6;

-- ------------------------------------------------------------------ linha do tempo reconstruída
SELECT teste.afirma(count(*) = 1 AND min(ini) = :'base'::timestamptz + interval '12 hours'
                    AND max(fim) = :'base'::timestamptz + interval '20 hours',
                    'mesmo motivo redefinido = um só intervalo de bloqueio')
  FROM fluxo.rel_intervalos(:A, :'p_ini', :'agora') WHERE episodio_id = :R1 AND tipo = 'BLOQUEIO';
SELECT teste.afirma(count(*) = 2, 'troca de motivo = dois intervalos')
  FROM fluxo.rel_intervalos(:A, :'p_ini', :'agora') WHERE episodio_id = :R3 AND tipo = 'BLOQUEIO';
SELECT teste.afirma(fluxo.rel_valor_em(:R1, 'SETOR', :'base'::timestamptz + interval '13 hours') = :S1
                    AND fluxo.rel_valor_em(:R1, 'SETOR', :'base'::timestamptz + interval '14 hours') = :S2
                    AND fluxo.rel_valor_em(:R1, 'ETAPA', :'base'::timestamptz + interval '15 hours') = 'AGUARDANDO_EXAME_PARECER',
                    'setor e etapa no instante, pela linha do tempo (a troca vale a partir do instante)');

-- ------------------------------------------------------------------ R1 resumo
INSERT INTO r SELECT * FROM fluxo.rel_resumo(:A, :'p_ini', :'p_fim', :'agora', NULL);
SELECT teste.afirma(quantidade = 3 AND parte = 1, 'entradas: R3, R4, R5 (R4 sem setor de entrada conhecido)')
  FROM r WHERE secao = 'ENTRADAS';
SELECT teste.afirma((SELECT quantidade FROM r WHERE secao = 'ENCERRAMENTOS' AND chave = 'ALTA') = 2
                    AND (SELECT quantidade FROM r WHERE secao = 'ENCERRAMENTOS' AND chave = 'TRANSFERENCIA') = 1
                    AND (SELECT quantidade FROM r WHERE secao = 'ENCERRAMENTOS_TOTAL') = 3,
                    'encerramentos: R1, R3, R6 (início incluído); R5 no fim excluído');
SELECT teste.afirma((SELECT quantidade FROM r WHERE secao = 'ABERTOS') = 2
                    AND (SELECT quantidade FROM r WHERE secao = 'ABERTOS_ETAPA' AND chave = 'AGUARDANDO_DECISAO') = 1
                    AND (SELECT quantidade FROM r WHERE secao = 'ABERTOS_SETOR' AND chave = :S1) = 2
                    AND (SELECT quantidade || '/' || base FROM r WHERE secao = 'BLOQUEADOS_AGORA') = '1/2',
                    'estoque no instante de referência: R2 e R4, separado dos eventos do período');
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_resumo(:A, :'p_ini', :'p_fim', :'agora', :S2);
SELECT teste.afirma((SELECT quantidade FROM r WHERE secao = 'ENTRADAS') = 1
                    AND (SELECT quantidade FROM r WHERE secao = 'ENCERRAMENTOS_TOTAL') = 2
                    AND (SELECT quantidade FROM r WHERE secao = 'ABERTOS') = 0,
                    'setor S2: entrada pelo setor de ENTRADA (R3), encerramento pelo setor FINAL (R1, R3)');

-- ------------------------------------------------------------------ R2 gargalos
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', NULL, NULL, NULL);
SELECT teste.afirma(quantidade = 4 AND episodios = 3 AND minutos = 1740 AND mediana = 420 AND p90 = 642 AND maximo = 660
                    AND media = 435,
                    'atendimento concluído no período: 240, 240, 600, 660 (intervalos repetidos de R1 contam cada um)')
  FROM r WHERE secao = 'ETAPA_CONCLUIDA' AND chave = 'EM_ATENDIMENTO';
SELECT teste.afirma(quantidade = 1 AND mediana = 480, 'exame/parecer concluído: R1 480 min')
  FROM r WHERE secao = 'ETAPA_CONCLUIDA' AND chave = 'AGUARDANDO_EXAME_PARECER';
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM r WHERE secao = 'ETAPA_CONCLUIDA' AND chave IN ('ALTA', 'TRANSFERIDO', 'AGUARDANDO_DECISAO')),
                    'etapas de desfecho e etapas em curso não entram nas concluídas');
SELECT teste.afirma(quantidade = 1 AND mediana = 2040 AND maximo = 2040,
                    'em curso: R2 em decisão há 34 h (idade, separada das durações concluídas)')
  FROM r WHERE secao = 'ETAPA_EM_CURSO' AND chave = 'AGUARDANDO_DECISAO';
SELECT teste.afirma((SELECT minutos FROM r WHERE secao = 'SETOR_TEMPO' AND chave = :S1) = 1500
                    AND (SELECT minutos FROM r WHERE secao = 'SETOR_TEMPO' AND chave = :S2) = 1260,
                    'tempo por setor sobreposto ao período, pela linha do tempo (R1: 240 em S1 e 600 em S2)');
SELECT teste.afirma((SELECT quantidade || '/' || parte || '/' || minutos FROM r WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'ASSISTENCIAL') = '1/1/480'
                    AND (SELECT quantidade || '/' || parte || '/' || minutos || '/' || episodios FROM r
                          WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'REGULACAO') = '2/1/1320/2'
                    AND (SELECT minutos FROM r WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'LEITO_CAPACIDADE') = 60,
                    'bloqueio por categoria da época: minutos no período e intervalos iniciados no período');
SELECT teste.afirma((SELECT grupo FROM r WHERE secao = 'BLOQUEIO_MOTIVO' AND chave = 'SEM_VAGA') = 'REGULACAO'
                    AND (SELECT minutos FROM r WHERE secao = 'BLOQUEIO_MOTIVO' AND chave = 'SEM_VAGA') = 1320,
                    'por motivo, com a categoria registrada');
SELECT teste.afirma((SELECT quantidade || '/' || mediana FROM r WHERE secao = 'BLOQUEIO_CONCLUIDO' AND chave = 'REGULACAO') = '1/120'
                    AND (SELECT mediana FROM r WHERE secao = 'BLOQUEIO_EM_CURSO' AND chave = 'REGULACAO') = 2040,
                    'bloqueio concluído (R3 120) separado do bloqueio em curso (R2 há 34 h)');
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', :S2, NULL, NULL);
SELECT teste.afirma(quantidade = 2 AND mediana = 450, 'S2: etapas pelo setor em que COMEÇARAM (R1 240, R3 660)')
  FROM r WHERE secao = 'ETAPA_CONCLUIDA' AND chave = 'EM_ATENDIMENTO';
SELECT teste.afirma(quantidade = 1 AND parte = 0 AND minutos = 360,
                    'S2: só a parte do bloqueio de R1 vivida em S2 (360 de 480 min); iniciado em S1, não em S2')
  FROM r WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'ASSISTENCIAL';
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM r WHERE secao IN ('ETAPA_EM_CURSO', 'BLOQUEIO_EM_CURSO')),
                    'S2: nada em curso (R2 está em S1)');
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', NULL,
                                              teste.etapa(:A, 'AGUARDANDO_EXAME_PARECER'), NULL);
SELECT teste.afirma((SELECT minutos FROM r WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'ASSISTENCIAL') = 480
                    AND NOT EXISTS (SELECT 1 FROM r WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'REGULACAO'),
                    'filtro de etapa: bloqueio só enquanto o episódio estava naquela etapa');
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_gargalos(:A, :'p_ini', :'p_fim', :'agora', NULL, NULL, 'LEITO_CAPACIDADE');
SELECT teste.afirma((SELECT count(*) FROM r WHERE secao LIKE 'BLOQUEIO%') = 3
                    AND (SELECT minutos FROM r WHERE secao = 'BLOQUEIO_CATEGORIA') = 60,
                    'filtro de categoria nos bloqueios');

-- ------------------------------------------------------------------ R4 evolução (dois períodos de 20 h)
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_metricas_periodo(:A, :'p_ini', :'p_fim', :'agora', NULL);
SELECT teste.afirma((SELECT quantidade FROM r WHERE secao = 'ENTRADAS') = 3
                    AND (SELECT quantidade || '/' || parte FROM r WHERE secao = 'ENCERRAMENTOS') = '3/1'
                    AND (SELECT quantidade || '/' || mediana FROM r WHERE secao = 'PERMANENCIA') = '3/660'
                    AND (SELECT quantidade || '/' || parte || '/' || minutos FROM r WHERE secao = 'BLOQUEIO_MINUTOS') = '4/3/1860',
                    'período atual: entradas 3, encerramentos 3 (1 transferência), permanência mediana 660, bloqueio 1860 min');
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_metricas_periodo(:A, :'p0_ini', :'p_ini', :'agora', NULL);
SELECT teste.afirma((SELECT quantidade FROM r WHERE secao = 'ENTRADAS') = 3
                    AND (SELECT quantidade FROM r WHERE secao = 'ENCERRAMENTOS') = 0
                    AND (SELECT quantidade FROM r WHERE secao = 'PERMANENCIA') = 0
                    AND (SELECT mediana FROM r WHERE secao = 'PERMANENCIA') IS NULL
                    AND (SELECT quantidade || '/' || parte || '/' || minutos FROM r WHERE secao = 'BLOQUEIO_MINUTOS') = '1/1/360',
                    'período anterior: sem encerramentos (mediana ausente, não zero); bloqueio de R2 [B+4h, B+10h)');

-- ------------------------------------------------------------------ conjunto vazio
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_gargalos(:A, '2020-01-01', '2020-01-02', '2020-01-02', NULL, NULL, NULL);
SELECT teste.afirma(count(*) = 0, 'gargalos sem dados: nenhuma linha (a tela diz "sem dados")') FROM r;
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_metricas_periodo(:A, '2020-01-01', '2020-01-02', '2020-01-02', NULL);
SELECT teste.afirma((SELECT quantidade FROM r WHERE secao = 'ENTRADAS') = 0
                    AND (SELECT minutos FROM r WHERE secao = 'BLOQUEIO_MINUTOS') = 0
                    AND (SELECT mediana FROM r WHERE secao = 'PERMANENCIA') IS NULL, 'período vazio: contagens 0, estatística ausente');

-- ------------------------------------------------------------------ pendências (criadas "agora")
SELECT clock_timestamp() AS n \gset
SELECT (:'n'::timestamptz - interval '1 hour') AS p2_ini, (:'n'::timestamptz + interval '1 hour') AS p2_fim,
       (:'n'::timestamptz + interval '5 hours') AS agora2 \gset
INSERT INTO fluxo.pendencia (id, unidade_id, episodio_id, categoria, descricao, responsavel_setor_id, prazo, criticidade_operacional)
VALUES ('77777777-0000-0000-0000-0000000000a1', :A, :R2, 'REGULACAO', 'Cobrar vaga na central', :S1, :'n'::timestamptz + interval '2 hours', 'ALTA');
INSERT INTO fluxo.pendencia (id, unidade_id, episodio_id, categoria, descricao, responsavel_papel, prazo, criticidade_operacional)
VALUES ('77777777-0000-0000-0000-0000000000a2', :A, :R2, 'LOGISTICA', '=HYPERLINK("x") transporte', 'MEDICO', :'n'::timestamptz + interval '10 hours', 'CRITICA');
INSERT INTO fluxo.pendencia (id, unidade_id, episodio_id, categoria, descricao, responsavel_setor_id, prazo, criticidade_operacional)
VALUES ('77777777-0000-0000-0000-0000000000a3', :A, :R2, 'ADMINISTRATIVO', 'Completar cadastro', :S1, :'n'::timestamptz + interval '1 hour', 'BAIXA'),
       ('77777777-0000-0000-0000-0000000000a4', :A, :R2, 'ADMINISTRATIVO', 'Copiar documento', :S1, :'n'::timestamptz + interval '3 hours', 'BAIXA');
UPDATE fluxo.pendencia SET status = 'RESOLVIDA', resolucao = 'Feito', encerrada_em = clock_timestamp(), encerrada_por = :ENF,
       versao = versao + 1 WHERE id = '77777777-0000-0000-0000-0000000000a3';
UPDATE fluxo.pendencia SET prazo = :'n'::timestamptz + interval '4 hours', versao = versao + 1 WHERE id = '77777777-0000-0000-0000-0000000000a4';
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
VALUES (gen_random_uuid(), :A, :R2, 'PENDENCIA_ATUALIZADA', clock_timestamp(),
        '{"pendencia_id": "77777777-0000-0000-0000-0000000000a4", "campo": "prazo"}');
UPDATE fluxo.pendencia SET status = 'CANCELADA', resolucao = 'Nao se aplica', encerrada_em = clock_timestamp(), encerrada_por = :ENF,
       versao = versao + 1 WHERE id = '77777777-0000-0000-0000-0000000000a4';

TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_pendencias(:A, :'p2_ini', :'p2_fim', :'agora2', NULL, NULL);
SELECT teste.afirma((SELECT quantidade || '/' || parte FROM r WHERE secao = 'CRIADAS_TOTAL') = '4/1'
                    AND (SELECT quantidade FROM r WHERE secao = 'CRIADAS' AND chave = 'ADMINISTRATIVO') = 2,
                    'criadas: 4, uma com prazo alterado');
SELECT teste.afirma((SELECT quantidade || '/' || parte || '/' || base FROM r WHERE secao = 'ENCERRADAS' AND chave = 'RESOLVIDA') = '1/1/1'
                    AND (SELECT quantidade || '/' || parte FROM r WHERE secao = 'ENCERRADAS' AND chave = 'CANCELADA') = '1/1',
                    'encerradas: resolvida e cancelada, ambas até o último prazo');
SELECT teste.afirma(quantidade = 2 AND parte = 1 AND round(mediana) = 300 AND round(maximo) = 300,
                    'abertas na referência N+5h: 2, uma vencida, idade 5 h')
  FROM r WHERE secao = 'ABERTAS_TOTAL';
SELECT teste.afirma((SELECT quantidade || '/' || parte FROM r WHERE secao = 'ABERTAS_RESPONSAVEL' AND chave = 'SETOR') = '1/1'
                    AND (SELECT quantidade || '/' || parte FROM r WHERE secao = 'ABERTAS_RESPONSAVEL' AND chave = 'PERFIL') = '1/0'
                    AND (SELECT round(mediana) FROM r WHERE secao = 'VENCIDAS_ATRASO') = 180,
                    'por tipo de responsável; atraso da vencida = 3 h');
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_pendencias(:A, :'p2_ini', :'p2_fim', :'agora2', :S2, NULL);
SELECT teste.afirma(coalesce((SELECT quantidade FROM r WHERE secao = 'CRIADAS_TOTAL'), 0) = 0
                    AND coalesce((SELECT quantidade FROM r WHERE secao = 'ABERTAS_TOTAL'), 0) = 0,
                    'setor S2: nenhuma (os episódios estão em S1)');
SELECT teste.afirma(count(*) = 2 AND (array_agg(descricao ORDER BY prazo))[1] = 'Cobrar vaga na central'
                    AND (array_agg(responsavel_nome ORDER BY prazo))[1] = 'Observação'
                    AND (array_agg(responsavel_nome ORDER BY prazo))[2] = 'MEDICO'
                    AND bool_or(vencida) AND (array_agg(paciente_nome ORDER BY prazo))[1] = 'Paciente Relatorio 2',
                    'lista operacional nominal: abertas por prazo')
  FROM fluxo.rel_pendencias_lista(:A, :'agora2', NULL, NULL, 10);
SELECT teste.afirma(count(*) = 1, 'lista respeita o limite (a aplicação pede limite+1 e recusa lista parcial)')
  FROM fluxo.rel_pendencias_lista(:A, :'agora2', NULL, NULL, 1);

-- ------------------------------------------------------------------ R5 qualidade
SELECT teste.ctx(:ADM_A, :A);
INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite) VALUES
    ('67676767-0000-0000-0000-0000000000f1', :A, 'Sem registro recente', 'SEM_ATUALIZACAO', interval '30 minutes');
SELECT teste.ctx(:ENF, :A);
SELECT (SELECT max(registrado_em) FROM fluxo.evento_episodio WHERE episodio_id = :R2) + interval '60 minutes' AS agora_q \gset
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_qualidade(:A, :'p2_ini', :'p2_fim', :'agora_q', NULL);
SELECT teste.afirma(quantidade = 1 AND base = 2 AND round(mediana) = 60,
                    'atualidade: R2 há 60 min do último registro; R4 sem registro (contado à parte, não como zero)')
  FROM r WHERE secao = 'ATUALIDADE';
SELECT teste.afirma(quantidade = 1 AND base = 2 AND minutos = 30, 'só a regra CONFIGURADA define "sem atualização"')
  FROM r WHERE secao = 'SEM_ATUALIZACAO_REGRA';
SELECT teste.afirma(quantidade = (SELECT count(*) FROM fluxo.evento_episodio WHERE unidade_id = :A)
                    AND parte = quantidade - 1 AND round(mediana) > 0,
                    'registros no período: todos os da carga são retroativos (com ajuste); a alteração de prazo, não')
  FROM r WHERE secao = 'REGISTROS_RETROATIVOS';
SELECT teste.afirma((SELECT quantidade || '/' || base FROM r WHERE secao = 'CAUSA_EM_INVESTIGACAO_AGORA') = '0/1'
                    AND (SELECT quantidade || '/' || base FROM r WHERE secao = 'LINHA_DO_TEMPO') = '1/2'
                    AND (SELECT grupo || ':' || base FROM r WHERE secao = 'DESTINO_EM_TRANSFERENCIA') = 'OPCIONAL:0',
                    'cobertura: causa definida, linha do tempo de R4 ausente, destino opcional sem base');

-- ------------------------------------------------------------------ isolamento (RLS)
SELECT teste.ctx(:COORD_B, :B);
TRUNCATE r;
INSERT INTO r SELECT * FROM fluxo.rel_resumo(:A, :'p_ini', :'p_fim', :'agora', NULL);
SELECT teste.afirma((SELECT quantidade FROM r WHERE secao = 'ENTRADAS') = 0 AND (SELECT quantidade FROM r WHERE secao = 'ABERTOS') = 0,
                    'outra unidade não enxerga nada da A, mesmo pedindo a A');
SELECT teste.afirma(count(*) = 0, 'lista nominal vazia para outra unidade')
  FROM fluxo.rel_pendencias_lista(:A, :'agora2', NULL, NULL, 10);
ROLLBACK;
