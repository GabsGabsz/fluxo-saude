-- V17: registro das leituras nominais da passagem de plantão. Ator e unidade vindos do contexto,
-- conjunto de episódios gravado uma vez (endereçado por SHA-256), só ids da própria unidade,
-- nada nominal no evento, isolamento por unidade e nenhuma escrita direta pela aplicação.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set COORD_B '''11111111-1111-1111-1111-000000000003'''
\set S1 '''00000000-0000-0000-0000-0000000005a1'''
\set SB '''00000000-0000-0000-0000-0000000005b1'''
\set E1 '''66666666-0000-0000-0000-000000000001'''
\set E2 '''66666666-0000-0000-0000-000000000002'''
\set EB '''66666666-0000-0000-0000-0000000000b1'''

-- Episódios fictícios: dois na unidade A, um na B.
SELECT teste.ctx(:ENF, :A);
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES
    ('46464646-0000-0000-0000-000000000001', :A, 'Paciente Consulta Um'),
    ('46464646-0000-0000-0000-000000000002', :A, 'Paciente Consulta Dois');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde) VALUES
    (:E1, :A, '46464646-0000-0000-0000-000000000001', :S1, now(), teste.etapa(:A, 'EM_ATENDIMENTO'), now()),
    (:E2, :A, '46464646-0000-0000-0000-000000000002', :S1, now(), teste.etapa(:A, 'EM_ATENDIMENTO'), now());
SELECT teste.ctx(:COORD_B, :B);
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('46464646-0000-0000-0000-0000000000b1', :B, 'Paciente Consulta B');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES (:EB, :B, '46464646-0000-0000-0000-0000000000b1', :SB, now(), teste.etapa(:B, 'EM_ATENDIMENTO'), now());

-- ------------------------------------------------------------- registro da leitura
SELECT teste.ctx(:ENF, :A);
SELECT coalesce((SELECT max(id) FROM teste.auditoria_desde(0)), 0) AS aud0 \gset
-- Lista fora de ordem, repetida e com nulo: normalizada antes do hash.
SELECT auditoria.registrar_consulta('CONSULTA_PREVIA_PASSAGEM', 'fluxo.passagem_plantao', NULL,
       ARRAY[:E2, :E1, :E1, NULL]::uuid[], '{"casos": 2}', :A) IS NOT NULL AS ok \gset
SELECT encode(public.digest(convert_to(:E1 || ',' || :E2, 'UTF8'), 'sha256'), 'hex') AS esperado \gset
SELECT teste.afirma(count(*) = 1
                    AND bool_and(usuario_id = :ENF AND unidade_id = :A AND recurso = 'fluxo.passagem_plantao'
                                 AND recurso_id IS NULL AND dados ->> 'conjunto' = :'esperado'
                                 AND (dados ->> 'episodios')::int = 2 AND (dados ->> 'casos')::int = 2),
                    'evento com ator, unidade, hash do conjunto e contagem')
  FROM teste.auditoria_desde(:aud0) WHERE acao = 'CONSULTA_PREVIA_PASSAGEM';
SELECT teste.afirma(bool_and(position('Paciente' IN dados::text) = 0 AND position(:E1 IN dados::text) = 0),
                    'evento sem nomes e sem a lista (só o hash)')
  FROM teste.auditoria_desde(:aud0);
SELECT teste.afirma(episodios = ARRAY[:E1, :E2]::uuid[] AND encode(hash, 'hex') = :'esperado',
                    'conjunto gravado ordenado, sem repetição, conferindo com o hash')
  FROM auditoria.conjunto_consultado WHERE unidade_id = :A;

-- Mesma leitura de novo: novo evento, MESMO conjunto (sem duplicar a lista).
SELECT auditoria.registrar_consulta('CONSULTA_PASSAGEM', 'fluxo.passagem_plantao', gen_random_uuid()::text,
       ARRAY[:E1, :E2]::uuid[], '{"status": "ENTREGUE"}', :A) IS NOT NULL AS ok \gset
SELECT teste.afirma((SELECT count(*) FROM auditoria.conjunto_consultado WHERE unidade_id = :A) = 1
                    AND (SELECT count(*) FROM teste.auditoria_desde(:aud0) WHERE acao LIKE 'CONSULTA\_%PASSAGEM'
                                                                            AND dados ->> 'conjunto' = :'esperado') = 2,
                    'conjunto reaproveitado por leituras iguais');
