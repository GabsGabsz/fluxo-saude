-- Executado como DONO do banco: mesmo quem tem poder de DBA não altera a
-- auditoria sem deixar rastro detectável.

-- Triggers bloqueiam até o dono
BEGIN;
SELECT teste.espera_erro($$ UPDATE auditoria.registro SET acao = 'XXX' $$, 'imutáveis');
SELECT teste.espera_erro($$ DELETE FROM auditoria.registro $$, 'imutáveis');
SELECT teste.espera_erro($$ TRUNCATE auditoria.registro $$, 'imutáveis');
ROLLBACK;


-- 1. Alteração de conteúdo
BEGIN;
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT auditoria.registrar('TESTE_A', 'teste');
SELECT auditoria.registrar('TESTE_B', 'teste');
SELECT auditoria.registrar('TESTE_C', 'teste');
SELECT teste.afirma(NOT EXISTS (SELECT 1 FROM auditoria.verificar_cadeia()), 'cadeia íntegra antes da adulteração');
ALTER TABLE auditoria.registro DISABLE TRIGGER registro_imutavel;
UPDATE auditoria.registro SET recurso = 'adulterado' WHERE acao = 'TESTE_B';
SELECT teste.afirma(EXISTS (SELECT 1 FROM auditoria.verificar_cadeia() v JOIN auditoria.registro r ON r.id = v.registro_id
                             WHERE r.acao = 'TESTE_B' AND v.problema LIKE 'conteúdo alterado%'),
                    'alteração de conteúdo detectada');
ROLLBACK;

-- 2. Remoção no meio
BEGIN;
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT auditoria.registrar('TESTE_A', 'teste');
SELECT auditoria.registrar('TESTE_B', 'teste');
SELECT auditoria.registrar('TESTE_C', 'teste');
ALTER TABLE auditoria.registro DISABLE TRIGGER registro_imutavel;
DELETE FROM auditoria.registro WHERE acao = 'TESTE_B';
SELECT teste.afirma(EXISTS (SELECT 1 FROM auditoria.verificar_cadeia() v JOIN auditoria.registro r ON r.id = v.registro_id
                             WHERE r.acao = 'TESTE_C' AND v.problema LIKE 'elo anterior%'),
                    'remoção no meio detectada');
ROLLBACK;

-- 3. Remoção do fim (últimos registros)
BEGIN;
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT auditoria.registrar('TESTE_A', 'teste');
SELECT auditoria.registrar('TESTE_B', 'teste');
ALTER TABLE auditoria.registro DISABLE TRIGGER registro_imutavel;
DELETE FROM auditoria.registro WHERE acao = 'TESTE_B';
SELECT teste.afirma(EXISTS (SELECT 1 FROM auditoria.verificar_cadeia() WHERE problema LIKE 'fim da cadeia%'),
                    'remoção do fim detectada');
ROLLBACK;

-- 4. Remoção do início
BEGIN;
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
SELECT auditoria.registrar('TESTE_A', 'teste');
ALTER TABLE auditoria.registro DISABLE TRIGGER registro_imutavel;
DELETE FROM auditoria.registro WHERE id = (SELECT min(id) FROM auditoria.registro);
SELECT teste.afirma(EXISTS (SELECT 1 FROM auditoria.verificar_cadeia() WHERE problema LIKE 'início da cadeia%'),
                    'remoção do início detectada');
ROLLBACK;
