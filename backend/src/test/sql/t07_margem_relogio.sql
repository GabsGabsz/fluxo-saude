-- V10: margem de 1 min entre o relógio da aplicação e o do banco no ajuste manual (RNF-017).
-- Unidade A: limiar 5 min (padrão), retroatividade 48 h (fixture).
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set PAC '''22222222-0000-0000-0000-000000000701'''
\set EP '''33333333-0000-0000-0000-000000000701'''

SELECT teste.ctx(:ENF, :A);
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES (:PAC, :A, 'Paciente Margem');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:EP, :A, :PAC, '00000000-0000-0000-0000-0000000005a1', clock_timestamp() - interval '47 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), clock_timestamp() - interval '47 hours');

-- Na fronteira (5 min 30 s): a aplicação pode ter avaliado antes do limiar → aceito sem marcação
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em)
VALUES (gen_random_uuid(), :A, :EP, 'OBSERVACAO_REGISTRADA', clock_timestamp() - interval '5 minutes 30 seconds');

-- Além do limiar + margem: marcação e justificativa obrigatórias
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em)
    VALUES (gen_random_uuid(), %L, %L, 'OBSERVACAO_REGISTRADA', clock_timestamp() - interval '7 minutes')
$$, :A, :EP), 'justificativa de ajuste');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
VALUES (gen_random_uuid(), :A, :EP, 'OBSERVACAO_REGISTRADA', clock_timestamp() - interval '7 minutes',
        '{"ajuste_manual":"true","ajuste_justificativa":"Registro atrasado"}');

-- Marcação sem justificativa é recusada mesmo abaixo do limiar
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
    VALUES (gen_random_uuid(), %L, %L, 'OBSERVACAO_REGISTRADA', clock_timestamp() - interval '1 minute',
            '{"ajuste_manual":"true"}')
$$, :A, :EP), 'justificativa de ajuste');

-- Retroatividade: 48 h + margem
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
    VALUES (gen_random_uuid(), %L, %L, 'OBSERVACAO_REGISTRADA', clock_timestamp() - interval '48 hours 2 minutes',
            '{"ajuste_manual":"true","ajuste_justificativa":"Muito antigo"}')
$$, :A, :EP), 'retroatividade');
SELECT teste.afirma(fluxo.limite_passado(:A) < clock_timestamp() - interval '48 hours', 'limite com margem');
SELECT teste.afirma(fluxo.limite_passado(:A) > clock_timestamp() - interval '48 hours 2 minutes', 'margem de só 1 min');
ROLLBACK;
