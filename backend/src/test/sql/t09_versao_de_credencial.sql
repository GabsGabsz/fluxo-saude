-- V12: versão de credencial — sessão antiga recusada após troca/redefinição de senha,
-- desativação/reativação e mudança de papéis, MESMO que a sessão não tenha sido apagada.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set ADM_A '''11111111-1111-1111-1111-000000000001'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set HASH2 '''{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=19456,t=2,p=1$dGVzdGU$c2VndW5kYQ'''
\set HASH3 '''{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=19456,t=2,p=1$dGVzdGU$dGVyY2VpcmE'''

-- ---------------------------------------------------------------- versão exigida
SELECT teste.ctx(NULL, NULL);
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, 1) = ARRAY['ENFERMAGEM'], 'versão vigente aceita');
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, 2) IS NULL, 'versão diferente recusada');
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, NULL) IS NULL, 'sem versão: recusado (falha fechada)');
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, 0) IS NULL, 'sessão anterior à V12 (0) recusada');
SELECT teste.espera_erro($$ SELECT fluxo.aplicar_contexto('11111111-1111-1111-1111-000000000002', NULL, NULL, NULL) $$,
                         'does not exist');   -- não há mais a assinatura sem a versão

-- ---------------------------------------------------------------- login concorrente com redefinição
SELECT teste.ctx(NULL, NULL);
SELECT credencial_versao AS v_login FROM fluxo.credencial_para_login('enf.a') \gset
SELECT teste.afirma(:v_login = 1, 'versão lida junto com o hash no login');
-- ... enquanto o Argon2 confere a senha ANTIGA, o administrador redefine a senha:
SELECT teste.ctx(:ADM_A, :A);
SELECT fluxo.admin_definir_senha_provisoria(:ENF, (SELECT versao FROM fluxo.usuario WHERE id = :ENF), :HASH2);
SELECT teste.ctx(NULL, NULL);
-- O login lê o perfil com a versão lida junto com o hash: o banco recusa (sem sessão)
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, NULL, NULL, NULL, :v_login) IS NULL,
                    'login com a senha antiga é recusado na leitura do perfil');
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, :v_login) IS NULL,
                    'sessão que tivesse nascido com a versão antiga é recusada na requisição seguinte');
SELECT teste.ctx(NULL, NULL);
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, :v_login + 1) = ARRAY['ENFERMAGEM'], 'nova versão vigente');

-- ---------------------------------------------------------------- troca da própria senha
SELECT teste.ctx(NULL, NULL);
SELECT credencial_versao AS v_antes FROM fluxo.credencial_para_login('enf.a') \gset
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, NULL, NULL, NULL, :v_antes) = '{}'::text[], 'contexto próprio');
-- Sessão desatualizada (outra troca/redefinição já confirmada) não grava nada
SELECT teste.afirma(fluxo.alterar_senha_propria(:HASH3, :v_antes - 1) IS NULL, 'versão antiga: nada gravado');
SELECT teste.afirma(fluxo.alterar_senha_propria(:HASH3, :v_antes) = :v_antes + 1,
                    'troca da própria senha devolve a nova versão (para a sessão atual)');
SELECT teste.afirma(NOT deve_trocar_senha, 'exigência de troca retirada') FROM fluxo.usuario WHERE id = :ENF;
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, :v_antes) IS NULL, 'outras sessões recusadas');
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, :v_antes + 1) = ARRAY['ENFERMAGEM'], 'a sessão atual segue');
SELECT teste.espera_erro($$ SELECT fluxo.alterar_senha_propria('x') $$, 'does not exist');  -- sem a versão, não há função

-- ---------------------------------------------------------------- desativar e reativar não "ressuscita"
SELECT teste.ctx(NULL, NULL);
SELECT credencial_versao AS v_ativa FROM fluxo.credencial_para_login('enf.a') \gset
SELECT teste.ctx(:ADM_A, :A);
SELECT fluxo.admin_definir_situacao(:ENF, (SELECT versao FROM fluxo.usuario WHERE id = :ENF), false);
SELECT fluxo.admin_definir_situacao(:ENF, (SELECT versao FROM fluxo.usuario WHERE id = :ENF), true);
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, :v_ativa) IS NULL,
                    'sessão de antes da desativação continua recusada após reativar');

-- ---------------------------------------------------------------- papéis alterados e restaurados
SELECT teste.ctx(NULL, NULL);
SELECT credencial_versao AS v_papeis FROM fluxo.credencial_para_login('enf.a') \gset
SELECT teste.ctx(:ADM_A, :A);
SELECT fluxo.admin_definir_papeis(:ENF, (SELECT versao FROM fluxo.usuario WHERE id = :ENF), ARRAY['MEDICO']::fluxo.papel[]);
SELECT fluxo.admin_definir_papeis(:ENF, (SELECT versao FROM fluxo.usuario WHERE id = :ENF), ARRAY['ENFERMAGEM']::fluxo.papel[]);
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, :v_papeis) IS NULL,
                    'papéis restaurados não revalidam a sessão antiga');
-- Alteração só de dados cadastrais não derruba sessões
SELECT credencial_versao AS v_cad FROM fluxo.usuario WHERE id = :ENF \gset
SELECT fluxo.admin_alterar_conta(:ENF, (SELECT versao FROM fluxo.usuario WHERE id = :ENF), 'Enfermeira Ana', NULL, NULL);
SELECT teste.afirma((SELECT credencial_versao FROM fluxo.usuario WHERE id = :ENF) = :v_cad, 'dados cadastrais não mudam a versão');

-- ---------------------------------------------------------------- a versão nunca regride
SELECT teste.espera_erro(format($$ UPDATE fluxo.usuario SET credencial_versao = 1, versao = versao + 1 WHERE id = %L $$, :ENF),
                         'permission denied');
SELECT teste.afirma((SELECT count(*) FROM auditoria.verificar_cadeia()) = 0, 'cadeia íntegra');
ROLLBACK;
