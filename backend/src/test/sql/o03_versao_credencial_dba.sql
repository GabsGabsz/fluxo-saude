-- V12 (como DBA): alteração de senha/situação feita direto no banco — sem passar pela
-- aplicação nem apagar sessões — também invalida as sessões existentes; a versão não regride.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
SELECT credencial_versao AS v0 FROM fluxo.usuario WHERE id = :ENF \gset
UPDATE fluxo.usuario SET senha_hash = '{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=19456,t=2,p=1$ZGJh$ZGJhLWhhc2g',
       versao = versao + 1 WHERE id = :ENF;
SELECT teste.afirma(credencial_versao = :v0 + 1, 'senha trocada pelo DBA incrementa') FROM fluxo.usuario WHERE id = :ENF;
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, :v0) IS NULL, 'sessão antiga recusada');
UPDATE fluxo.usuario SET credencial_versao = 1, versao = versao + 1 WHERE id = :ENF;
SELECT teste.afirma(credencial_versao = :v0 + 1, 'versão não regride (nem pelo DBA)') FROM fluxo.usuario WHERE id = :ENF;
-- Lotação removida e recolocada pelo DBA: a sessão de antes não "ressuscita"
SELECT credencial_versao AS v1 FROM fluxo.usuario WHERE id = :ENF \gset
DELETE FROM fluxo.lotacao WHERE usuario_id = :ENF AND unidade_id = :A;
INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (:ENF, :A, 'ENFERMAGEM');
SELECT teste.afirma(fluxo.aplicar_contexto(:ENF, :A, NULL, NULL, :v1) IS NULL, 'lotação recolocada não revalida sessão antiga');
SELECT teste.afirma(credencial_versao = :v1 + 1, 'remoção de lotação pelo DBA incrementa') FROM fluxo.usuario WHERE id = :ENF;
ROLLBACK;
