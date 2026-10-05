-- Regras introduzidas pela ERS v1.1.
BEGIN;
SELECT teste.ctx('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000a');
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set SETOR '''00000000-0000-0000-0000-0000000005a1'''

INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-0000000000a1', :A, 'Paciente Concorrência');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('33333333-0000-0000-0000-0000000000a1', :A, '22222222-0000-0000-0000-0000000000a1', :SETOR,
        now() - interval '1 hour', teste.etapa(:A, 'EM_ATENDIMENTO'), now() - interval '1 hour');

-- RF-036 / RNF-014: UPDATE sem incrementar a versão é recusado (sem sobrescrita silenciosa)
SELECT teste.espera_erro($$
    UPDATE fluxo.episodio SET destino_descricao = 'x' WHERE id = '33333333-0000-0000-0000-0000000000a1'
$$, 'controle de concorrência');
SELECT teste.espera_erro($$
    UPDATE fluxo.episodio SET versao = versao + 2, destino_descricao = 'x' WHERE id = '33333333-0000-0000-0000-0000000000a1'
$$, 'controle de concorrência');
-- Padrão otimista da aplicação: WHERE versao = lida. Segunda escrita com versão antiga não afeta nada.
UPDATE fluxo.episodio SET versao = 1, destino_descricao = 'Hospital Regional' WHERE id = '33333333-0000-0000-0000-0000000000a1' AND versao = 0;
UPDATE fluxo.episodio SET versao = 1, destino_descricao = 'Outro' WHERE id = '33333333-0000-0000-0000-0000000000a1' AND versao = 0;
SELECT teste.afirma((SELECT destino_descricao = 'Hospital Regional' AND versao = 1 FROM fluxo.episodio
                      WHERE id = '33333333-0000-0000-0000-0000000000a1'), 'escrita concorrente com versão antiga não sobrescreve');

-- RNF-017: retroatividade é parâmetro da unidade (A = 48 h; B = padrão 24 h)
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-0000000000a2', :A, 'Paciente Retro');
INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:A, '22222222-0000-0000-0000-0000000000a2', :SETOR, now() - interval '30 hours',
        teste.etapa(:A, 'EM_ATENDIMENTO'), now() - interval '30 hours');
SELECT teste.ctx('11111111-1111-1111-1111-000000000003', '00000000-0000-0000-0000-00000000000b');
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-0000000000b2', :B, 'Paciente Retro B');
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
    VALUES (%L, '22222222-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-0000000005b1', now() - interval '30 hours',
            teste.etapa(%L, 'EM_ATENDIMENTO'), now() - interval '30 hours')
$$, :B, :B), 'retroatividade permitida pela unidade');
SELECT teste.ctx('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000a');

-- RNF-017: evento com horário manual exige marcação e justificativa; com elas é aceito
SELECT teste.espera_erro($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '33333333-0000-0000-0000-0000000000a1',
            'OBSERVACAO_REGISTRADA', now() - interval '2 hours')
$$, 'exige marcação e justificativa');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
VALUES (gen_random_uuid(), :A, '33333333-0000-0000-0000-0000000000a1', 'OBSERVACAO_REGISTRADA', now() - interval '2 hours',
        '{"ajuste_manual": "true", "ajuste_justificativa": "Registro após queda do sistema (contingência)"}');
SELECT teste.espera_erro($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '33333333-0000-0000-0000-0000000000a1',
            'OBSERVACAO_REGISTRADA', now() - interval '3 days', '{"ajuste_manual": "true", "ajuste_justificativa": "x y z"}')
$$, 'além da retroatividade');

-- RF-035: motivo "causa em investigação" existe e exige justificativa
SELECT teste.afirma((SELECT categoria = 'NAO_DEFINIDA' AND exige_detalhe FROM fluxo.motivo_bloqueio
                      WHERE id = teste.motivo(:A, 'CAUSA_EM_INVESTIGACAO')), 'motivo em investigação provisionado');
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(%L, 'AGUARDANDO_DECISAO'), etapa_desde = now(),
           motivo_bloqueio_id = teste.motivo(%L, 'CAUSA_EM_INVESTIGACAO'), bloqueio_desde = now()
     WHERE id = '33333333-0000-0000-0000-0000000000a1'
$$, :A, :A), 'exige detalhamento');
UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(:A, 'AGUARDANDO_DECISAO'), etapa_desde = now(),
       motivo_bloqueio_id = teste.motivo(:A, 'CAUSA_EM_INVESTIGACAO'), bloqueio_desde = now(),
       motivo_detalhe = 'Equipe ainda apurando o que impede a decisão'
 WHERE id = '33333333-0000-0000-0000-0000000000a1';

-- RF-037: reconciliação de cadastro duplicado, sem excluir histórico
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-0000000000d1', :A, 'Maria Souza');
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-0000000000d2', :A, 'Maria de Souza');
-- duplicado com episódio ativo não pode ser reconciliado
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('33333333-0000-0000-0000-0000000000d2', :A, '22222222-0000-0000-0000-0000000000d2', :SETOR, now(),
        teste.etapa(:A, 'EM_ATENDIMENTO'), now());
SELECT teste.espera_erro($$
    UPDATE fluxo.paciente SET versao = versao + 1, reconciliado_com_id = '22222222-0000-0000-0000-0000000000d1',
           reconciliado_em = now(), justificativa_reconciliacao = 'Mesmo paciente, cadastro duplicado'
     WHERE id = '22222222-0000-0000-0000-0000000000d2'
$$, 'episódio ativo');
UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(:A, 'CANCELADO_ENCERRADO'), etapa_desde = clock_timestamp(),
       desfecho = 'ENCERRAMENTO_ADMINISTRATIVO', encerrado_em = clock_timestamp(),
       justificativa_encerramento = 'Episódio aberto em cadastro duplicado'
 WHERE id = '33333333-0000-0000-0000-0000000000d2';
UPDATE fluxo.paciente SET versao = versao + 1, reconciliado_com_id = '22222222-0000-0000-0000-0000000000d1',
       reconciliado_em = now(), justificativa_reconciliacao = 'Mesmo paciente, cadastro duplicado'
 WHERE id = '22222222-0000-0000-0000-0000000000d2';
SELECT teste.afirma(reconciliado_por = '11111111-1111-1111-1111-000000000002' AND reconciliado_em IS NOT NULL,
                    'reconciliação com autor do banco')
  FROM fluxo.paciente WHERE id = '22222222-0000-0000-0000-0000000000d2';
SELECT teste.afirma(EXISTS (SELECT 1 FROM fluxo.episodio WHERE id = '33333333-0000-0000-0000-0000000000d2'),
                    'histórico do cadastro duplicado preservado');
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
    VALUES (%L, '22222222-0000-0000-0000-0000000000d2', %L, now(), teste.etapa(%L, 'EM_ATENDIMENTO'), now())
$$, :A, :SETOR, :A), 'cadastro reconciliado');
SELECT teste.espera_erro($$
    UPDATE fluxo.paciente SET versao = versao + 1, nome = 'Outro' WHERE id = '22222222-0000-0000-0000-0000000000d2'
$$, 'reconciliado é imutável');
SELECT teste.espera_erro($$
    UPDATE fluxo.paciente SET versao = versao + 1, reconciliado_com_id = id, reconciliado_em = now(),
           justificativa_reconciliacao = 'xxx' WHERE id = '22222222-0000-0000-0000-0000000000d1'
$$, 'paciente_reconciliacao_coerente');
ROLLBACK;

