-- RN-004, RN-010, RNF-002, CA-10: auditoria automática, redigida, imutável e não forjável.
BEGIN;
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');

CREATE TEMP TABLE marco AS SELECT coalesce(max(id), 0) AS id FROM auditoria.registro;

INSERT INTO fluxo.paciente (id, unidade_id, nome, cns)
VALUES ('22222222-0000-0000-0000-000000000010', '00000000-0000-0000-0000-00000000000a', 'José da Silva', '291417776317066');

-- Registro criado com origem BANCO, autor, unidade, IP e dados pessoais REDIGIDOS
SELECT teste.afirma(r.acao = 'CRIAR' AND r.origem = 'BANCO'
                AND r.usuario_id = '11111111-1111-1111-1111-000000000001'
                AND r.unidade_id = '00000000-0000-0000-0000-00000000000a'
                AND host(r.origem_ip) = '10.0.0.1'
                AND r.correlacao_id = 'teste'
                AND r.dados -> 'depois' ->> 'nome' = '[redigido]'
                AND r.dados -> 'depois' ->> 'cns' = '[redigido]'
                AND position('Silva' IN r.dados::text) = 0, 'auditoria de criação de paciente')
  FROM auditoria.registro r
 WHERE r.id > (SELECT id FROM marco) AND r.recurso = 'fluxo.paciente'
   AND r.recurso_id = '22222222-0000-0000-0000-000000000010';

-- UPDATE registra apenas o diff (valor anterior nulo é omitido)
UPDATE fluxo.paciente SET versao = versao + 1, identificador_institucional = 'PR-123' WHERE id = '22222222-0000-0000-0000-000000000010';
SELECT teste.afirma((SELECT dados FROM auditoria.registro
                      WHERE recurso = 'fluxo.paciente' AND acao = 'ALTERAR' AND id > (SELECT id FROM marco))
                    = '{"antes": {}, "depois": {"identificador_institucional": "[redigido]"}}'::jsonb,
                    'diff só com campos alterados');

-- UPDATE sem mudança real não gera registro
UPDATE fluxo.paciente SET versao = versao + 1, nome = nome WHERE id = '22222222-0000-0000-0000-000000000010';
SELECT teste.afirma((SELECT count(*) FROM auditoria.registro
                      WHERE recurso = 'fluxo.paciente' AND id > (SELECT id FROM marco)) = 2,
                    'update sem mudança não audita');

-- Senha e dados pessoais de profissionais nunca vão para o log
SELECT fluxo.admin_alterar_conta('11111111-1111-1111-1111-000000000002', 0, 'Enfermeira A Silva', NULL, NULL);
SELECT teste.ctx('11111111-1111-1111-1111-000000000002', NULL);   -- a própria enfermeira troca a senha
SELECT fluxo.alterar_senha_propria('{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$bm92YQ$bm92YQ');
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT teste.afirma(bool_and(coalesce(dados -> 'depois' ->> 'senha_hash', '[redigido]') = '[redigido]'
                         AND coalesce(dados -> 'depois' ->> 'nome', '[redigido]') = '[redigido]')
                    AND count(*) = 2, 'hash de senha e nome redigidos')
  FROM teste.auditoria_desde((SELECT id FROM marco)) WHERE recurso = 'fluxo.usuario' AND acao = 'ALTERAR';

-- Escrita sem usuário no contexto é recusada (falha fechada)
SELECT set_config('fluxo.usuario_id', '', true);
SELECT teste.espera_erro($$
    INSERT INTO fluxo.setor (unidade_id, codigo, nome) VALUES ('00000000-0000-0000-0000-00000000000a', 'TRIAGEM', 'Triagem')
$$, 'contexto de usuário ausente');
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');

-- IP malformado no contexto não derruba a operação (vira NULL)
SELECT set_config('fluxo.origem_ip', 'nao-e-ip', true);
INSERT INTO fluxo.setor (unidade_id, codigo, nome) VALUES ('00000000-0000-0000-0000-00000000000a', 'TRIAGEM', 'Triagem');
SELECT teste.afirma((SELECT origem_ip IS NULL FROM auditoria.registro WHERE recurso = 'fluxo.setor' AND id > (SELECT id FROM marco)),
                    'IP inválido registrado como NULL');
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');

-- Tabelas de auditoria: a aplicação só lê
SELECT teste.espera_erro($$ UPDATE auditoria.registro SET acao = 'XXX' $$, 'permission denied');
SELECT teste.espera_erro($$ DELETE FROM auditoria.registro $$, 'permission denied');
SELECT teste.espera_erro($$ TRUNCATE auditoria.registro $$, 'permission denied');
SELECT teste.espera_erro($$
    INSERT INTO auditoria.registro (id, ocorrido_em, origem, acao, recurso, hash) VALUES (1, now(), 'BANCO', 'FAKE', 'x', '\x00')
$$, 'permission denied');
SELECT teste.espera_erro($$ SELECT * FROM auditoria.cadeia_cabeca FOR UPDATE $$, 'permission denied');
SELECT teste.espera_erro($$ SELECT auditoria.tg_capturar() $$, 'permission denied');
SELECT teste.espera_erro($$ SELECT * FROM fluxo.usuario_acesso $$, 'permission denied');

-- Eventos declarados pela aplicação
SELECT teste.afirma(auditoria.registrar('CONSULTA_SENSIVEL', 'fluxo.paciente', '22222222-0000-0000-0000-000000000010',
                                        '{}', '00000000-0000-0000-0000-00000000000a') > 0, 'registrar evento de aplicação');
