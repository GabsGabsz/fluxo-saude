-- V21 (revisão do PR #11): setor vigente no início do bloqueio quando há transferências com o MESMO
-- ocorrido_em. A ordem é a da linha do tempo — ocorrido_em → registrado_em → id —, nunca o UUID do
-- setor (a V20 desempatava pelo UUID textual do setor e falha neste teste).
--
-- Executado como DONO apenas para fixar registrado_em (e o id) de cada evento: os gatilhos de autoria
-- e de ajuste manual de evento_episodio são desligados SÓ dentro desta transação (ROLLBACK no fim).
-- Assim o teste é determinístico (sem depender do relógio nem de pausas) e pode empatar também o
-- registrado_em, para conferir o desempate pelo id. As funções são as mesmas que a aplicação chama.
BEGIN;
\set U '''00000000-0000-0000-0000-00000000000a'''
\set ENF '''11111111-1111-1111-1111-000000000002'''
\set SA '''11111111-1111-1111-1111-111111111111'''
\set SB '''99999999-9999-9999-9999-999999999999'''
SELECT set_config('fluxo.usuario_id', '11111111-1111-1111-1111-000000000002', true),
       set_config('fluxo.unidade_ids', '00000000-0000-0000-0000-00000000000a', true);

-- Base: hora cheia de 30 h atrás. Período P = [B, B+20h); referência = B+25h.
SELECT date_trunc('hour', now()) - interval '30 hours' AS b \gset
SELECT (:'b'::timestamptz + interval '20 hours') AS p_fim, (:'b'::timestamptz + interval '25 hours') AS agora \gset

INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES
    (:SA, :U, 'DESEMP_A', 'Setor A (desempate)'), (:SB, :U, 'DESEMP_B', 'Setor B (desempate)');
INSERT INTO fluxo.paciente (id, unidade_id, nome)
SELECT ('99999999-0000-0000-0000-0000000002' || lpad(n::text, 2, '0'))::uuid, :U, 'Paciente Desempate ' || n
  FROM generate_series(1, 5) n;
-- Episódios abertos; o setor da linha é o ATUAL (irrelevante para fatos históricos).
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
SELECT ('66666666-0000-0000-0000-00000000000' || n)::uuid, :U, ('99999999-0000-0000-0000-0000000002' || lpad(n::text, 2, '0'))::uuid,
       CASE WHEN n IN (1, 3) THEN :SA::uuid ELSE :SB::uuid END, :'b'::timestamptz + interval '9 hours',
       teste.etapa(:U, 'EM_ATENDIMENTO'), :'b'::timestamptz + interval '9 hours'
  FROM generate_series(1, 5) n;

ALTER TABLE fluxo.evento_episodio DISABLE TRIGGER evento_autoria, DISABLE TRIGGER evento_verificar_ajuste_manual;

-- (episódio, id do evento, tipo, ocorrido = B + o, registrado = B + r, dados)
CREATE TEMP TABLE ev (ep int, id uuid, tipo text, o interval, r interval, dados jsonb);
\set BLQ '''{"motivo": "CAUSA_EM_INVESTIGACAO", "categoria": "NAO_DEFINIDA"}'''
INSERT INTO ev VALUES
-- E1 (caso da revisão): em A às 9h; A→B às 10h; B→A também às 10h, REGISTRADA DEPOIS; bloqueio 10h01.
-- Uma observação às 10h registrada ENTRE as duas transferências e outra às 10h00m30s.
 (1, gen_random_uuid(), 'EPISODIO_ABERTO', '09:00:00', '09:00:00', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :SA)),
 (1, gen_random_uuid(), 'SETOR_ALTERADO', '10:00:00', '10:00:10', jsonb_build_object('de', :SA, 'para', :SB)),
 (1, gen_random_uuid(), 'OBSERVACAO_REGISTRADA', '10:00:00', '10:00:15', '{}'),
 (1, gen_random_uuid(), 'SETOR_ALTERADO', '10:00:00', '10:00:20', jsonb_build_object('de', :SB, 'para', :SA)),
 (1, gen_random_uuid(), 'OBSERVACAO_REGISTRADA', '10:00:30', '10:00:30', '{}'),
 (1, gen_random_uuid(), 'BLOQUEIO_DEFINIDO', '10:01:00', '10:01:00', :BLQ),
-- E2 (ordem efetiva invertida): em B às 9h; B→A às 10h; A→B também às 10h, registrada depois → B.
 (2, gen_random_uuid(), 'EPISODIO_ABERTO', '09:00:00', '09:00:00', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :SB)),
 (2, gen_random_uuid(), 'SETOR_ALTERADO', '10:00:00', '10:00:10', jsonb_build_object('de', :SB, 'para', :SA)),
 (2, gen_random_uuid(), 'SETOR_ALTERADO', '10:00:00', '10:00:20', jsonb_build_object('de', :SA, 'para', :SB)),
 (2, gen_random_uuid(), 'BLOQUEIO_DEFINIDO', '10:01:00', '10:01:00', :BLQ),
