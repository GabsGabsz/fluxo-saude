-- V16: indicadores — valores calculados à mão sobre uma linha do tempo controlada.
-- Base B = hora cheia de 40 h atrás (dentro da retroatividade de 48 h da unidade A).
-- Período P = [B+10h, B+30h); "agora" das funções = B+38h (parâmetro: resultado determinístico).
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set S1 '''00000000-0000-0000-0000-0000000005a1'''
\set S2 '''00000000-0000-0000-0000-0000000005a2'''
\set SB '''00000000-0000-0000-0000-0000000005b1'''
\set ADM_A '''11111111-1111-1111-1111-000000000001'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set COORD_B '''11111111-1111-1111-1111-000000000003'''
\set AJ '''{"ajuste_manual": true, "ajuste_justificativa": "carga de teste"}'''

SELECT date_trunc('hour', now()) - interval '40 hours' AS base \gset
SELECT (:'base'::timestamptz + interval '10 hours') AS p_ini, (:'base'::timestamptz + interval '30 hours') AS p_fim,
       (:'base'::timestamptz + interval '38 hours') AS agora \gset

-- ------------------------------------------------------------------ fusos e fronteiras de período
SELECT teste.afirma(fim - inicio = interval '23 hours', 'dia de início do horário de verão (Nova York) tem 23 h')
  FROM fluxo.ind_periodo('America/New_York', '2026-03-08', '2026-03-08');
SELECT teste.afirma(fim - inicio = interval '25 hours', 'dia de fim do horário de verão (Nova York) tem 25 h')
  FROM fluxo.ind_periodo('America/New_York', '2026-11-01', '2026-11-01');
SELECT teste.afirma(inicio = '2026-10-06 03:00:00+00' AND fim = '2026-10-08 03:00:00+00',
                    'Fortaleza: [06/10 00:00, 08/10 00:00) locais = [03:00Z, 03:00Z)')
  FROM fluxo.ind_periodo('America/Fortaleza', '2026-10-06', '2026-10-07');
SELECT teste.afirma(inicio = '2026-10-06 04:00:00+00', 'Manaus: 1 h a mais que Fortaleza')
  FROM fluxo.ind_periodo('America/Manaus', '2026-10-06', '2026-10-06');

-- ------------------------------------------------------------------ carga (unidade A, como a aplicação)
SELECT teste.ctx(:ENF, :A);
SELECT (SELECT count(*) FROM fluxo.episodio) AS episodios_antes \gset
INSERT INTO fluxo.paciente (id, unidade_id, nome)
SELECT ('44444444-0000-0000-0000-00000000000' || n)::uuid, :A, 'Paciente Indicador ' || n FROM generate_series(1, 9) n;

-- E1: alta em B+12h (720 min). Bloqueio inteiro ANTES do período (não entra em motivos).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('55555555-0000-0000-0000-000000000001', :A, '44444444-0000-0000-0000-000000000001', :S1,
        :'base'::timestamptz, teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz);
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000001', 'BLOQUEIO_DEFINIDO', :'base'::timestamptz + interval '1 hour', :AJ::jsonb || '{"motivo": "ACOMPANHANTE", "categoria": "LOGISTICA"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000001', 'BLOQUEIO_REMOVIDO', :'base'::timestamptz + interval '3 hours', :AJ::jsonb || '{"motivo": "ACOMPANHANTE"}');
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'ALTA'), etapa_desde = :'base'::timestamptz + interval '12 hours',
       desfecho = 'ALTA', encerrado_em = :'base'::timestamptz + interval '12 hours', versao = versao + 1
 WHERE id = '55555555-0000-0000-0000-000000000001';

-- E2: entra B+8h, alta EXATAMENTE no início do período (B+10h, incluída): 120 min.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('55555555-0000-0000-0000-000000000002', :A, '44444444-0000-0000-0000-000000000002', :S1,
        :'base'::timestamptz + interval '8 hours', teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '8 hours');
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'ALTA'), etapa_desde = :'base'::timestamptz + interval '10 hours',
       desfecho = 'ALTA', encerrado_em = :'base'::timestamptz + interval '10 hours', versao = versao + 1
 WHERE id = '55555555-0000-0000-0000-000000000002';

-- E3: alta EXATAMENTE no fim do período (B+30h, excluída).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('55555555-0000-0000-0000-000000000003', :A, '44444444-0000-0000-0000-000000000003', :S1,
        :'base'::timestamptz + interval '20 hours', teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '20 hours');
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'ALTA'), etapa_desde = :'base'::timestamptz + interval '30 hours',
       desfecho = 'ALTA', encerrado_em = :'base'::timestamptz + interval '30 hours', versao = versao + 1
 WHERE id = '55555555-0000-0000-0000-000000000003';

-- E4: transferência com intervalos repetidos. Solicitação B+2h; aceite B+11h (1º); volta ao
-- atendimento B+11h30; aceite de novo B+12h (último); saída B+13h. Permanência 720 min (B+1h..B+13h).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('55555555-0000-0000-0000-000000000004', :A, '44444444-0000-0000-0000-000000000004', :S1,
        :'base'::timestamptz + interval '1 hour', teste.etapa(:A, 'ACEITO'), :'base'::timestamptz + interval '12 hours');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000004', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '90 minutes', :AJ::jsonb || '{"de": "EM_ATENDIMENTO", "para": "AGUARDANDO_SOLICITACAO_TRANSFERENCIA"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000004', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '2 hours', :AJ::jsonb || '{"de": "AGUARDANDO_SOLICITACAO_TRANSFERENCIA", "para": "TRANSFERENCIA_SOLICITADA"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000004', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '11 hours', :AJ::jsonb || '{"de": "TRANSFERENCIA_SOLICITADA", "para": "ACEITO"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000004', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '690 minutes', :AJ::jsonb || '{"de": "ACEITO", "para": "EM_ATENDIMENTO"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000004', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '12 hours', :AJ::jsonb || '{"de": "EM_ATENDIMENTO", "para": "ACEITO"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000004', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '13 hours', :AJ::jsonb || '{"de": "ACEITO", "para": "TRANSFERIDO"}');
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'TRANSFERIDO'), etapa_desde = :'base'::timestamptz + interval '13 hours',
       desfecho = 'TRANSFERENCIA', encerrado_em = :'base'::timestamptz + interval '13 hours', versao = versao + 1
 WHERE id = '55555555-0000-0000-0000-000000000004';

-- E5: encerramento administrativo em B+15h (excluído da permanência; contado nos desfechos).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('55555555-0000-0000-0000-000000000005', :A, '44444444-0000-0000-0000-000000000005', :S1,
        :'base'::timestamptz + interval '2 hours', teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '2 hours');
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'CANCELADO_ENCERRADO'), etapa_desde = :'base'::timestamptz + interval '15 hours',
       desfecho = 'ENCERRAMENTO_ADMINISTRATIVO', encerrado_em = :'base'::timestamptz + interval '15 hours',
       justificativa_encerramento = 'Registro aberto por engano', versao = versao + 1
 WHERE id = '55555555-0000-0000-0000-000000000005';

-- E6: ABERTO, setor NIR, bloqueado agora. Linha do tempo de bloqueio:
--   SEM_VAGA B+6h (antes do período) -> SEM_VAGA de novo B+12h (troca de detalhe) -> removido B+14h
--   -> AGUARDANDO_EXAME B+20h (em aberto: até "agora", recortado no fim do período).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde,
                            motivo_bloqueio_id, bloqueio_desde)
VALUES ('55555555-0000-0000-0000-000000000006', :A, '44444444-0000-0000-0000-000000000006', :S2,
        :'base'::timestamptz + interval '5 hours', teste.etapa(:A, 'EM_ATENDIMENTO'), :'base'::timestamptz + interval '5 hours',
        teste.motivo(:A, 'AGUARDANDO_EXAME'), :'base'::timestamptz + interval '20 hours');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000006', 'BLOQUEIO_DEFINIDO', :'base'::timestamptz + interval '6 hours', :AJ::jsonb || '{"motivo": "SEM_VAGA", "categoria": "REGULACAO"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000006', 'BLOQUEIO_DEFINIDO', :'base'::timestamptz + interval '12 hours', :AJ::jsonb || '{"motivo": "SEM_VAGA", "categoria": "REGULACAO"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000006', 'BLOQUEIO_REMOVIDO', :'base'::timestamptz + interval '14 hours', :AJ::jsonb || '{"motivo": "SEM_VAGA"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000006', 'BLOQUEIO_DEFINIDO', :'base'::timestamptz + interval '20 hours', :AJ::jsonb || '{"motivo": "AGUARDANDO_EXAME", "categoria": "ASSISTENCIAL"}');
INSERT INTO fluxo.pendencia (unidade_id, episodio_id, categoria, descricao, responsavel_setor_id, prazo, criticidade_operacional)
VALUES (:A, '55555555-0000-0000-0000-000000000006', 'ASSISTENCIAL', 'Cobrar laudo', :S2, now() + interval '2 hours', 'ALTA'),
       (:A, '55555555-0000-0000-0000-000000000006', 'REGULACAO', 'Atualizar regulação', :S2, now() + interval '4 days', 'MEDIA');

-- E7: aceite em B+16h SEM solicitação registrada antes; saída B+18h (aceite->saída 120 min); permanência 900 min.
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('55555555-0000-0000-0000-000000000007', :A, '44444444-0000-0000-0000-000000000007', :S1,
        :'base'::timestamptz + interval '3 hours', teste.etapa(:A, 'ACEITO'), :'base'::timestamptz + interval '16 hours');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000007', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '16 hours', :AJ::jsonb || '{"de": "EM_ATENDIMENTO", "para": "ACEITO"}');
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'TRANSFERIDO'), etapa_desde = :'base'::timestamptz + interval '18 hours',
       desfecho = 'TRANSFERENCIA', encerrado_em = :'base'::timestamptz + interval '18 hours', versao = versao + 1
 WHERE id = '55555555-0000-0000-0000-000000000007';

-- E8: transferido SEM aceite registrado (aceite->saída: dado ausente); bloqueio de transporte
-- B+17h..B+20h (180 min no período); permanência 960 min (B+4h..B+20h).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde,
                            motivo_bloqueio_id, bloqueio_desde)
VALUES ('55555555-0000-0000-0000-000000000008', :A, '44444444-0000-0000-0000-000000000008', :S1,
        :'base'::timestamptz + interval '4 hours', teste.etapa(:A, 'AGUARDANDO_TRANSPORTE'), :'base'::timestamptz + interval '17 hours',
        teste.motivo(:A, 'TRANSPORTE_PENDENTE'), :'base'::timestamptz + interval '17 hours');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000008', 'BLOQUEIO_DEFINIDO', :'base'::timestamptz + interval '17 hours', :AJ::jsonb || '{"motivo": "TRANSPORTE_PENDENTE", "categoria": "LOGISTICA"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000008', 'BLOQUEIO_REMOVIDO', :'base'::timestamptz + interval '20 hours', :AJ::jsonb || '{"motivo": "TRANSPORTE_PENDENTE"}');
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:A, 'TRANSFERIDO'), etapa_desde = :'base'::timestamptz + interval '20 hours',
       motivo_bloqueio_id = NULL, bloqueio_desde = NULL,
       desfecho = 'TRANSFERENCIA', encerrado_em = :'base'::timestamptz + interval '20 hours', versao = versao + 1
 WHERE id = '55555555-0000-0000-0000-000000000008';

-- E9: ABERTO; aceite em B+11h SEM solicitação antes; solicitação em B+13h; novo aceite em B+15h.
-- Solicitação -> aceite = 120 min (1ª solicitação -> 1º aceite seguinte); NÃO é "sem marco" (entrou no tempo).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('55555555-0000-0000-0000-000000000009', :A, '44444444-0000-0000-0000-000000000009', :S1,
        :'base'::timestamptz + interval '10 hours', teste.etapa(:A, 'ACEITO'), :'base'::timestamptz + interval '15 hours');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000009', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '11 hours', :AJ::jsonb || '{"de": "EM_ATENDIMENTO", "para": "ACEITO"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000009', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '13 hours', :AJ::jsonb || '{"de": "ACEITO", "para": "TRANSFERENCIA_SOLICITADA"}'),
 (gen_random_uuid(), :A, '55555555-0000-0000-0000-000000000009', 'ETAPA_ALTERADA', :'base'::timestamptz + interval '15 hours', :AJ::jsonb || '{"de": "TRANSFERENCIA_SOLICITADA", "para": "ACEITO"}');

-- Regras (administrador): só TEMPO_TOTAL ativa e sem etapa entra no "acima do limite".
SELECT teste.ctx(:ADM_A, :A);
INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite) VALUES
 ('66666666-0000-0000-0000-000000000001', :A, 'Permanência 12 h (ilustrativa)', 'TEMPO_TOTAL', interval '720 minutes'),
 ('66666666-0000-0000-0000-000000000002', :A, 'Inativa (ilustrativa)', 'TEMPO_TOTAL', interval '60 minutes');
INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, etapa_id, limite) VALUES
 ('66666666-0000-0000-0000-000000000003', :A, 'Com etapa (ilustrativa)', 'TEMPO_TOTAL', teste.etapa(:A, 'ACEITO'), interval '60 minutes');
UPDATE fluxo.regra_alerta SET ativa = false, versao = versao + 1 WHERE id = '66666666-0000-0000-0000-000000000002';

-- Unidade B: um encerrado no mesmo período (não pode aparecer na A).
SELECT teste.ctx(:COORD_B, :B);
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('44444444-0000-0000-0000-0000000000b1', :B, 'Paciente B');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('55555555-0000-0000-0000-0000000000b1', :B, '44444444-0000-0000-0000-0000000000b1', :SB,
        now() - interval '20 hours', teste.etapa(:B, 'EM_ATENDIMENTO'), now() - interval '20 hours');
UPDATE fluxo.episodio SET etapa_id = teste.etapa(:B, 'ALTA'), etapa_desde = now() - interval '19 hours',
       desfecho = 'ALTA', encerrado_em = now() - interval '19 hours', versao = versao + 1
 WHERE id = '55555555-0000-0000-0000-0000000000b1';

-- ------------------------------------------------------------------ asserções (unidade A)
SELECT teste.ctx(:ENF, :A);

-- I-01/I-02: incluídos E1 720, E2 120, E4 720, E7 900, E8 960 (E3 na fronteira final; E5 administrativo)
SELECT teste.afirma(incluidos = 5 AND excluidos = 1 AND round(media_min::numeric, 6) = 684 AND mediana_min = 720
                    AND minimo_min = 120 AND maximo_min = 960,
                    format('permanência: n=5, excl=1, média 684, mediana 720, min 120, max 960 (obtido %s/%s/%s/%s/%s/%s)',
                           incluidos, excluidos, media_min, mediana_min, minimo_min, maximo_min))
  FROM fluxo.ind_permanencia(:A, :'p_ini', :'p_fim', NULL);
-- Fronteira: deslocar o fim em 1 s inclui E3 (600 min).
SELECT teste.afirma(incluidos = 6, 'fim + 1 s inclui a alta exatamente na fronteira')
  FROM fluxo.ind_permanencia(:A, :'p_ini', :'p_fim'::timestamptz + interval '1 second', NULL);
-- Fronteira: começar 1 s depois exclui E2.
SELECT teste.afirma(incluidos = 4, 'início + 1 s exclui a alta exatamente no início')
  FROM fluxo.ind_permanencia(:A, :'p_ini'::timestamptz + interval '1 second', :'p_fim', NULL);
-- Conjunto vazio: contagem 0 e estatísticas AUSENTES (NULL), nunca zero.
SELECT teste.afirma(incluidos = 0 AND excluidos = 0 AND media_min IS NULL AND mediana_min IS NULL,
                    'período sem encerramentos: n=0 e média/mediana ausentes')
  FROM fluxo.ind_permanencia(:A, :'base'::timestamptz - interval '2 hours', :'base'::timestamptz - interval '1 hour', NULL);
-- Setor: NIR não tem encerrados no período
SELECT teste.afirma(incluidos = 0, 'filtro de setor') FROM fluxo.ind_permanencia(:A, :'p_ini', :'p_fim', :S2);
-- Isolamento: os dados da B não são visíveis a partir da A (nem passando o id da B).
SELECT teste.afirma(incluidos = 0 AND excluidos = 0, 'RLS: unidade B invisível')
  FROM fluxo.ind_permanencia(:B, now() - interval '2 days', now(), NULL);

-- I-03: só a regra ativa sem etapa; >= limite (720) é "acima": E1, E4 (=720), E7, E8 -> 4 de 5.
SELECT teste.afirma(count(*) = 1 AND bool_and(regra_id = '66666666-0000-0000-0000-000000000001' AND limite_min = 720
                    AND populacao = 5 AND acima = 4), 'acima do limite: 4 de 5 (fronteira inclusiva)')
  FROM fluxo.ind_acima_dos_limites(:A, :'p_ini', :'p_fim', NULL);
-- Denominador zero: população 0 (o percentual fica ausente na aplicação).
SELECT teste.afirma(populacao = 0 AND acima = 0, 'denominador zero')
  FROM fluxo.ind_acima_dos_limites(:A, :'base'::timestamptz - interval '2 hours', :'base'::timestamptz - interval '1 hour', NULL);

-- I-04: desfechos no período
SELECT teste.afirma(jsonb_object_agg(desfecho, quantidade) = '{"ALTA": 2, "TRANSFERENCIA": 3, "ENCERRAMENTO_ADMINISTRATIVO": 1}'::jsonb,
                    'desfechos: 2 altas, 3 transferências, 1 administrativo')
  FROM fluxo.ind_desfechos(:A, :'p_ini', :'p_fim', NULL);

-- I-05: E4 1ª solicitação (B+2h) -> 1º aceite seguinte (B+11h) = 540 min; E9 (B+13h -> B+15h) = 120 min;
-- E7 sem solicitação = dado ausente. E9 não é contado duas vezes (já entrou no tempo).
SELECT teste.afirma(incluidos = 2 AND sem_marco = 1 AND media_min = 330 AND mediana_min = 330,
                    format('solicitação->aceite (obtido %s/%s/%s)', incluidos, sem_marco, media_min))
  FROM fluxo.ind_solicitacao_aceite(:A, :'p_ini', :'p_fim', NULL);

-- I-06: último aceite antes da saída: E4 60 min, E7 120 min; E8 sem aceite.
SELECT teste.afirma(incluidos = 2 AND sem_marco = 1 AND media_min = 90 AND mediana_min = 90,
                    format('aceite->saída (obtido %s/%s/%s)', incluidos, sem_marco, media_min))
  FROM fluxo.ind_aceite_saida(:A, :'p_ini', :'p_fim', NULL);

-- I-07: motivos. AGUARDANDO_EXAME [B+20h, agora) recortado em B+30h = 600; SEM_VAGA [B+10h,B+14h) = 240
-- sem novo início no período; TRANSPORTE_PENDENTE [B+17h,B+20h) = 180; ACOMPANHANTE fora do período.
SELECT teste.afirma(
    jsonb_agg(jsonb_build_array(codigo, round(minutos::numeric), inicios, episodios) ORDER BY minutos DESC)
      = '[["AGUARDANDO_EXAME", 600, 1, 1], ["SEM_VAGA", 240, 0, 1], ["TRANSPORTE_PENDENTE", 180, 1, 1]]'::jsonb,
    'distribuição dos motivos (minutos, inícios, episódios)')
  FROM fluxo.ind_motivos(:A, :'p_ini', :'p_fim', NULL, :'agora');
SELECT teste.afirma(bool_and(categoria IS NOT NULL AND descricao IS NOT NULL), 'motivos com descrição e categoria')
  FROM fluxo.ind_motivos(:A, :'p_ini', :'p_fim', NULL, :'agora');
-- "agora" antes do fim do período: o intervalo em aberto termina em "agora" (B+25h -> 300 min)
SELECT teste.afirma(round(minutos::numeric) = 300, 'intervalo aberto termina em agora')
  FROM fluxo.ind_motivos(:A, :'p_ini', :'p_fim', NULL, :'base'::timestamptz + interval '25 hours') WHERE codigo = 'AGUARDANDO_EXAME';
-- Setor S1: só o transporte (E8)
SELECT teste.afirma(array_agg(codigo) = ARRAY['TRANSPORTE_PENDENTE'], 'motivos filtrados por setor')
  FROM fluxo.ind_motivos(:A, :'p_ini', :'p_fim', :S1, :'agora');

-- Retrato atual (todos os abertos; aqui só o setor NIR, onde está E6)
SELECT teste.afirma(
    (SELECT quantidade FROM fluxo.ind_retrato(:A, :S2, now() + interval '3 days') WHERE dimensao = 'ABERTOS') = 1
    AND (SELECT quantidade FROM fluxo.ind_retrato(:A, :S2, now() + interval '3 days') WHERE dimensao = 'MOTIVO'
           AND chave = teste.motivo(:A, 'AGUARDANDO_EXAME')) = 1
    AND (SELECT quantidade FROM fluxo.ind_retrato(:A, :S2, now() + interval '3 days') WHERE dimensao = 'SEM_BLOQUEIO') = 0
    AND (SELECT quantidade FROM fluxo.ind_retrato(:A, :S2, now() + interval '3 days') WHERE dimensao = 'PENDENCIAS_VENCIDAS') = 1
    AND (SELECT quantidade FROM fluxo.ind_retrato(:A, :S2, now() + interval '3 days') WHERE dimensao = 'EPISODIOS_COM_VENCIDA') = 1,
    'retrato: 1 aberto, bloqueado por exame, 1 pendência vencida (relativa ao "agora" informado)');
SELECT teste.afirma((SELECT quantidade FROM fluxo.ind_retrato(:A, NULL, now()) WHERE dimensao = 'ABERTOS')
                    = (SELECT count(*) FROM fluxo.episodio WHERE encerrado_em IS NULL),
                    'retrato conta TODOS os abertos da unidade (sem limite de página)');

-- Volume diário: soma de entradas no intervalo de datas locais = episódios que entraram nele.
SELECT teste.afirma(
    (SELECT sum(entradas) FROM fluxo.ind_volume_diario(:A, 'America/Fortaleza',
        (:'base'::timestamptz AT TIME ZONE 'America/Fortaleza')::date, (now() AT TIME ZONE 'America/Fortaleza')::date, NULL))
    = (SELECT count(*) FROM fluxo.episodio e, fluxo.ind_periodo('America/Fortaleza',
           (:'base'::timestamptz AT TIME ZONE 'America/Fortaleza')::date, (now() AT TIME ZONE 'America/Fortaleza')::date) p
        WHERE e.entrada_em >= p.inicio AND e.entrada_em < p.fim),
    'volume diário: entradas somam o total do intervalo');
SELECT teste.afirma(count(*) = 3, 'uma linha por dia, inclusive dias sem movimento')
  FROM fluxo.ind_volume_diario(:A, 'America/Fortaleza', '2020-01-01', '2020-01-03', NULL);
SELECT teste.afirma(bool_and(entradas = 0 AND saidas = 0), 'dias sem movimento = 0 entradas (contagem, não estatística)')
  FROM fluxo.ind_volume_diario(:A, 'America/Fortaleza', '2020-01-01', '2020-01-03', NULL);
ROLLBACK;
