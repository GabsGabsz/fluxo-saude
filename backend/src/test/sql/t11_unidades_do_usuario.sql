-- V15: unidades do usuário (para a troca de unidade) — só as do próprio usuário.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set ADM_B '''11111111-1111-1111-1111-000000000004'''

SELECT teste.ctx(NULL, NULL);
SELECT teste.espera_erro($$ SELECT * FROM fluxo.unidades_do_usuario() $$, 'contexto de usuário ausente');

SELECT teste.ctx(:ENF, :A);
SELECT teste.afirma(array_agg(codigo ORDER BY codigo) = ARRAY['UPA_BJ'] AND bool_and(fuso_horario = 'America/Fortaleza'),
                    'enfermeira só na A') FROM fluxo.unidades_do_usuario();
SELECT teste.afirma((SELECT count(*) FROM fluxo.unidade) = 1, 'RLS continua mostrando só a unidade ativa');

-- admin.b vincula a enfermeira também à B: agora ela vê as duas (só as dela)
SELECT teste.ctx(:ADM_B, :B);
SELECT versao AS v FROM fluxo.admin_localizar_por_login('enf.a') \gset
SELECT fluxo.admin_definir_papeis(:ENF, :v, ARRAY['ENFERMAGEM']::fluxo.papel[]);
SELECT teste.ctx(:ENF, :A);
SELECT teste.afirma(array_agg(codigo ORDER BY codigo) = ARRAY['HOSP_B', 'UPA_BJ'], 'duas unidades')
  FROM fluxo.unidades_do_usuario();
-- admin.b (só na B) não enxerga a A por aqui
SELECT teste.ctx(:ADM_B, :B);
SELECT teste.afirma(array_agg(codigo) = ARRAY['HOSP_B'], 'admin.b só na B') FROM fluxo.unidades_do_usuario();
ROLLBACK;
