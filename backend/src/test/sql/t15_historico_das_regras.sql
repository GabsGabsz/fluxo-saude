-- V18: cada versão de uma regra de alerta fica guardada, imutável, com os dados DAQUELA versão
-- (nome, limite, ação esperada, ativa). A passagem de plantão usa isso para exibir alertas
-- históricos sem trocar por dados da configuração atual.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set ADM_A '''11111111-1111-1111-1111-000000000001'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set COORD_B '''11111111-1111-1111-1111-000000000003'''
\set R '''67676767-0000-0000-0000-000000000001'''

SELECT teste.ctx(:ADM_A, :A);
INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite, acao_esperada)
VALUES (:R, :A, 'Permanencia longa A', 'TEMPO_TOTAL', interval '6 hours', 'Acionar coordenacao');
UPDATE fluxo.regra_alerta SET nome = 'Permanencia longa B', limite = interval '4 hours', acao_esperada = 'Acionar direcao',
       versao = versao + 1 WHERE id = :R;
UPDATE fluxo.regra_alerta SET ativa = false, versao = versao + 1 WHERE id = :R;

SELECT teste.afirma(count(*) = 3, 'uma linha por versão') FROM fluxo.regra_alerta_versao WHERE regra_id = :R;
SELECT teste.afirma(nome = 'Permanencia longa A' AND limite = interval '6 hours' AND acao_esperada = 'Acionar coordenacao'
                    AND ativa AND unidade_id = :A, 'versão 0 preservada como era')
  FROM fluxo.regra_alerta_versao WHERE regra_id = :R AND versao = 0;
SELECT teste.afirma(nome = 'Permanencia longa B' AND limite = interval '4 hours' AND acao_esperada = 'Acionar direcao' AND ativa,
                    'versão 1 com os dados alterados')
  FROM fluxo.regra_alerta_versao WHERE regra_id = :R AND versao = 1;
SELECT teste.afirma(NOT ativa AND nome = 'Permanencia longa B', 'versão 2 = desativação')
  FROM fluxo.regra_alerta_versao WHERE regra_id = :R AND versao = 2;

-- A aplicação só lê o histórico.
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.regra_alerta_versao (regra_id, versao, unidade_id, nome, tipo, ativa,
       vigente_desde) VALUES (%L, 9, %L, 'Forjada', 'TEMPO_TOTAL', true, now()) $$, :R, :A), 'permission denied');
SELECT teste.espera_erro(format($$ UPDATE fluxo.regra_alerta_versao SET nome = 'X' WHERE regra_id = %L $$, :R),
                         'permission denied');
SELECT teste.espera_erro(format($$ DELETE FROM fluxo.regra_alerta_versao WHERE regra_id = %L $$, :R), 'permission denied');

-- Quem lê a passagem (enfermagem) vê o histórico da própria unidade; outra unidade, não.
SELECT teste.ctx(:ENF, :A);
SELECT teste.afirma(count(*) = 3, 'enfermagem da unidade lê as versões') FROM fluxo.regra_alerta_versao WHERE regra_id = :R;
SELECT teste.ctx(:COORD_B, :B);
SELECT teste.afirma(count(*) = 0, 'outra unidade não vê o histórico') FROM fluxo.regra_alerta_versao WHERE regra_id = :R;
ROLLBACK;
