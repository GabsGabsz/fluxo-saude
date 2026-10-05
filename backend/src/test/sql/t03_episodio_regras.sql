-- Regras do episódio, etapas, bloqueios, protocolo e desfecho (garantias do banco).
-- Reproduz o cenário da ERS §11 (transferência para ortopedia).
BEGIN;
SELECT teste.ctx('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000a');

\set A '''00000000-0000-0000-0000-00000000000a'''
\set EP '''33333333-0000-0000-0000-000000000001'''
\set PAC '''22222222-0000-0000-0000-000000000020'''
\set SETOR '''00000000-0000-0000-0000-0000000005a1'''

INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES (:PAC, :A, 'Paciente Ortopedia');

-- Não abre em etapa de desfecho
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
    VALUES (%L, %L, %L, now() - interval '1 hour', teste.etapa(%L, 'ALTA'), now() - interval '1 hour')
$$, :A, :PAC, :SETOR, :A), 'etapa de desfecho');

-- Não abre com entrada no futuro
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
    VALUES (%L, %L, %L, now() + interval '1 hour', teste.etapa(%L, 'EM_ATENDIMENTO'), now() + interval '1 hour')
$$, :A, :PAC, :SETOR, :A), 'futuro');

-- 08:12 — Entrada (aqui: há 30 horas)
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:EP, :A, :PAC, :SETOR, now() - interval '30 hours', teste.etapa(:A, 'EM_ATENDIMENTO'), now() - interval '30 hours');

SELECT teste.afirma((SELECT criado_por FROM fluxo.episodio WHERE id = :EP) = '11111111-1111-1111-1111-000000000002',
                    'criado_por vem do contexto');
-- Autoria forjada é sobrescrita pelo banco
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-000000000099', :A, 'Paciente Forja');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde, criado_por, criado_em)
VALUES ('33333333-0000-0000-0000-000000000099', :A, '22222222-0000-0000-0000-000000000099', :SETOR, now(),
        teste.etapa(:A, 'EM_ATENDIMENTO'), now(), '11111111-1111-1111-1111-000000000001', now() - interval '1 year');
SELECT teste.afirma(criado_por = '11111111-1111-1111-1111-000000000002' AND criado_em > now() - interval '1 minute',
                    'criado_por/criado_em forjados foram sobrescritos')
  FROM fluxo.episodio WHERE id = '33333333-0000-0000-0000-000000000099';

-- RF-003 (v1.1): segundo episódio ativo do mesmo paciente é DETECTADO e exige
-- justificativa — não é bloqueado.
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
    VALUES (%L, %L, %L, now(), teste.etapa(%L, 'EM_ATENDIMENTO'), now())
$$, :A, :PAC, :SETOR, :A), 'possível duplicidade');
SAVEPOINT duplicidade;
INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde, justificativa_duplicidade)
VALUES (:A, :PAC, :SETOR, now(), teste.etapa(:A, 'EM_ATENDIMENTO'), now(), 'Retorno após evasão; episódio anterior ainda não encerrado');
SELECT teste.afirma((SELECT count(*) FROM fluxo.episodio WHERE paciente_id = :PAC AND encerrado_em IS NULL) = 2,
                    'duplicidade justificada é aceita');
ROLLBACK TO SAVEPOINT duplicidade;

-- Transição inexistente no grafo é recusada
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(%L, 'ACEITO'), etapa_desde = now() WHERE id = %L
$$, :A, :EP), 'transição de etapa não permitida');

-- RN-003: etapa de espera sem motivo é recusada
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(%L, 'AGUARDANDO_SOLICITACAO_TRANSFERENCIA'),
                              etapa_desde = now() - interval '28 hours' WHERE id = %L
$$, :A, :EP), 'exige motivo de bloqueio');

-- "Outros" exige detalhamento
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(%L, 'AGUARDANDO_SOLICITACAO_TRANSFERENCIA'),
                              etapa_desde = now() - interval '28 hours',
                              motivo_bloqueio_id = teste.motivo(%L, 'OUTROS'), bloqueio_desde = now() - interval '28 hours'
     WHERE id = %L
$$, :A, :A, :EP), 'exige detalhamento');

-- Mudar etapa_desde sem mudar de etapa é recusado (relógio não pode ser "zerado")
SELECT teste.espera_erro(format($$ UPDATE fluxo.episodio SET versao = versao + 1, etapa_desde = now() WHERE id = %L $$, :EP),
                         'só muda com mudança de etapa');

-- 10:02 — Decisão: aguardando solicitação de transferência, com motivo
UPDATE fluxo.episodio
   SET versao = versao + 1, etapa_id = teste.etapa(:A, 'AGUARDANDO_SOLICITACAO_TRANSFERENCIA'),
       etapa_desde = now() - interval '28 hours',
       motivo_bloqueio_id = teste.motivo(:A, 'SOLICITACAO_NAO_ENVIADA'),
       bloqueio_desde = now() - interval '28 hours',
       especialidade_requerida_id = (SELECT id FROM fluxo.especialidade WHERE codigo = 'ORTOPEDIA')
 WHERE id = :EP;

