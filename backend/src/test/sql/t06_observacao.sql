-- V9: observação operacional (RF-028) — autoria pelo banco, imutável, isolada por unidade, auditada.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set COORD_B '''11111111-1111-1111-1111-000000000003'''
\set PAC '''22222222-0000-0000-0000-000000000601'''
\set EP '''33333333-0000-0000-0000-000000000601'''
\set OBS '''44444444-0000-0000-0000-000000000601'''

SELECT teste.ctx(:ENF, :A);
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES (:PAC, :A, 'Paciente Observacao');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:EP, :A, :PAC, '00000000-0000-0000-0000-0000000005a1', now(), teste.etapa(:A, 'EM_ATENDIMENTO'), now());
SELECT coalesce((SELECT max(id) FROM teste.auditoria_desde(0)), 0) AS aud0 \gset

-- Autor e instante vêm do banco, mesmo se o cliente tentar forjar
INSERT INTO fluxo.observacao (id, unidade_id, episodio_id, texto, autor_id, registrada_em)
VALUES (:OBS, :A, :EP, 'Familia avisada', '11111111-1111-1111-1111-000000000001', now() - interval '1 year');
SELECT teste.afirma(autor_id = :ENF AND registrada_em > now() - interval '1 minute', 'autoria/instante definidos pelo banco')
  FROM fluxo.observacao WHERE id = :OBS;

-- Texto: 3..1000 caracteres úteis
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.observacao (id, unidade_id, episodio_id, texto)
    VALUES (gen_random_uuid(), %L, %L, '  a ') $$, :A, :EP), 'check');
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.observacao (id, unidade_id, episodio_id, texto)
    VALUES (gen_random_uuid(), %L, %L, repeat('x', 1001)) $$, :A, :EP), 'check');

-- Imutável e sem privilégio de alteração para a aplicação
SELECT teste.espera_erro(format($$ UPDATE fluxo.observacao SET texto = 'alterado' WHERE id = %L $$, :OBS), 'permission denied');
SELECT teste.espera_erro(format($$ DELETE FROM fluxo.observacao WHERE id = %L $$, :OBS), 'permission denied');

-- Não aponta para episódio de outra unidade (FK composta) nem grava em unidade fora do contexto (RLS)
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.observacao (id, unidade_id, episodio_id, texto)
    VALUES (gen_random_uuid(), %L, %L, 'outra unidade') $$, :B, :EP), 'row-level security');

-- Auditoria do INSERT com o texto REDIGIDO (texto livre não vai para a trilha)
SELECT teste.afirma(count(*) = 1 AND bool_and(dados -> 'depois' ->> 'texto' = '[redigido]')
                    AND bool_and(position('Familia avisada' IN dados::text) = 0), 'insert auditado com texto redigido')
  FROM teste.auditoria_desde(:aud0) WHERE recurso = 'fluxo.observacao' AND acao = 'CRIAR';

-- Outra unidade não enxerga
SELECT teste.ctx(:COORD_B, :B);
SELECT teste.afirma((SELECT count(*) FROM fluxo.observacao WHERE id = :OBS) = 0, 'RLS: invisível para outra unidade');
ROLLBACK;
