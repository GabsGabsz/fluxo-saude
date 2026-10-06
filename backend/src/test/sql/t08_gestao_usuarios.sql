-- V11: gestão de usuários e lotações — alcance do administrador garantido pelo BANCO.
-- A = UPA (admin.a, enf.a), B = hospital (coord.b, admin.b).
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set ADM_A '''11111111-1111-1111-1111-000000000001'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set COORD_B '''11111111-1111-1111-1111-000000000003'''
\set ADM_B '''11111111-1111-1111-1111-000000000004'''
\set NOVO '''11111111-1111-1111-1111-000000000801'''
\set ADM2 '''11111111-1111-1111-1111-000000000802'''
\set TEMP '''11111111-1111-1111-1111-000000000803'''
\set HASH '''{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=19456,t=2,p=1$dGVzdGU$dGVzdGUtaGFzaA'''

SELECT teste.ctx(NULL, NULL);
SELECT coalesce((SELECT max(id) FROM teste.auditoria_desde(0)), 0) AS aud0 \gset

-- ---------------------------------------------------------------- sem DML direto
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel)
    VALUES (%L, %L, 'MEDICO') $$, :ENF, :A), 'permission denied');
SELECT teste.espera_erro(format($$ DELETE FROM fluxo.lotacao WHERE usuario_id = %L $$, :ENF), 'permission denied');
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.usuario (id, login, nome, senha_hash)
    VALUES (gen_random_uuid(), 'direto', 'Direto', %L) $$, :HASH), 'permission denied');
SELECT teste.espera_erro(format($$ UPDATE fluxo.usuario SET ativo = false, versao = versao + 1 WHERE id = %L $$, :ENF),
                         'permission denied');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_carregar_alvo(%L, 0) $$, :ENF), 'permission denied');

-- ---------------------------------------------------------------- sem permissão de administrar
SELECT teste.ctx(:ENF, :A);
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_papeis(%L, 0, ARRAY['ADMINISTRADOR']::fluxo.papel[]) $$, :ENF),
                         'exige administrador');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_criar_usuario(gen_random_uuid(), 'intruso', 'Intruso', NULL, NULL,
    %L, ARRAY['ADMINISTRADOR']::fluxo.papel[]) $$, :HASH), 'exige administrador');
SELECT teste.espera_erro($$ SELECT * FROM fluxo.admin_localizar_por_login('admin.a') $$, 'exige administrador');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_situacao(%L, 0, false) $$, :ADM_A), 'exige administrador');
SELECT teste.afirma(fluxo.possui_outras_unidades(:ADM_A) IS NULL, 'não-administrador não consulta alcance');
-- Contexto com mais de uma unidade não serve para administrar
SELECT teste.ctx(:ADM_A, '00000000-0000-0000-0000-00000000000a,00000000-0000-0000-0000-00000000000b');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_papeis(%L, 0, ARRAY['MEDICO']::fluxo.papel[]) $$, :ENF),
                         'exige administrador');

-- ---------------------------------------------------------------- criação pelo administrador
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.afirma(fluxo.admin_criar_usuario(:NOVO, ' Novo.A ', 'Novo Profissional', NULL, NULL, :HASH,
                                              ARRAY['ENFERMAGEM', 'ENFERMAGEM', NULL]::fluxo.papel[]) = 0, 'criado');
SELECT teste.afirma(login = 'novo.a' AND ativo AND deve_trocar_senha AND versao = 0, 'nasce ativo, com troca obrigatória')
  FROM fluxo.usuario WHERE id = :NOVO;
SELECT teste.afirma((SELECT array_agg(papel::text) FROM fluxo.lotacao WHERE usuario_id = :NOVO) = ARRAY['ENFERMAGEM']
                    AND (SELECT bool_and(concedido_por = :ADM_A) FROM fluxo.lotacao WHERE usuario_id = :NOVO),
                    'lotado na unidade ativa, autoria pelo banco');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_criar_usuario(gen_random_uuid(), 'NOVO.A', 'Outro', NULL, NULL, %L,
    ARRAY['MEDICO']::fluxo.papel[]) $$, :HASH), 'usuario_login_key');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_criar_usuario(gen_random_uuid(), 'sem.papel', 'Sem Papel', NULL, NULL,
    %L, '{}'::fluxo.papel[]) $$, :HASH), 'ao menos um papel');
-- Mesma definição: nada muda, versão mantida
SELECT teste.afirma(fluxo.admin_definir_papeis(:NOVO, 0, ARRAY['ENFERMAGEM']::fluxo.papel[]) = 0, 'idempotente');
SELECT teste.afirma(fluxo.admin_definir_papeis(:NOVO, 0, ARRAY['ENFERMAGEM', 'MEDICO']::fluxo.papel[]) = 1, 'papéis');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_papeis(%L, 0, ARRAY['MEDICO']::fluxo.papel[]) $$, :NOVO),
                         'versão desatualizada');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_papeis(%L, 0, ARRAY['MEDICO']::fluxo.papel[]) $$,
                                gen_random_uuid()), 'inexistente');

-- ---------------------------------------------------------------- autoalteração
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_papeis(%L, 0, ARRAY['COORDENACAO_FLUXO']::fluxo.papel[]) $$,
                                :ADM_A), 'autoalteração');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_situacao(%L, 0, false) $$, :ADM_A), 'autoalteração');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_alterar_conta(%L, 0, 'Eu Mesmo', NULL, NULL) $$, :ADM_A),
                         'autoalteração');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_senha_provisoria(%L, 0, %L) $$, :ADM_A, :HASH),
                         'autoalteração');

-- ---------------------------------------------------------------- unidade alheia
-- Contexto declarando a unidade B, mas admin.a não é administrador lá
SELECT teste.ctx(:ADM_A, :B);
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_papeis(%L, 1, ARRAY['MEDICO']::fluxo.papel[]) $$, :NOVO),
                         'exige administrador');

-- ---------------------------------------------------------------- usuário em duas unidades
-- admin.b vincula a enfermeira (ativa e lotada na A) também à unidade B
SELECT teste.ctx(:ADM_B, :B);
SELECT teste.afirma(NOT lotado_na_unidade AND vinculavel AND usuario_id = :ENF, 'localiza conta existente pelo login')
  FROM fluxo.admin_localizar_por_login(' ENF.A ');
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM fluxo.admin_localizar_por_login('nao.existe')), 'login inexistente');
SELECT versao AS v_enf FROM fluxo.admin_localizar_por_login('enf.a') \gset
SELECT teste.afirma(fluxo.admin_definir_papeis(:ENF, :v_enf, ARRAY['ENFERMAGEM']::fluxo.papel[]) = :v_enf + 1,
                    'vinculada à B');
SELECT teste.afirma(NOT fluxo.pode_administrar_conta(:ENF) AND fluxo.possui_outras_unidades(:ENF), 'alcance na B');

SELECT teste.ctx(:ADM_A, :A);
SELECT teste.afirma(NOT fluxo.pode_administrar_conta(:ENF), 'A também não administra a conta inteira');
SELECT teste.afirma(fluxo.pode_administrar_conta(:NOVO), 'conta só da A é gerenciável');
-- Operações globais recusadas: afetariam o acesso na B
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_situacao(%L, %s, false) $$, :ENF, :v_enf + 1),
                         'fora do alcance');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_senha_provisoria(%L, %s, %L) $$, :ENF, :v_enf + 1, :HASH),
                         'fora do alcance');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_alterar_conta(%L, %s, 'Outro Nome', NULL, NULL) $$, :ENF, :v_enf + 1),
                         'fora do alcance');
-- Revogar só o acesso na A: a lotação na B permanece e a conta segue ativa
SELECT teste.afirma(fluxo.admin_definir_papeis(:ENF, :v_enf + 1, '{}'::fluxo.papel[]) = :v_enf + 2, 'acesso na A revogado');
SELECT teste.afirma((SELECT count(*) FROM fluxo.usuario WHERE id = :ENF) = 0, 'sem lotação na A, a A deixa de vê-la');
SELECT teste.ctx(:ADM_B, :B);
SELECT teste.afirma((SELECT array_agg(papel::text) FROM fluxo.lotacao WHERE usuario_id = :ENF) = ARRAY['ENFERMAGEM']
                    AND (SELECT ativo FROM fluxo.usuario WHERE id = :ENF), 'acesso na B intacto, conta ativa');
-- A B vinculou a conta, mas não é a unidade gestora: não passa a gerir a conta (sem "tomada")
SELECT teste.afirma(NOT fluxo.pode_administrar_conta(:ENF), 'B não gere conta de outra unidade gestora');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_senha_provisoria(%L, %s, %L) $$, :ENF, :v_enf + 2, :HASH),
                         'fora do alcance');
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, (SELECT credencial_versao FROM fluxo.usuario WHERE id = :ENF)) IS NULL, 'sessão na A perde o contexto');

-- ---------------------------------------------------------------- conta órfã não é "adotável"
SELECT teste.ctx(:ADM_B, :B);
SELECT fluxo.admin_criar_usuario(:TEMP, 'temp.b', 'Temporario B', NULL, NULL, :HASH, ARRAY['TRANSPORTE']::fluxo.papel[]);
SELECT teste.afirma(fluxo.admin_definir_papeis(:TEMP, 0, '{}'::fluxo.papel[]) = 1, 'última lotação removida');
-- (a conta órfã fica invisível ao RLS; a desativação é conferida pela auditoria)
SELECT teste.afirma(count(*) = 1, 'sem lotação, a conta é desativada')
  FROM teste.auditoria_desde(:aud0)
 WHERE acao = 'CONTA_DESATIVADA' AND recurso_id = :TEMP AND dados ->> 'motivo' = 'sem_lotacao';
SELECT teste.afirma(dados -> 'depois' ->> 'ativo' = 'false', 'linha do usuário desativada')
  FROM teste.auditoria_desde(:aud0)
 WHERE acao = 'ALTERAR' AND recurso = 'fluxo.usuario' AND recurso_id = :TEMP;
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.afirma(NOT vinculavel, 'órfã não é vinculável') FROM fluxo.admin_localizar_por_login('temp.b');
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_papeis(%L, 1, ARRAY['ADMINISTRADOR']::fluxo.papel[]) $$, :TEMP),
                         'só é possível vincular');

-- ---------------------------------------------------------------- operações globais com alcance
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.afirma(fluxo.admin_alterar_conta(:NOVO, 1, 'Novo Nome', 'novo@upa.br', 'COREN 9') = 2, 'conta alterada');
SELECT teste.afirma(fluxo.admin_alterar_conta(:NOVO, 2, 'Novo Nome', 'novo@upa.br', 'COREN 9') = 2, 'sem mudança');
SELECT teste.afirma(fluxo.admin_definir_senha_provisoria(:NOVO, 2, :HASH) = 3, 'senha provisória definida');
SELECT teste.afirma(deve_trocar_senha, 'troca obrigatória') FROM fluxo.usuario WHERE id = :NOVO;
SELECT teste.afirma(fluxo.admin_definir_situacao(:NOVO, 3, false) = 4, 'conta desativada');
SELECT teste.afirma(fluxo.aplicar_contexto(:NOVO, :A, NULL, NULL, (SELECT credencial_versao FROM fluxo.usuario WHERE id = :NOVO)) IS NULL, 'conta desativada perde o contexto');
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.afirma(fluxo.admin_definir_situacao(:NOVO, 4, true) = 5, 'conta reativada');

-- ---------------------------------------------------------------- último administrador
SELECT fluxo.admin_criar_usuario(:ADM2, 'admin2.a', 'Admin Dois', NULL, NULL, :HASH, ARRAY['ADMINISTRADOR']::fluxo.papel[]);
-- admin2 troca o papel de admin.a: permitido (admin2 continua administrador)
SELECT teste.ctx(:ADM2, :A);
SELECT teste.afirma(fluxo.admin_definir_papeis(:ADM_A, 0, ARRAY['AUDITORIA']::fluxo.papel[]) = 1, 'papel trocado');
-- admin.a já não administra: nem tenta tirar o último administrador
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.espera_erro(format($$ SELECT fluxo.admin_definir_papeis(%L, 0, '{}'::fluxo.papel[]) $$, :ADM2),
                         'exige administrador');

-- ---------------------------------------------------------------- auditoria
SELECT teste.afirma(count(*) FILTER (WHERE acao = 'USUARIO_CRIADO') = 3
                AND count(*) FILTER (WHERE acao = 'ACESSO_CONCEDIDO') = 1
                AND count(*) FILTER (WHERE acao = 'PAPEIS_ALTERADOS') = 2
                AND count(*) FILTER (WHERE acao = 'ACESSO_REVOGADO') = 2
                AND count(*) FILTER (WHERE acao = 'CONTA_ALTERADA') = 1
                AND count(*) FILTER (WHERE acao = 'SENHA_PROVISORIA_DEFINIDA') = 1
                AND count(*) FILTER (WHERE acao = 'CONTA_DESATIVADA') = 2
                AND count(*) FILTER (WHERE acao = 'CONTA_REATIVADA') = 1
                AND count(*) FILTER (WHERE acao = 'USUARIO_LOCALIZADO') >= 4, 'operações administrativas auditadas')
  FROM teste.auditoria_desde(:aud0);
SELECT teste.afirma(bool_and(position('argon2' IN dados::text) = 0 AND position('dGVzdGU' IN dados::text) = 0
                         AND position('enf.a' IN lower(dados::text)) = 0),
                    'nenhum hash nem login digitado na auditoria')
  FROM teste.auditoria_desde(:aud0);
SELECT teste.afirma(dados = '{"antes": ["ENFERMAGEM"], "depois": []}'::jsonb, 'revogação registra antes/depois')
  FROM teste.auditoria_desde(:aud0) WHERE acao = 'ACESSO_REVOGADO' AND recurso_id = :ENF;
SELECT teste.afirma(bool_and(usuario_id IS NOT NULL AND unidade_id IS NOT NULL), 'autor e unidade em toda ação')
  FROM teste.auditoria_desde(:aud0) WHERE origem = 'APLICACAO';
SELECT teste.afirma((SELECT count(*) FROM auditoria.verificar_cadeia()) = 0, 'cadeia íntegra');
ROLLBACK;
