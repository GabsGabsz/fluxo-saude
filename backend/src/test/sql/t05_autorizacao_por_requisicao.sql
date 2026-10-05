-- V8: contexto validado pelo banco a cada transação; hash de senha protegido.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set ENF '''11111111-1111-1111-1111-000000000002'''

-- Contexto válido devolve os papéis vigentes e aplica o RLS
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, '10.0.0.9', 'corr-1') = ARRAY['ENFERMAGEM'], 'papéis vigentes');
SELECT teste.afirma(current_setting('fluxo.unidade_ids') = '00000000-0000-0000-0000-00000000000a', 'contexto aplicado');
SELECT teste.afirma((SELECT count(*) FROM fluxo.etapa) > 0, 'RLS liberado para a unidade');

-- Unidade onde não está lotado: NULL (contexto recusado) e nada é aplicado
SELECT teste.ctx(NULL, NULL);
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, '00000000-0000-0000-0000-00000000000b', NULL, NULL) IS NULL, 'sem lotação');
SELECT teste.afirma(coalesce(current_setting('fluxo.usuario_id', true), '') = '', 'contexto não aplicado');
-- Usuário inexistente
SELECT teste.afirma(fluxo.aplicar_contexto(gen_random_uuid(), :A, NULL, NULL) IS NULL, 'usuário inexistente');
-- Sem unidade (operações sobre o próprio cadastro): lista vazia
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, NULL, NULL, NULL) = '{}'::text[], 'contexto do próprio usuário');

-- Hash de senha: nem leitura nem escrita direta pela aplicação
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT teste.espera_erro($$ SELECT senha_hash FROM fluxo.usuario $$, 'permission denied');
SELECT teste.espera_erro($$ UPDATE fluxo.usuario SET senha_hash = 'x', versao = versao + 1 WHERE id = '11111111-1111-1111-1111-000000000002' $$,
                         'permission denied');
SELECT teste.afirma((SELECT count(*) FROM fluxo.usuario) = 2, 'cadastro (sem hash) visível na unidade');
-- Admin pode exigir troca de senha e desativar (colunas permitidas)
UPDATE fluxo.usuario SET deve_trocar_senha = true, versao = versao + 1 WHERE id = :ENF;

-- Troca da própria senha
SELECT teste.ctx(:ENF, NULL);
SELECT teste.afirma(fluxo.hash_senha_propria() LIKE '{argon2%', 'lê o próprio hash');
SELECT teste.afirma(fluxo.alterar_senha_propria('{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=19456,t=2,p=1$bm92YQ$bm92YTI'),
                    'troca a própria senha');
SELECT teste.ctx(NULL, NULL);
SELECT teste.afirma(NOT deve_trocar_senha, 'troca retira a exigência') FROM fluxo.credencial_para_login('enf.a');
SELECT teste.espera_erro($$ SELECT fluxo.alterar_senha_propria('x') $$, 'contexto de usuário ausente');

-- Desativação revoga o acesso já na transação seguinte
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
UPDATE fluxo.usuario SET ativo = false, versao = versao + 1 WHERE id = :ENF;
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL) IS NULL, 'usuário desativado perde o contexto');

-- Tentativa em conta bloqueada é auditada sem contexto
SELECT teste.ctx(NULL, NULL);
SELECT teste.afirma(fluxo.registrar_tentativa_bloqueada(:ENF), 'tentativa bloqueada auditada');
ROLLBACK;