-- E3 (empate também em registrado_em → decide o id): em A; A→B (id 000…) e B→A (id fff…) → A.
 (3, gen_random_uuid(), 'EPISODIO_ABERTO', '09:00:00', '09:00:00', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :SA)),
 (3, '00000000-0000-0000-0000-0000000e3001', 'SETOR_ALTERADO', '10:00:00', '10:00:10', jsonb_build_object('de', :SA, 'para', :SB)),
 (3, 'ffffffff-0000-0000-0000-0000000e3002', 'SETOR_ALTERADO', '10:00:00', '10:00:10', jsonb_build_object('de', :SB, 'para', :SA)),
 (3, gen_random_uuid(), 'BLOQUEIO_DEFINIDO', '10:01:00', '10:01:00', :BLQ),
-- E4 (o mesmo empate, ids na ordem inversa): em B; B→A (id 000…) e A→B (id fff…) → B.
 (4, gen_random_uuid(), 'EPISODIO_ABERTO', '09:00:00', '09:00:00', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :SB)),
 (4, '00000000-0000-0000-0000-0000000e4001', 'SETOR_ALTERADO', '10:00:00', '10:00:10', jsonb_build_object('de', :SB, 'para', :SA)),
 (4, 'ffffffff-0000-0000-0000-0000000e4002', 'SETOR_ALTERADO', '10:00:00', '10:00:10', jsonb_build_object('de', :SA, 'para', :SB)),
 (4, gen_random_uuid(), 'BLOQUEIO_DEFINIDO', '10:01:00', '10:01:00', :BLQ),
-- E5 (transferência e início de bloqueio no MESMO instante): em A; bloqueio às 12h (registrado antes)
-- e A→B às 12h (registrada depois). Regra documentada: a transferência no mesmo instante prevalece
-- para o início do bloqueio (como nos pedaços de Gargalos/Evolução) → B.
 (5, gen_random_uuid(), 'EPISODIO_ABERTO', '09:00:00', '09:00:00', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', :SA)),
 (5, gen_random_uuid(), 'BLOQUEIO_DEFINIDO', '12:00:00', '12:00:00', :BLQ),
 (5, gen_random_uuid(), 'SETOR_ALTERADO', '12:00:00', '12:00:05', jsonb_build_object('de', :SA, 'para', :SB));

INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, registrado_em, autor_id, dados)
SELECT id, :U, ('66666666-0000-0000-0000-00000000000' || ep)::uuid, tipo, :'b'::timestamptz + o, :'b'::timestamptz + r, :ENF, dados
  FROM ev;
ALTER TABLE fluxo.evento_episodio ENABLE TRIGGER evento_autoria, ENABLE TRIGGER evento_verificar_ajuste_manual;

-- Linha do tempo: a transferência substituída no mesmo instante vira intervalo VAZIO.
SELECT teste.afirma(count(*) FILTER (WHERE fim = ini) = 1 AND
                    (SELECT valor FROM fluxo.rel_intervalos(:U, :'b', :'agora') x
                      WHERE x.episodio_id = '66666666-0000-0000-0000-000000000001' AND x.tipo = 'SETOR' AND x.fim IS NULL) = :SA,
                    'E1: A→B (registrada antes) não vigorou; o intervalo aberto é A')
  FROM fluxo.rel_intervalos(:U, :'b', :'agora') WHERE episodio_id = '66666666-0000-0000-0000-000000000001' AND tipo = 'SETOR';

CREATE TEMP TABLE q (setor text, secao text, chave text, quantidade bigint, base bigint);
INSERT INTO q SELECT 'TODOS', secao, coalesce(chave, ''), quantidade, base FROM fluxo.rel_qualidade(:U, :'b', :'p_fim', :'agora', NULL);
INSERT INTO q SELECT 'A', secao, coalesce(chave, ''), quantidade, base FROM fluxo.rel_qualidade(:U, :'b', :'p_fim', :'agora', :SA);
INSERT INTO q SELECT 'B', secao, coalesce(chave, ''), quantidade, base FROM fluxo.rel_qualidade(:U, :'b', :'p_fim', :'agora', :SB);
CREATE FUNCTION pg_temp.v(p_setor text, p_secao text, p_chave text DEFAULT '') RETURNS text LANGUAGE sql AS
$$ SELECT coalesce(max(quantidade), 0) || '/' || coalesce(max(base), 0) FROM q WHERE setor = p_setor AND secao = p_secao AND chave = p_chave $$;