-- Conjunto vazio (ex.: detalhe de passagem já recebida: a referência é a própria passagem).
SELECT auditoria.registrar_consulta('CONSULTA_PASSAGEM', 'fluxo.passagem_plantao', gen_random_uuid()::text,
       '{}'::uuid[], '{"status": "RECEBIDA"}', :A) IS NOT NULL AS ok \gset
SELECT teste.afirma(cardinality(episodios) = 0, 'conjunto vazio aceito')
  FROM auditoria.conjunto_consultado WHERE unidade_id = :A AND hash = public.digest('', 'sha256');

-- ------------------------------------------------------------- recusas
SELECT teste.espera_erro(format($$ SELECT auditoria.registrar_consulta('CONSULTA_PREVIA_PASSAGEM', 'x', NULL,
       ARRAY[%L, %L]::uuid[], '{}', %L) $$, :E1, :EB, :A), 'episódio fora da unidade');
SELECT teste.espera_erro(format($$ SELECT auditoria.registrar_consulta('CONSULTA_PREVIA_PASSAGEM', 'x', NULL,
       ARRAY[%L]::uuid[], '{}', %L) $$, :EB, :B), 'unidade fora do contexto');
SELECT teste.espera_erro(format($$ SELECT auditoria.registrar_consulta('CRIAR', 'x', NULL, '{}', '{}', %L) $$, :A),
                         'ação de consulta inválida');
SELECT teste.espera_erro(format($$ SELECT auditoria.registrar_consulta('CONSULTA_X_Y', 'x', NULL, '{}',
       '{"conjunto": "forjado"}', %L) $$, :A), 'dados da consulta inválidos');
SELECT teste.espera_erro(format($$ SELECT auditoria.registrar_consulta('CONSULTA_X_Y', 'x', NULL, '{}', '[]', %L) $$, :A),
                         'dados da consulta inválidos');
SELECT teste.ctx(NULL, :A);
SELECT teste.espera_erro(format($$ SELECT auditoria.registrar_consulta('CONSULTA_X_Y', 'x', NULL, '{}', '{}', %L) $$, :A),
                         'contexto de usuário ausente');

-- A aplicação não escreve diretamente no conjunto.
SELECT teste.ctx(:ENF, :A);
SELECT teste.espera_erro(format($$ INSERT INTO auditoria.conjunto_consultado (unidade_id, hash, episodios)
       VALUES (%L, public.digest('', 'sha256'), '{}') $$, :B), 'permission denied');
SELECT teste.espera_erro($$ UPDATE auditoria.conjunto_consultado SET episodios = '{}' $$, 'permission denied');
SELECT teste.espera_erro($$ DELETE FROM auditoria.conjunto_consultado $$, 'permission denied');

-- ------------------------------------------------------------- isolamento
SELECT teste.ctx(:COORD_B, :B);
SELECT teste.afirma(count(*) = 0, 'outra unidade não vê os conjuntos da A') FROM auditoria.conjunto_consultado;
SELECT teste.afirma(count(*) = 0, 'outra unidade não vê os eventos de consulta da A')
  FROM auditoria.registro WHERE acao LIKE 'CONSULTA\_%PASSAGEM';
SELECT auditoria.registrar_consulta('CONSULTA_PREVIA_PASSAGEM', 'fluxo.passagem_plantao', NULL,
       ARRAY[:EB]::uuid[], '{"casos": 1}', :B) IS NOT NULL AS ok \gset
SELECT teste.afirma(count(*) = 1 AND bool_and(:EB = ANY (episodios)), 'B vê só o próprio conjunto')
  FROM auditoria.conjunto_consultado;
SELECT teste.ctx(:ENF, :A);
SELECT teste.afirma(count(*) = 2 AND bool_and(NOT (:EB = ANY (episodios))), 'A continua vendo só os seus')
  FROM auditoria.conjunto_consultado;

-- A cadeia continua íntegra com os eventos novos.
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM auditoria.verificar_cadeia()), 'cadeia de auditoria íntegra');
ROLLBACK;
