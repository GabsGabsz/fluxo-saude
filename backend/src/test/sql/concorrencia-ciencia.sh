#!/usr/bin/env bash
# Concorrência entre ALTERAR uma regra de alerta e registrar CIÊNCIA (V14) — determinístico:
# a ordem é forçada por sessões controladas (FIFO) e só se avança quando o banco CONFIRMA o
# estado esperado (marcador emitido pela sessão; sessão em espera por bloqueio em
# pg_stat_activity). Nenhuma decisão depende de "dormir e torcer".
#  1) a alteração segura a regra; a ciência na versão lida ESPERA; a alteração confirma →
#     a ciência é recusada (FX409) e nada é gravado;
#  2) a ciência trava a regra primeiro; a alteração ESPERA; a ciência confirma na versão
#     enviada → a alteração prossegue depois. Em nenhum caso há deadlock ou ciência em
#     versão diferente da enviada.
set -uo pipefail
DB="${DB:?informe DB}"
A=00000000-0000-0000-0000-00000000000a
ADM=11111111-1111-1111-1111-000000000001
ENF=11111111-1111-1111-1111-000000000002
R=66666666-0000-0000-0000-00000000c1e1
EP=33333333-0000-0000-0000-00000000c1e1
TMP=$(mktemp -d)
# Sessões de trabalho nunca esperam indefinidamente (um travamento vira erro, não um CI pendurado).
export PGOPTIONS='-c lock_timeout=15s'

trap 'exec 3>&- 4>&- 2>/dev/null; rm -rf "$TMP"' EXIT
dono() { psql -X -At -v ON_ERROR_STOP=1 -U fluxo_owner -d "$DB" -c "$1"; }
falha() { echo "FALHA: $1"; exit 1; }

# Espera (com limite de segurança) até a condição informada ser verdadeira.
esperar() {  # $1 = descrição, $2 = comando de teste
    for _ in $(seq 1 300); do eval "$2" && return 0; sleep 0.05; done
    falha "tempo esgotado esperando: $1"
}
# pg_stat_activity de outros papéis só é visível ao superusuário (o mesmo que roda run-db-tests.sh).
em_espera_de_lock() {
    [ "$(psql -X -At -d "$DB" -c "SELECT count(*) FROM pg_stat_activity
                                    WHERE datname = current_database() AND application_name = '$1'
                                      AND wait_event_type = 'Lock'")" = "1" ]
}

dono "SELECT set_config('fluxo.usuario_id', '$ADM', true), set_config('fluxo.unidade_ids', '$A', true);
      INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite) VALUES ('$R', '$A', 'Corrida ciencia', 'TEMPO_TOTAL', interval '1 minute');
      INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES ('22222222-0000-0000-0000-00000000c1e1', '$A', 'Paciente Corrida');
      INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
      VALUES ('$EP', '$A', '22222222-0000-0000-0000-00000000c1e1', '00000000-0000-0000-0000-0000000005a1',
              now() - interval '2 hours', (SELECT id FROM fluxo.etapa WHERE unidade_id = '$A' AND codigo = 'EM_ATENDIMENTO'),
              now() - interval '2 hours');" >/dev/null || falha "preparação"

# ---------------------------------------------------------------- 1) alteração primeiro
V0=$(dono "SELECT versao FROM fluxo.regra_alerta WHERE id = '$R'")
mkfifo "$TMP/alt"; PGAPPNAME=alteracao psql -X -q -U fluxo_app -d "$DB" < "$TMP/alt" > "$TMP/alt.log" 2>&1 &
exec 3> "$TMP/alt"
echo "BEGIN; SELECT teste.ctx('$ADM', '$A');
      UPDATE fluxo.regra_alerta SET acao_esperada = 'Nova acao', versao = versao + 1 WHERE id = '$R';
      \\echo ALTERACAO_PENDENTE" >&3