SELECT teste.afirma((SELECT origem = 'APLICACAO' AND usuario_id = '11111111-1111-1111-1111-000000000001'
                       FROM auditoria.registro WHERE acao = 'CONSULTA_SENSIVEL' ORDER BY id DESC LIMIT 1),
                    'evento de aplicação marcado como APLICACAO e com autor do contexto');
SELECT teste.espera_erro($$ SELECT auditoria.registrar('ALTERAR', 'fluxo.episodio') $$, 'ação reservada');
SELECT teste.espera_erro($$ SELECT auditoria.registrar('LOGIN_SUCESSO', 'autenticacao') $$, 'ação reservada');
SELECT teste.espera_erro($$
    SELECT auditoria.registrar('EXPORTACAO', 'relatorio', NULL, '{}', '00000000-0000-0000-0000-00000000000b')
$$, 'unidade fora do contexto');
SELECT teste.espera_erro(format($$ SELECT auditoria.registrar('EXPORTACAO', 'relatorio', NULL, %L) $$,
                                jsonb_build_object('x', repeat('a', 5000))), 'até 4 KB');
SELECT teste.ctx(NULL, NULL);
SELECT teste.espera_erro($$ SELECT auditoria.registrar('EXPORTACAO', 'relatorio') $$, 'contexto de usuário ausente');

-- Login (sem contexto) via função dedicada, com bloqueio progressivo
SELECT teste.afirma((SELECT usuario_id FROM fluxo.credencial_para_login('ENF.A')) = '11111111-1111-1111-1111-000000000002',
                    'login case-insensitive');
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM fluxo.credencial_para_login('inexistente')), 'login inexistente');
SELECT teste.afirma(fluxo.registrar_tentativa_login(NULL, false, 3, '5 minutes') IS NULL, 'falha de login inexistente');
SELECT fluxo.registrar_tentativa_login('11111111-1111-1111-1111-000000000002', false, 3, '5 minutes');
SELECT fluxo.registrar_tentativa_login('11111111-1111-1111-1111-000000000002', false, 3, '5 minutes');
SELECT teste.afirma(fluxo.registrar_tentativa_login('11111111-1111-1111-1111-000000000002', false, 3, '5 minutes')
                    > clock_timestamp() + interval '4 minutes', 'terceira falha bloqueia a conta');
SELECT teste.afirma((SELECT bloqueado_ate IS NOT NULL FROM fluxo.credencial_para_login('enf.a')), 'bloqueio visível no login');
SELECT teste.espera_erro($$ SELECT fluxo.registrar_tentativa_login('11111111-1111-1111-1111-000000000002', false, 1, '5 minutes') $$,
                         'fora dos limites');
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT teste.espera_erro($$ SELECT fluxo.registrar_tentativa_login('11111111-1111-1111-1111-000000000002', true, 3, '5 minutes') $$,
                         'já no contexto');
-- Desbloqueio pelo admin da mesma unidade; admin de outra unidade não consegue
SELECT fluxo.desbloquear_usuario('11111111-1111-1111-1111-000000000002');
SELECT teste.afirma((SELECT bloqueado_ate IS NULL FROM fluxo.credencial_para_login('enf.a')), 'desbloqueado');
SELECT teste.ctx('11111111-1111-1111-1111-000000000003', '00000000-0000-0000-0000-00000000000b');
SELECT teste.espera_erro($$ SELECT fluxo.desbloquear_usuario('11111111-1111-1111-1111-000000000002') $$, 'fora das unidades');
SELECT teste.espera_erro($$ SELECT * FROM fluxo.lotacoes_para_autenticacao('11111111-1111-1111-1111-000000000002') $$,
                         'outro usuário');

-- Usuários: unidade B não vê nem altera usuário lotado só na A
SELECT teste.afirma((SELECT count(*) FROM fluxo.usuario WHERE id = '11111111-1111-1111-1111-000000000002') = 0,
                    'B não vê usuário da A');
SELECT teste.espera_erro($$ UPDATE fluxo.usuario SET versao = versao + 1, nome = 'Hack'
                              WHERE id = '11111111-1111-1111-1111-000000000001' $$, 'permission denied');
SELECT teste.espera_erro($$ SELECT fluxo.admin_alterar_conta('11111111-1111-1111-1111-000000000001', 0, 'Hack', NULL, NULL) $$,
                         'exige administrador');
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT teste.afirma((SELECT nome <> 'Hack' FROM fluxo.usuario WHERE id = '11111111-1111-1111-1111-000000000001'),
                    'B não altera usuário da A');

-- Eventos de login ficam fora do alcance da leitura comum (sem unidade): só via função de auditoria
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM auditoria.registro WHERE acao LIKE 'LOGIN%'), 'RLS esconde eventos sem unidade');
-- Unidade B não lê auditoria da A
SELECT teste.ctx('11111111-1111-1111-1111-000000000003', '00000000-0000-0000-0000-00000000000b');
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM auditoria.registro WHERE unidade_id = '00000000-0000-0000-0000-00000000000a'),
                    'B não lê auditoria da A');

-- Cadeia íntegra
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM auditoria.verificar_cadeia()), 'cadeia de hash íntegra');
ROLLBACK;

-- Isolamento diferente de READ COMMITTED é recusado para escrita auditada
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT teste.espera_erro($$ SELECT auditoria.registrar('TESTE_RR', 'x') $$, 'exige isolamento READ COMMITTED');
ROLLBACK;
