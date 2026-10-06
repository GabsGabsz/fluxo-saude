-- V13: regras de alerta (parâmetros da unidade, só Administrador) e ciência de alerta.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set ADM_A '''11111111-1111-1111-1111-000000000001'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set ADM_B '''11111111-1111-1111-1111-000000000004'''
\set R1 '''66666666-0000-0000-0000-000000000001'''
\set R2 '''66666666-0000-0000-0000-000000000002'''
\set PAC '''22222222-0000-0000-0000-000000001001'''
\set EP '''33333333-0000-0000-0000-000000001001'''

-- Nenhuma regra vem cadastrada (RN-014)
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.afirma((SELECT count(*) FROM fluxo.regra_alerta) = 0, 'sem regras pré-cadastradas');

-- Só o Administrador da unidade configura
SELECT teste.ctx(:ENF, :A);
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite)
    VALUES (gen_random_uuid(), %L, 'Tempo total', 'TEMPO_TOTAL', interval '6 hours') $$, :A), 'row-level security');
SELECT teste.ctx(:ADM_A, :A);
INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, etapa_id, limite, acao_esperada, criado_por, criado_em)
VALUES (:R1, :A, 'Transporte atrasado (ilustrativo)', 'TEMPO_NA_ETAPA', teste.etapa(:A, 'AGUARDANDO_TRANSPORTE'),
        interval '2 hours', 'Acionar central de transporte', :ENF, now() - interval '1 year');
SELECT teste.afirma(criado_por = :ADM_A AND criado_em > now() - interval '1 minute', 'autoria pelo banco')
  FROM fluxo.regra_alerta WHERE id = :R1;
INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, categoria)
VALUES (:R2, :A, 'Pendência vencida', 'PENDENCIA_VENCIDA', 'LOGISTICA');

-- Coerência dos parâmetros
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo)
    VALUES (gen_random_uuid(), %L, 'Sem limite', 'TEMPO_TOTAL') $$, :A), 'check');
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite)
    VALUES (gen_random_uuid(), %L, 'Pendência com limite', 'PENDENCIA_VENCIDA', interval '1 hour') $$, :A), 'check');
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite)
    VALUES (gen_random_uuid(), %L, 'Curto demais', 'TEMPO_TOTAL', interval '30 seconds') $$, :A), 'check');
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite, categoria)
    VALUES (gen_random_uuid(), %L, 'Categoria indevida', 'TEMPO_TOTAL', interval '1 hour', 'LOGISTICA') $$, :A), 'check');
-- Etapa de outra unidade é recusada (FK composta)
SELECT teste.ctx(:ADM_B, :B);
SELECT teste.etapa(:B, 'AGUARDANDO_TRANSPORTE') AS etapa_b \gset
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, etapa_id, limite)
    VALUES (gen_random_uuid(), %L, 'Etapa alheia', 'TEMPO_NA_ETAPA', %L, interval '1 hour') $$,
    :A, :'etapa_b'), 'foreign key');

-- Alteração versionada; tipo/unidade imutáveis; sem exclusão
UPDATE fluxo.regra_alerta SET limite = interval '3 hours', versao = versao + 1 WHERE id = :R1;
SELECT teste.espera_erro(format($$ UPDATE fluxo.regra_alerta SET limite = interval '4 hours' WHERE id = %L $$, :R1), 'vers');
SELECT teste.espera_erro(format($$ UPDATE fluxo.regra_alerta SET tipo = 'TEMPO_TOTAL', versao = versao + 1 WHERE id = %L $$, :R1),
                         'não podem ser alterados');
SELECT teste.espera_erro(format($$ DELETE FROM fluxo.regra_alerta WHERE id = %L $$, :R1), 'permission denied');
-- Enfermagem não altera (política restritiva: nenhuma linha alcançada)
SELECT teste.ctx(:ENF, :A);
UPDATE fluxo.regra_alerta SET ativa = false, versao = versao + 1 WHERE id = :R1;
SELECT teste.afirma((SELECT ativa FROM fluxo.regra_alerta WHERE id = :R1), 'enfermagem não desativa regra');
SELECT teste.afirma((SELECT count(*) FROM fluxo.regra_alerta) = 2, 'enfermagem lê as regras da unidade');

-- Isolamento: a unidade B não vê nem usa regras da A
SELECT teste.ctx(:ADM_B, :B);
SELECT teste.afirma((SELECT count(*) FROM fluxo.regra_alerta) = 0, 'B não vê regras da A');
UPDATE fluxo.regra_alerta SET ativa = false, versao = versao + 1 WHERE id = :R1;

-- ---------------------------------------------------------------- ciência
SELECT teste.ctx(:ENF, :A);
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES (:PAC, :A, 'Paciente Alerta');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:EP, :A, :PAC, '00000000-0000-0000-0000-0000000005a1', now() - interval '3 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), now() - interval '3 hours');
INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em, autor_id)
VALUES (gen_random_uuid(), :A, :EP, :R1, 1, now() - interval '3 hours', :ADM_A);
SELECT teste.afirma(autor_id = :ENF, 'autor da ciência pelo banco') FROM fluxo.ciencia_alerta WHERE episodio_id = :EP;
-- Mesma ocorrência não é registrada duas vezes
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em)
    VALUES (gen_random_uuid(), %L, %L, %L, 1, now() - interval '3 hours') $$, :A, :EP, :R1), 'ciencia_alerta_ocorrencia_uq');
-- Regra alterada (outra versão): é outra ocorrência, sem ciência
INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em)
VALUES (gen_random_uuid(), :A, :EP, :R1, 2, now() - interval '3 hours');
-- Imutável
SELECT teste.espera_erro(format($$ UPDATE fluxo.ciencia_alerta SET referencia_em = now() WHERE episodio_id = %L $$, :EP),
                         'permission denied');
-- Pendência de outro episódio é recusada
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-000000001002', :A, 'Outro Paciente');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('33333333-0000-0000-0000-000000001002', :A, '22222222-0000-0000-0000-000000001002',
        '00000000-0000-0000-0000-0000000005a1', now(), teste.etapa(:A, 'EM_ATENDIMENTO'), now());
INSERT INTO fluxo.pendencia (id, unidade_id, episodio_id, categoria, descricao, responsavel_papel, prazo, criticidade_operacional)
VALUES ('77777777-0000-0000-0000-000000001002', :A, '33333333-0000-0000-0000-000000001002', 'LOGISTICA', 'Outra',
        'TRANSPORTE', now() + interval '1 hour', 'BAIXA');
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em, pendencia_id)
    VALUES (gen_random_uuid(), %L, %L, %L, 0, now(), '77777777-0000-0000-0000-000000001002') $$, :A, :EP, :R2),
    'não pertence ao episódio');
-- Outra unidade não registra ciência em episódio da A
SELECT teste.ctx(:ADM_B, :B);
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em)
    VALUES (gen_random_uuid(), %L, %L, %L, 1, now()) $$, :A, :EP, :R1), 'row-level security');
SELECT teste.afirma((SELECT count(*) FROM fluxo.ciencia_alerta) = 0, 'B não vê ciências da A');

-- Episódio encerrado não recebe ciência
SELECT teste.ctx(:ENF, :A);
UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(:A, 'ALTA'), etapa_desde = t.agora,
       desfecho = 'ALTA', encerrado_em = t.agora
  FROM (SELECT clock_timestamp() AS agora) t WHERE id = :EP;
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em)
    VALUES (gen_random_uuid(), %L, %L, %L, 0, now()) $$, :A, :EP, :R2), 'episódio encerrado');

-- Auditoria: regras e ciência registradas, cadeia íntegra
SELECT teste.afirma((SELECT count(*) FROM teste.auditoria_desde(0) WHERE recurso = 'fluxo.regra_alerta') >= 3
                AND (SELECT count(*) FROM teste.auditoria_desde(0) WHERE recurso = 'fluxo.ciencia_alerta') = 2,
                    'regras e ciência auditadas');
SELECT teste.afirma((SELECT count(*) FROM auditoria.verificar_cadeia()) = 0, 'cadeia íntegra');
ROLLBACK;