esperar "alteração segurando a regra" "grep -q ALTERACAO_PENDENTE '$TMP/alt.log'"
PGAPPNAME=ciencia1 psql -X -q -U fluxo_app -d "$DB" > "$TMP/c1.log" 2>&1 <<SQL &
BEGIN; SELECT teste.ctx('$ENF', '$A');
-- Como o serviço: primeiro a trava da regra (espera a alteração e então devolve a versão NOVA)
SELECT 'travada=' || versao FROM fluxo.travar_regra_alerta('$R');
INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em)
VALUES (gen_random_uuid(), '$A', '$EP', '$R', $V0, (SELECT entrada_em FROM fluxo.episodio WHERE id = '$EP'));
COMMIT;
SQL
C1=$!
esperar "ciência aguardando o bloqueio da regra" "em_espera_de_lock ciencia1"
echo "COMMIT; \\echo ALTERACAO_CONFIRMADA" >&3
esperar "alteração confirmada" "grep -q ALTERACAO_CONFIRMADA '$TMP/alt.log'"
exec 3>&-
wait "$C1"
grep -q "travada=$((V0 + 1))" "$TMP/c1.log" || falha "a trava deveria devolver a versão nova: $(cat "$TMP/c1.log")"
grep -q 'mudou desde a leitura' "$TMP/c1.log" || falha "ciência na versão antiga deveria ser recusada: $(cat "$TMP/c1.log")"
[ "$(dono "SELECT count(*) FROM fluxo.ciencia_alerta WHERE episodio_id = '$EP'")" = "0" ] || falha "ciência gravada"
V1=$(dono "SELECT versao FROM fluxo.regra_alerta WHERE id = '$R'")
[ "$V1" -eq $((V0 + 1)) ] || falha "versão após alteração"
echo "1) alteração confirmada antes: ciência na versão $V0 recusada (vigente $V1), nada gravado"

# ---------------------------------------------------------------- 2) ciência primeiro
mkfifo "$TMP/cie"; PGAPPNAME=ciencia2 psql -X -q -U fluxo_app -d "$DB" < "$TMP/cie" > "$TMP/c2.log" 2>&1 &
exec 4> "$TMP/cie"
echo "BEGIN; SELECT teste.ctx('$ENF', '$A');
      SELECT 'travada=' || versao FROM fluxo.travar_regra_alerta('$R');
      INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em)
      VALUES (gen_random_uuid(), '$A', '$EP', '$R', $V1, (SELECT entrada_em FROM fluxo.episodio WHERE id = '$EP'));
      \\echo CIENCIA_PENDENTE" >&4
esperar "ciência segurando a regra" "grep -q CIENCIA_PENDENTE '$TMP/c2.log'"
grep -q "travada=$V1" "$TMP/c2.log" || falha "trava deveria devolver a versão $V1: $(cat "$TMP/c2.log")"
PGAPPNAME=alteracao2 psql -X -q -U fluxo_app -d "$DB" > "$TMP/alt2.log" 2>&1 <<SQL &
BEGIN; SELECT teste.ctx('$ADM', '$A');
UPDATE fluxo.regra_alerta SET acao_esperada = 'Outra acao', versao = versao + 1 WHERE id = '$R';
COMMIT;
SQL
A2=$!
esperar "alteração aguardando a ciência" "em_espera_de_lock alteracao2"
echo "COMMIT; \\echo CIENCIA_CONFIRMADA" >&4
esperar "ciência confirmada" "grep -q CIENCIA_CONFIRMADA '$TMP/c2.log'"
exec 4>&-
wait "$A2"
grep -qiE 'deadlock|ERROR' "$TMP/c2.log" "$TMP/alt2.log" && falha "erro inesperado: $(cat "$TMP/c2.log" "$TMP/alt2.log")"
[ "$(dono "SELECT string_agg(regra_versao::text, ',') FROM fluxo.ciencia_alerta WHERE episodio_id = '$EP'")" = "$V1" ] \
    || falha "ciência deveria estar só na versão enviada ($V1)"
[ "$(dono "SELECT versao FROM fluxo.regra_alerta WHERE id = '$R'")" -eq $((V1 + 1)) ] || falha "alteração não aplicada"
echo "2) ciência travou primeiro: gravada na versão $V1 (a enviada); alteração aplicada depois → versão $((V1 + 1)); sem deadlock"
