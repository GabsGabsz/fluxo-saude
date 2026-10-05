#!/usr/bin/env bash
# Corrida RN-008: uma sessão cria pendência enquanto outra encerra o episódio.
# Invariante: nunca pode existir episódio encerrado com pendência aberta.
set -uo pipefail
DB="${DB:?informe DB}"
CTX="SELECT teste.ctx('11111111-1111-1111-1111-000000000002', '00000000-0000-0000-0000-00000000000a');"
EP=33333333-0000-0000-0000-0000000000c1
psql -X -q -v ON_ERROR_STOP=1 -U fluxo_app -d "$DB" >/dev/null <<SQL
BEGIN; $CTX
INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000000a', 'Corrida');
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
VALUES ('$EP', '00000000-0000-0000-0000-00000000000a', '22222222-0000-0000-0000-0000000000c1',
        '00000000-0000-0000-0000-0000000005a1', now() - interval '1 hour', teste.etapa('00000000-0000-0000-0000-00000000000a','EM_ATENDIMENTO'), now() - interval '1 hour');
COMMIT;
SQL
# Sessão 1: cria pendência e segura a transação aberta 2 s
psql -X -q -U fluxo_app -d "$DB" >/tmp/corrida1.log 2>&1 <<SQL &
BEGIN; $CTX
INSERT INTO fluxo.pendencia (unidade_id, episodio_id, categoria, descricao, responsavel_papel, prazo, criticidade_operacional)
VALUES ('00000000-0000-0000-0000-00000000000a', '$EP', 'LOGISTICA', 'Pendência concorrente', 'TRANSPORTE', now() + interval '1 hour', 'ALTA');
SELECT pg_sleep(2);
COMMIT;
SQL
sleep 0.5
# Sessão 2: encerra o episódio (alta) no meio da transação da sessão 1
psql -X -q -U fluxo_app -d "$DB" >/tmp/corrida2.log 2>&1 <<SQL
BEGIN; $CTX
UPDATE fluxo.episodio SET versao = versao + 1, etapa_id = teste.etapa('00000000-0000-0000-0000-00000000000a','ALTA'),
       etapa_desde = clock_timestamp(), desfecho = 'ALTA', encerrado_em = clock_timestamp() WHERE id = '$EP';
COMMIT;
SQL
wait
violacoes=$(psql -X -At -U fluxo_owner -d "$DB" -c "
  SELECT count(*) FROM fluxo.episodio e JOIN fluxo.pendencia p ON p.episodio_id = e.id
   WHERE e.id = '$EP' AND e.encerrado_em IS NOT NULL AND p.status = 'ABERTA'")
echo "violações RN-008 após corrida: $violacoes (sessão 2: $(grep -o 'pendências abertas' /tmp/corrida2.log | head -1 || true))"
[ "$violacoes" -eq 0 ]