-- Inícios de bloqueio (todos sem causa definida): A = E1, E3; B = E2, E4, E5. A V20 dava 0/0 em A e
-- 5/5 em B (E1 e E3 iam para B, cujo UUID vem depois no empate textual).
SELECT teste.afirma(pg_temp.v('A', 'BLOQUEIOS_INICIADOS_SEM_CAUSA') = '2/2',
                    'A: E1 (último evento às 10h = B→A) e E3 (empate de registro → id) — obtido: '
                    || pg_temp.v('A', 'BLOQUEIOS_INICIADOS_SEM_CAUSA'));
SELECT teste.afirma(pg_temp.v('B', 'BLOQUEIOS_INICIADOS_SEM_CAUSA') = '3/3',
                    'B: E2 (ordem invertida), E4 (id inverso) e E5 (transferência no mesmo instante prevalece) — obtido: '
                    || pg_temp.v('B', 'BLOQUEIOS_INICIADOS_SEM_CAUSA'));
SELECT teste.afirma(pg_temp.v('A', 'SETOR_NAO_ATRIBUIDO', 'BLOQUEIOS_INICIADOS') LIKE '0/%'
                    AND pg_temp.v('B', 'SETOR_NAO_ATRIBUIDO', 'BLOQUEIOS_INICIADOS') LIKE '0/%',
                    'nenhum início sem setor determinável');
SELECT teste.afirma((SELECT count(*) FROM fluxo.rel_intervalos(:U, :'b', :'agora') i
                      WHERE i.tipo = 'BLOQUEIO' AND i.ini >= :'b' AND i.ini < :'p_fim' AND i.episodio_id::text LIKE '66666666-%') = 5
                    AND split_part(pg_temp.v('A', 'BLOQUEIOS_INICIADOS_SEM_CAUSA'), '/', 2)::int
                      + split_part(pg_temp.v('B', 'BLOQUEIOS_INICIADOS_SEM_CAUSA'), '/', 2)::int = 5
                    AND split_part(pg_temp.v('TODOS', 'BLOQUEIOS_INICIADOS_SEM_CAUSA'), '/', 2)::int
                      = split_part(pg_temp.v('A', 'SETOR_NAO_ATRIBUIDO', 'BLOQUEIOS_INICIADOS'), '/', 2)::int,
                    'unidade: 5 inícios, A (2) + B (3) = 5 — sem perda nem duplicação entre setores; o total da unidade '
                    || 'é o mesmo com e sem filtro');

-- Mesma regra em Gargalos (iniciados no período por setor), que usa os intervalos com duração.
SELECT teste.afirma((SELECT parte FROM fluxo.rel_gargalos(:U, :'b', :'p_fim', :'agora', :SA, NULL, NULL)
                      WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'NAO_DEFINIDA') = 2
                    AND (SELECT parte FROM fluxo.rel_gargalos(:U, :'b', :'p_fim', :'agora', :SB, NULL, NULL)
                          WHERE secao = 'BLOQUEIO_CATEGORIA' AND chave = 'NAO_DEFINIDA') = 3,
                    'Gargalos concorda com a Qualidade: 2 inícios em A, 3 em B');
-- E Evolução (mesmos pedaços de Gargalos).
SELECT teste.afirma((SELECT parte FROM fluxo.rel_metricas_periodo(:U, :'b', :'p_fim', :'agora', :SA) WHERE secao = 'BLOQUEIO_MINUTOS') = 2
                    AND (SELECT parte FROM fluxo.rel_metricas_periodo(:U, :'b', :'p_fim', :'agora', :SB) WHERE secao = 'BLOQUEIO_MINUTOS') = 3,
                    'Evolução concorda: 2 inícios em A, 3 em B');

-- Registros pela ordem completa da linha do tempo (ocorrido → registrado → id):
-- E1: abertura A; A→B → B; observação registrada entre as transferências → B; B→A → A; obs. 10h00m30s → A; bloqueio → A.
-- E2: A=1 (B→A), B=3. E3: A=3, B=1 (A→B de id menor). E4: A=1, B=3. E5: abertura e bloqueio (registrado
-- antes da transferência) → A; transferência → B. Totais: A = 4+1+3+1+2 = 11; B = 2+3+1+3+1 = 10; 21 eventos.
SELECT teste.afirma(pg_temp.v('A', 'REGISTROS_RETROATIVOS') = '11/11' AND pg_temp.v('B', 'REGISTROS_RETROATIVOS') = '10/10',
                    'registros por setor do fato: A ' || pg_temp.v('A', 'REGISTROS_RETROATIVOS') || ', B '
                    || pg_temp.v('B', 'REGISTROS_RETROATIVOS'));
SELECT teste.afirma(pg_temp.v('A', 'SETOR_NAO_ATRIBUIDO', 'REGISTROS') LIKE '0/%', 'todo registro tem setor determinável');
ROLLBACK;
