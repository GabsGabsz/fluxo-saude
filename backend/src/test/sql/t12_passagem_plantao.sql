-- V16: passagem de plantão — papel exigido, isolamento, uma pendente por unidade, recebedor
-- distinto, transições controladas pelo banco, conteúdo imutável e auditoria sem texto livre.
BEGIN;
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set ADM_A '''11111111-1111-1111-1111-000000000001'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set COORD_B '''11111111-1111-1111-1111-000000000003'''
\set MED '''11111111-1111-1111-1111-0000000000d1'''
\set P1 '''77777777-0000-0000-0000-000000000001'''
\set P2 '''77777777-0000-0000-0000-000000000002'''
\set P3 '''77777777-0000-0000-0000-000000000003'''
\set ASS '''aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'''
\set ASS2 '''bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'''

-- Um médico na unidade A (recebedor), criado pelo administrador como a aplicação faz.
SELECT teste.ctx(:ADM_A, :A);
SELECT fluxo.admin_criar_usuario(:MED, 'medico.a', 'Medico A', NULL, NULL,
       '{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$ZmljdGljaW8$ZmljdGljaW8', ARRAY['MEDICO']::fluxo.papel[]);

-- ------------------------------------------------------------- papel exigido (PLANTAO_GERENCIAR)
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.passagem_plantao (id, unidade_id, assinatura, total_casos, total_criticos, total_transferencias,
                                        total_pendencias, total_vencidas)
    VALUES (%L, %L, %L, 0, 0, 0, 0, 0) $$, :P1, :A, :ASS), 'row-level security');

-- ------------------------------------------------------------- entrega (enfermagem)
SELECT teste.ctx(:ENF, :A);
SELECT coalesce((SELECT max(id) FROM teste.auditoria_desde(0)), 0) AS aud0 \gset
INSERT INTO fluxo.passagem_plantao (id, unidade_id, assinatura, total_casos, total_criticos, total_transferencias,
                                    total_pendencias, total_vencidas, observacao, entregue_por, entregue_em, periodo_inicio,
                                    status)
VALUES (:P1, :A, :ASS, 3, 1, 1, 2, 1, 'Leito 4 aguardando higienização', :ADM_A, now() - interval '1 year',
        now() - interval '2 years', 'ENTREGUE');
SELECT teste.afirma(entregue_por = :ENF AND entregue_em > now() - interval '1 minute' AND periodo_inicio IS NULL
                    AND versao = 0, 'autoria, instante e período definidos pelo banco (primeira passagem: sem início)')
  FROM fluxo.passagem_plantao WHERE id = :P1;
INSERT INTO fluxo.passagem_conteudo (passagem_id, unidade_id, conteudo)
VALUES (:P1, :A, '{"versao": 1, "casos": [{"episodioId": "x", "critico": true}]}');

-- Auditoria: fato e assinatura registrados; texto livre redigido.
SELECT teste.afirma(count(*) = 1 AND bool_and(dados -> 'depois' ->> 'observacao' = '[redigido]')
                    AND bool_and(dados -> 'depois' ->> 'assinatura' = :ASS)
                    AND bool_and(position('higieniza' IN dados::text) = 0),
                    'auditoria da entrega sem o texto livre')
  FROM teste.auditoria_desde(:aud0) WHERE recurso = 'fluxo.passagem_plantao' AND acao = 'CRIAR';

-- Só uma passagem aguardando recebimento por unidade.
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.passagem_plantao (id, unidade_id, assinatura, total_casos, total_criticos, total_transferencias,
                                        total_pendencias, total_vencidas)
    VALUES (%L, %L, %L, 0, 0, 0, 0, 0) $$, :P2, :A, :ASS2), 'passagem_pendente_uq');

-- Conteúdo: uma vez só, imutável, e só pelo autor.
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.passagem_conteudo (passagem_id, unidade_id, conteudo)
                                    VALUES (%L, %L, '{"casos": []}') $$, :P1, :A), 'passagem_conteudo_pkey');
-- Sem privilégio de UPDATE/DELETE para a aplicação (e gatilhos de imutabilidade para o dono).
SELECT teste.espera_erro(format($$ UPDATE fluxo.passagem_conteudo SET conteudo = '{"casos": []}' WHERE passagem_id = %L $$, :P1),
                         'permission denied');
SELECT teste.espera_erro(format($$ DELETE FROM fluxo.passagem_conteudo WHERE passagem_id = %L $$, :P1), 'permission denied');
SELECT teste.espera_erro(format($$ DELETE FROM fluxo.passagem_plantao WHERE id = %L $$, :P1), 'permission denied');

-- O conteúdo entregue não muda (nem pelo autor).
SELECT teste.espera_erro(format($$ UPDATE fluxo.passagem_plantao SET total_casos = 0, versao = versao + 1 WHERE id = %L $$, :P1),
                         'imutável');

-- ------------------------------------------------------------- isolamento
SELECT teste.ctx(:COORD_B, :B);
SELECT teste.afirma((SELECT count(*) FROM fluxo.passagem_plantao) = 0 AND (SELECT count(*) FROM fluxo.passagem_conteudo) = 0,
                    'coordenação da B não vê passagens da A');
-- Quem não gerencia plantão (administração) também não lê, mesmo na própria unidade.
SELECT teste.ctx(:ADM_A, :A);
SELECT teste.afirma((SELECT count(*) FROM fluxo.passagem_plantao) = 0, 'administrador não lê passagens');

-- ------------------------------------------------------------- recebimento
SELECT teste.ctx(:ENF, :A);
SELECT teste.espera_erro(format($$ UPDATE fluxo.passagem_plantao SET status = 'RECEBIDA', assinatura_recebimento = %L,
                                       diferencas_recebimento = '{}', versao = versao + 1 WHERE id = %L $$, :ASS2, :P1),
                         'passagem_recebedor_distinto');
SELECT teste.ctx(:MED, :A);
-- versão errada: o banco recusa (controle otimista)
SELECT teste.espera_erro(format($$ UPDATE fluxo.passagem_plantao SET status = 'RECEBIDA', assinatura_recebimento = %L,
                                       diferencas_recebimento = '{}', versao = versao + 2 WHERE id = %L $$, :ASS2, :P1),
                         'controle de concorrência');
UPDATE fluxo.passagem_plantao
   SET status = 'RECEBIDA', assinatura_recebimento = :ASS2, diferencas_recebimento = '{"casosEncerrados": 1}',
       recebida_por = :ENF, recebida_em = now() - interval '1 day', versao = versao + 1
 WHERE id = :P1;
SELECT teste.afirma(recebida_por = :MED AND recebida_em > now() - interval '1 minute' AND versao = 1,
                    'recebedor e instante vêm do contexto do banco')
  FROM fluxo.passagem_plantao WHERE id = :P1;
-- Recebida é definitiva.
SELECT teste.espera_erro(format($$ UPDATE fluxo.passagem_plantao SET status = 'CANCELADA', cancelada_por = %L,
                                       justificativa_cancelamento = 'teste', versao = versao + 1 WHERE id = %L $$, :MED, :P1),
                         'já recebida');

-- ------------------------------------------------------------- nova entrega: período começa na última recebida
SELECT entregue_em AS entrega1 FROM fluxo.passagem_plantao WHERE id = :P1 \gset
INSERT INTO fluxo.passagem_plantao (id, unidade_id, assinatura, total_casos, total_criticos, total_transferencias,
                                    total_pendencias, total_vencidas)
VALUES (:P2, :A, :ASS2, 0, 0, 0, 0, 0);
SELECT teste.afirma(periodo_inicio = :'entrega1'::timestamptz AND entregue_por = :MED, 'período desde a última recebida')
  FROM fluxo.passagem_plantao WHERE id = :P2;
-- Conteúdo só pelo autor
SELECT teste.ctx(:ENF, :A);
SELECT teste.espera_erro(format($$ INSERT INTO fluxo.passagem_conteudo (passagem_id, unidade_id, conteudo)
                                    VALUES (%L, %L, '{"casos": []}') $$, :P2, :A), 'pelo autor');
-- Cancelamento só pelo autor, com justificativa
SELECT teste.espera_erro(format($$ UPDATE fluxo.passagem_plantao SET status = 'CANCELADA',
                                       justificativa_cancelamento = 'Entregue por engano', versao = versao + 1
                                   WHERE id = %L $$, :P2), 'passagem_cancelamento_pelo_autor');
SELECT teste.ctx(:MED, :A);
SELECT teste.espera_erro(format($$ UPDATE fluxo.passagem_plantao SET status = 'CANCELADA', versao = versao + 1
                                   WHERE id = %L $$, :P2), 'passagem_situacao_coerente');
UPDATE fluxo.passagem_plantao SET status = 'CANCELADA', justificativa_cancelamento = 'Entregue por engano',
       versao = versao + 1 WHERE id = :P2;
SELECT teste.afirma(cancelada_por = :MED AND status = 'CANCELADA', 'cancelada pelo autor')
  FROM fluxo.passagem_plantao WHERE id = :P2;
-- Depois do cancelamento, nova entrega é possível; o período continua a partir da última RECEBIDA.
INSERT INTO fluxo.passagem_plantao (id, unidade_id, assinatura, total_casos, total_criticos, total_transferencias,
                                    total_pendencias, total_vencidas)
VALUES (:P3, :A, :ASS, 0, 0, 0, 0, 0);
SELECT teste.afirma(periodo_inicio = :'entrega1'::timestamptz, 'cancelada não conta como início de período')
  FROM fluxo.passagem_plantao WHERE id = :P3;
-- Totais coerentes
SELECT teste.espera_erro(format($$
    INSERT INTO fluxo.passagem_plantao (id, unidade_id, assinatura, total_casos, total_criticos, total_transferencias,
                                        total_pendencias, total_vencidas)
    VALUES (gen_random_uuid(), %L, %L, 1, 2, 0, 0, 0) $$, :B, :ASS), 'row-level security');
ROLLBACK;
