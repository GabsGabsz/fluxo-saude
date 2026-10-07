-- Executado como DONO: o conjunto de episódios de uma leitura registrada (V17) não pode ser
-- trocado nem apagado, e uma lista que não confere com o hash é recusada pelo próprio banco.
BEGIN;
SELECT teste.espera_erro($$ INSERT INTO auditoria.conjunto_consultado (unidade_id, hash, episodios)
       VALUES ('00000000-0000-0000-0000-00000000000a', public.digest('outra coisa', 'sha256'), '{}') $$,
       'conjunto_hash_confere');
INSERT INTO auditoria.conjunto_consultado (unidade_id, hash, episodios)
VALUES ('00000000-0000-0000-0000-00000000000a', public.digest('', 'sha256'), '{}');
SELECT teste.espera_erro($$ UPDATE auditoria.conjunto_consultado SET criado_em = now() $$, 'imutáveis');
SELECT teste.espera_erro($$ DELETE FROM auditoria.conjunto_consultado $$, 'imutáveis');
SELECT teste.espera_erro($$ TRUNCATE auditoria.conjunto_consultado $$, 'imutáveis');
ROLLBACK;
