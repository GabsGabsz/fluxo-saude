-- CA-11 / RNF-003: isolamento por unidade e falha fechada sem contexto.
BEGIN;

-- Sem contexto: nada visível.
SELECT teste.afirma((SELECT count(*) FROM fluxo.unidade) = 0, 'sem contexto não vê unidades');
SELECT teste.afirma((SELECT count(*) FROM fluxo.etapa) = 0, 'sem contexto não vê etapas');
SELECT teste.afirma((SELECT count(*) FROM fluxo.setor) = 0, 'sem contexto não vê setores');

-- Contexto malformado gera erro (não "acesso a tudo").
SELECT set_config('fluxo.unidade_ids', 'lixo', true);
SELECT teste.espera_erro('SELECT count(*) FROM fluxo.unidade', 'invalid input syntax for type uuid');

-- Enfermeira da unidade A
SELECT teste.ctx('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000a');
SELECT teste.afirma((SELECT count(*) FROM fluxo.unidade) = 1, 'vê só a própria unidade');
SELECT teste.afirma((SELECT bool_and(unidade_id = '00000000-0000-0000-0000-00000000000a') FROM fluxo.etapa), 'etapas só da unidade A');
SELECT teste.afirma((SELECT count(*) FROM fluxo.lotacao) = 2, 'lotações só da unidade A');

-- Não consegue escrever na unidade B
SELECT teste.espera_erro($$
    INSERT INTO fluxo.paciente (unidade_id, nome) VALUES ('00000000-0000-0000-0000-00000000000b', 'Fulano')
$$, 'row-level security');
SELECT teste.espera_erro($$
    INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel)
    VALUES ('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000b', 'ADMINISTRADOR')
$$, 'row-level security');

-- Não consegue "mover" um registro para outra unidade (WITH CHECK)
INSERT INTO fluxo.paciente (id, unidade_id, nome)
VALUES ('22222222-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'Maria Teste');
SELECT teste.espera_erro($$
    UPDATE fluxo.paciente SET versao = versao + 1, unidade_id = '00000000-0000-0000-0000-00000000000b'
     WHERE id = '22222222-0000-0000-0000-000000000001'
$$, 'row-level security');

-- Coordenação da unidade B não enxerga o paciente da A
SELECT teste.ctx('11111111-1111-1111-1111-000000000003', '00000000-0000-0000-0000-00000000000b');
SELECT teste.afirma((SELECT count(*) FROM fluxo.paciente WHERE id = '22222222-0000-0000-0000-000000000001') = 0,
                    'unidade B não vê paciente da A');
-- UPDATE/"DELETE" cego também não alcança linhas de outra unidade
UPDATE fluxo.paciente SET versao = versao + 1, nome = 'Hack' WHERE id = '22222222-0000-0000-0000-000000000001';
SELECT teste.ctx('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000a');
SELECT teste.afirma((SELECT nome FROM fluxo.paciente WHERE id = '22222222-0000-0000-0000-000000000001') = 'Maria Teste',
                    'update de outra unidade não teve efeito');

-- Privilégios mínimos: sem DELETE em dados de negócio
SELECT teste.espera_erro($$ DELETE FROM fluxo.paciente $$, 'permission denied');
SELECT teste.espera_erro($$ DELETE FROM fluxo.episodio $$, 'permission denied');
SELECT teste.espera_erro($$ DELETE FROM fluxo.pendencia $$, 'permission denied');
SELECT teste.espera_erro($$ INSERT INTO fluxo.unidade (codigo, nome, tipo) VALUES ('X1','X','UPA') $$, 'permission denied');
SELECT teste.espera_erro($$ CREATE TABLE fluxo.intrusa (id int) $$, 'permission denied');

-- Autenticação: lotações de um usuário via função controlada, sem contexto
SELECT teste.ctx(NULL, NULL);
SELECT teste.afirma((SELECT count(*) FROM fluxo.lotacoes_para_autenticacao('11111111-1111-1111-1111-000000000001')) = 1,
                    'função de autenticação retorna lotações do usuário');

ROLLBACK;