-- Nova etapa não pode começar antes da anterior
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(%L, 'EM_ATENDIMENTO'), etapa_desde = now() - interval '29 hours',
                              motivo_bloqueio_id = NULL, bloqueio_desde = NULL WHERE id = %L
$$, :A, :EP), 'não pode começar antes');

-- RF-009: "Transferência solicitada" exige protocolo externo
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(%L, 'TRANSFERENCIA_SOLICITADA'), etapa_desde = now() - interval '27 hours',
                              motivo_bloqueio_id = teste.motivo(%L, 'AGUARDANDO_ANALISE_ACEITE')
     WHERE id = %L
$$, :A, :A, :EP), 'exige protocolo externo');

-- 10:14 — Protocolo externo registrado
UPDATE fluxo.episodio
   SET versao = versao + 1, etapa_id = teste.etapa(:A, 'TRANSFERENCIA_SOLICITADA'), etapa_desde = now() - interval '27 hours',
       motivo_bloqueio_id = teste.motivo(:A, 'AGUARDANDO_ANALISE_ACEITE'), bloqueio_desde = now() - interval '27 hours',
       protocolo_sistema = 'REGULA_PI', protocolo_numero = '2026-000123'
 WHERE id = :EP;

-- Pendência: exatamente um responsável
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.pendencia (unidade_id, episodio_id, categoria, descricao, responsavel_usuario_id, responsavel_papel, prazo, criticidade_operacional)
    VALUES (%L, %L, 'REGULACAO', 'Atualizar informações', '11111111-1111-1111-1111-000000000002', 'COORDENACAO_FLUXO', now() + interval '2 hours', 'ALTA')
$$, :A, :EP), 'pendencia_um_responsavel');

-- Responsável precisa estar lotado na unidade
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.pendencia (unidade_id, episodio_id, categoria, descricao, responsavel_usuario_id, prazo, criticidade_operacional)
    VALUES (%L, %L, 'REGULACAO', 'Atualizar informações', '11111111-1111-1111-1111-000000000003', now() + interval '2 hours', 'ALTA')
$$, :A, :EP), 'não pertence à unidade');

-- Prazo no passado é recusado
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.pendencia (unidade_id, episodio_id, categoria, descricao, responsavel_papel, prazo, criticidade_operacional)
    VALUES (%L, %L, 'REGULACAO', 'Atualizar informações', 'COORDENACAO_FLUXO', now() - interval '1 day', 'ALTA')
$$, :A, :EP), 'prazo da pendência no passado');

INSERT INTO fluxo.pendencia (id, unidade_id, episodio_id, categoria, descricao, responsavel_papel, prazo, criticidade_operacional)
VALUES ('44444444-0000-0000-0000-000000000001', :A, :EP, 'REGULACAO', 'Atualizar informações na regulação',
        'COORDENACAO_FLUXO', now() + interval '2 hours', 'ALTA');

-- Configuração endurecida depois não trava episódios já na etapa (ex.: troca de setor)
UPDATE fluxo.motivo_bloqueio SET versao = versao + 1, exige_detalhe = true WHERE id = teste.motivo(:A, 'AGUARDANDO_ANALISE_ACEITE');
UPDATE fluxo.episodio SET versao = versao + 1, setor_id = '00000000-0000-0000-0000-0000000005a2' WHERE id = :EP;
UPDATE fluxo.episodio SET versao = versao + 1, setor_id = :SETOR WHERE id = :EP;
UPDATE fluxo.motivo_bloqueio SET versao = versao + 1, exige_detalhe = false WHERE id = teste.motivo(:A, 'AGUARDANDO_ANALISE_ACEITE');

-- Semântica de etapa é imutável; etapa inicial não pode ser desativada
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT teste.espera_erro(format($$ UPDATE fluxo.etapa SET versao = versao + 1, natureza = 'DESFECHO', desfecho = 'ALTA' WHERE id = teste.etapa(%L, 'ACEITO') $$, :A),
                         'não podem ser alterados');
SELECT teste.espera_erro(format($$ UPDATE fluxo.etapa SET versao = versao + 1, ativa = false WHERE id = teste.etapa(%L, 'EM_ATENDIMENTO') $$, :A),
                         'etapa inicial não pode ser desativada');
SELECT teste.espera_erro(format($$ UPDATE fluxo.motivo_bloqueio SET versao = versao + 1, categoria = 'OUTROS' WHERE id = teste.motivo(%L, 'SEM_VAGA') $$, :A),
                         'não podem ser alterados');
