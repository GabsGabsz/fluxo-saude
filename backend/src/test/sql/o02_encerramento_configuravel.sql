-- RN-017 / RF-015: encerramento configurável por unidade (executado como dono, que cria unidades).
BEGIN;
INSERT INTO fluxo.unidade (id, codigo, nome, tipo) VALUES ('00000000-0000-0000-0000-0000000000cc', 'HOSP_REDE', 'Hospital da rede', 'HOSPITAL');
SELECT fluxo.provisionar_unidade('00000000-0000-0000-0000-0000000000cc', p_internacao_encerra => false);
SELECT teste.afirma(natureza = 'ATENDIMENTO' AND desfecho IS NULL, 'internação é transição na unidade configurada assim')
  FROM fluxo.etapa WHERE unidade_id = '00000000-0000-0000-0000-0000000000cc' AND codigo = 'INTERNADO';
SELECT teste.afirma(EXISTS (SELECT 1 FROM fluxo.transicao_etapa t
                              JOIN fluxo.etapa o ON o.id = t.origem_id JOIN fluxo.etapa d ON d.id = t.destino_id
                             WHERE o.codigo = 'INTERNADO' AND d.codigo = 'ALTA' AND o.unidade_id = '00000000-0000-0000-0000-0000000000cc'),
                    'internado (transição) segue para desfechos');
-- Padrão (UPA): internação encerra
SELECT teste.afirma(natureza = 'DESFECHO' AND desfecho = 'INTERNACAO', 'na UPA a internação encerra')
  FROM fluxo.etapa WHERE unidade_id = '00000000-0000-0000-0000-00000000000a' AND codigo = 'INTERNADO';
ROLLBACK;
