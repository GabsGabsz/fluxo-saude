-- Executado como DONO: o histórico das versões das regras de alerta (V18) não é alterado nem
-- apagado, nem por quem tem poder de DBA.
BEGIN;
SELECT set_config('fluxo.usuario_id', '11111111-1111-1111-1111-000000000001', true),
       set_config('fluxo.unidade_ids', '00000000-0000-0000-0000-00000000000a', true);
INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite)
VALUES ('67676767-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-00000000000a', 'Regra do dono', 'TEMPO_TOTAL',
        interval '2 hours');
SELECT teste.afirma(count(*) = 1, 'versão 0 registrada') FROM fluxo.regra_alerta_versao
 WHERE regra_id = '67676767-0000-0000-0000-0000000000d1';
SELECT teste.espera_erro($$ UPDATE fluxo.regra_alerta_versao SET nome = 'Outro nome' $$, 'imutáveis');
SELECT teste.espera_erro($$ DELETE FROM fluxo.regra_alerta_versao $$, 'imutáveis');
SELECT teste.espera_erro($$ TRUNCATE fluxo.regra_alerta_versao $$, 'imutáveis');
ROLLBACK;