SELECT teste.ctx('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000a');

-- RN-005: encerrar sem resolução é recusado
SELECT teste.espera_erro($$
    UPDATE fluxo.pendencia SET versao = versao + 1, status = 'RESOLVIDA', encerrada_em = now() WHERE id = '44444444-0000-0000-0000-000000000001'
$$, 'pendencia_encerramento_coerente');

UPDATE fluxo.pendencia SET versao = versao + 1, status = 'RESOLVIDA', encerrada_em = now(), resolucao = 'Informações atualizadas no sistema oficial'
 WHERE id = '44444444-0000-0000-0000-000000000001';
SELECT teste.afirma((SELECT encerrada_por FROM fluxo.pendencia WHERE id = '44444444-0000-0000-0000-000000000001')
                    = '11111111-1111-1111-1111-000000000002', 'encerrada_por vem do contexto');

-- Pendência encerrada é imutável
SELECT teste.espera_erro($$
    UPDATE fluxo.pendencia SET versao = versao + 1, descricao = 'alterada' WHERE id = '44444444-0000-0000-0000-000000000001'
$$, 'imutável');

-- Dia seguinte 11:20 — Aceite; 11:22 — pendência de transporte
UPDATE fluxo.episodio
   SET versao = versao + 1, etapa_id = teste.etapa(:A, 'ACEITO'), etapa_desde = now() - interval '3 hours',
       motivo_bloqueio_id = NULL, bloqueio_desde = NULL
 WHERE id = :EP;

INSERT INTO fluxo.pendencia (id, unidade_id, episodio_id, categoria, descricao, responsavel_setor_id, prazo, criticidade_operacional)
VALUES ('44444444-0000-0000-0000-000000000002', :A, :EP, 'LOGISTICA', 'Acionar transporte',
        '00000000-0000-0000-0000-0000000005a2', now() + interval '1 hour', 'ALTA');

-- Desfecho incoerente (encerrado_em diferente do início da etapa de desfecho) é recusado
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(%L, 'TRANSFERIDO'), etapa_desde = now() - interval '1 hour',
                              desfecho = 'TRANSFERENCIA', encerrado_em = now() WHERE id = %L
$$, :A, :EP), 'encerramento incoerente');
-- Desfecho em etapa não terminal é recusado
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, desfecho = 'ALTA', encerrado_em = now() WHERE id = %L
$$, :EP), 'só pode ser registrado em etapa de desfecho');

-- RN-008: encerrar o episódio deixando pendência aberta é recusado no COMMIT
-- (constraint trigger adiável; SET CONSTRAINTS antecipa a verificação no teste).
SAVEPOINT antes_encerrar;
UPDATE fluxo.episodio
   SET versao = versao + 1, etapa_id = teste.etapa(:A, 'TRANSFERIDO'), etapa_desde = now() - interval '1 hour',
       desfecho = 'TRANSFERENCIA', encerrado_em = now() - interval '1 hour'
 WHERE id = :EP;
SELECT teste.espera_erro('SET CONSTRAINTS ALL IMMEDIATE', 'pendências abertas');
ROLLBACK TO SAVEPOINT antes_encerrar;

-- "Encerrada por desfecho" com episódio ainda aberto também é recusada no COMMIT
SAVEPOINT desfecho_falso;
UPDATE fluxo.pendencia SET versao = versao + 1, status = 'ENCERRADA_POR_DESFECHO', resolucao = 'Encerrada pelo desfecho'
 WHERE id = '44444444-0000-0000-0000-000000000002';
SELECT teste.espera_erro('SET CONSTRAINTS ALL IMMEDIATE', 'exige episódio encerrado');
ROLLBACK TO SAVEPOINT desfecho_falso;

-- 13:10 — Saída: Transferido. Ordem "episódio antes da pendência" também funciona.
UPDATE fluxo.episodio
   SET versao = versao + 1, etapa_id = teste.etapa(:A, 'TRANSFERIDO'), etapa_desde = now() - interval '1 hour',
       desfecho = 'TRANSFERENCIA', encerrado_em = now() - interval '1 hour'
 WHERE id = :EP;
-- Após o encerramento, a pendência só pode ser encerrada pelo desfecho
SELECT teste.espera_erro($$
    UPDATE fluxo.pendencia SET versao = versao + 1, status = 'RESOLVIDA', resolucao = 'Resolvida depois' WHERE id = '44444444-0000-0000-0000-000000000002'
$$, 'episódio encerrado');
UPDATE fluxo.pendencia SET versao = versao + 1, status = 'ENCERRADA_POR_DESFECHO',
       resolucao = 'Encerrada automaticamente pelo desfecho do episódio: TRANSFERENCIA'
 WHERE id = '44444444-0000-0000-0000-000000000002';
SET CONSTRAINTS ALL IMMEDIATE;   -- verificações de consistência do desfecho passam
SET CONSTRAINTS ALL DEFERRED;

-- Episódio encerrado é imutável e não aceita pendências
SELECT teste.espera_erro(format($$ UPDATE fluxo.episodio SET versao = versao + 1, destino_descricao = 'x' WHERE id = %L $$, :EP), 'imutável');
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.pendencia (unidade_id, episodio_id, categoria, descricao, responsavel_papel, prazo, criticidade_operacional)
    VALUES (%L, %L, 'LOGISTICA', 'Nova pendência', 'TRANSPORTE', now() + interval '1 hour', 'BAIXA')
$$, :A, :EP), 'episódio encerrado');

-- Novo episódio para o mesmo paciente é permitido após o encerramento
INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:A, :PAC, :SETOR, now(), teste.etapa(:A, 'EM_ATENDIMENTO'), now());

-- Encerramento administrativo exige justificativa
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-000000000021', :A, 'Paciente Cancelado');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('33333333-0000-0000-0000-000000000002', :A, '22222222-0000-0000-0000-000000000021', :SETOR,
        now() - interval '1 hour', teste.etapa(:A, 'EM_ATENDIMENTO'), now() - interval '1 hour');
SELECT teste.espera_erro(format($$
    UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa(%L, 'CANCELADO_ENCERRADO'), etapa_desde = now(),
                              desfecho = 'ENCERRAMENTO_ADMINISTRATIVO', encerrado_em = now()
     WHERE id = '33333333-0000-0000-0000-000000000002'
$$, :A), 'exige justificativa');

-- Linha do tempo: autor e registro definidos pelo banco (tentativa de forjar é sobrescrita)
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, autor_id, registrado_em)
VALUES ('55555555-0000-0000-0000-000000000009', :A, :EP, 'OBSERVACAO_REGISTRADA', now(),
        '11111111-1111-1111-1111-000000000001', now() - interval '10 days');
SELECT teste.afirma(autor_id = '11111111-1111-1111-1111-000000000002' AND registrado_em > now() - interval '1 minute',
                    'autor/registro forjados foram sobrescritos')
  FROM fluxo.evento_episodio WHERE id = '55555555-0000-0000-0000-000000000009';
-- dados do evento: só objeto plano com valores escalares curtos (sem texto livre)
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
    VALUES (gen_random_uuid(), %L, %L, 'OBSERVACAO_REGISTRADA', now(), %L)
$$, :A, :EP, jsonb_build_object('texto', repeat('x', 500))), 'evento_episodio_dados_check');
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
    VALUES (gen_random_uuid(), %L, %L, 'OBSERVACAO_REGISTRADA', now(), '{"a": {"b": 1}}')
$$, :A, :EP), 'evento_episodio_dados_check');
-- Correção só pode apontar para evento do mesmo episódio
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, corrige_evento_id)
    VALUES (gen_random_uuid(), %L, '33333333-0000-0000-0000-000000000002', 'CORRECAO', now(), '55555555-0000-0000-0000-000000000009')
$$, :A), 'foreign key');
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados)
VALUES ('55555555-0000-0000-0000-000000000001', :A, :EP, 'EPISODIO_ENCERRADO', now(), '{"desfecho":"TRANSFERENCIA"}');
SELECT teste.espera_erro($$ UPDATE fluxo.evento_episodio SET tipo = 'X' $$, 'permission denied');
SELECT teste.espera_erro($$ DELETE FROM fluxo.evento_episodio $$, 'permission denied');
-- Evento retroativo é permitido; evento "no futuro" não
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em)
    VALUES (gen_random_uuid(), %L, %L, 'OBSERVACAO_REGISTRADA', now() + interval '1 day')
$$, :A, :EP), 'evento_episodio_check');

-- Toda mudança do episódio gerou auditoria com o autor correto
SELECT teste.afirma((SELECT count(*) FROM auditoria.registro
                      WHERE recurso = 'fluxo.episodio' AND recurso_id = '33333333-0000-0000-0000-000000000001') >= 5,
                    'mudanças do episódio auditadas');
SELECT teste.afirma((SELECT bool_and(usuario_id = '11111111-1111-1111-1111-000000000002') FROM auditoria.registro
                      WHERE recurso = 'fluxo.episodio' AND recurso_id = '33333333-0000-0000-0000-000000000001'),
                    'autor correto em toda auditoria do episódio');

-- CNS
SELECT teste.afirma(fluxo.cns_valido('291417776317066') AND fluxo.cns_valido('719961983914549'), 'CNS válidos');
SELECT teste.afirma(NOT fluxo.cns_valido('291417776317067') AND NOT fluxo.cns_valido('391417776317066')
                    AND NOT fluxo.cns_valido('12345'), 'CNS inválidos');

ROLLBACK;
