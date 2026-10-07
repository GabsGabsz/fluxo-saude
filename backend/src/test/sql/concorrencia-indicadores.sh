#!/usr/bin/env bash
# Visão única dos indicadores (ADR-0009; revisão do PR #10, ponto 4) — determinístico, com
# conexões reais: uma sessão de LEITURA faz as consultas dos indicadores em sequência; ENTRE a
# primeira (desfechos) e as seguintes (permanência, volume, retrato), outra sessão ENCERRA um
# episódio e confirma. A ordem é forçada por FIFO e marcadores; só se avança quando o banco
# confirma o estado esperado. Nenhuma decisão depende de "dormir e torcer".
#  1) REPEATABLE READ READ ONLY (o modo da aplicação): todas as consultas veem o mesmo
#     instantâneo — desfechos = permanência = saídas do volume, e o encerramento concorrente
#     não aparece pela metade. Uma nova leitura vê o encerramento em TODAS as consultas.
#  2) Controle: a MESMA sequência em READ COMMITTED mistura estados (permanência = desfechos + 1),
#     o que prova que o teste detecta o problema que o modo da aplicação evita.
set -uo pipefail
DB="${DB:?informe DB}"
A=00000000-0000-0000-0000-00000000000a
ENF=11111111-1111-1111-1111-000000000002
S1=00000000-0000-0000-0000-0000000005a1
TMP=$(mktemp -d)
export PGOPTIONS='-c lock_timeout=15s'

trap 'exec 3>&- 2>/dev/null; rm -rf "$TMP"' EXIT
dono() { psql -X -At -v ON_ERROR_STOP=1 -U fluxo_owner -d "$DB" -c "$1"; }
falha() { echo "FALHA: $1"; exit 1; }
esperar() {  # $1 = descrição, $2 = comando de teste
    for _ in $(seq 1 300); do eval "$2" && return 0; sleep 0.05; done
    falha "tempo esgotado esperando: $1"
}
valor() { grep -o "^$1=[0-9-]*" "$2" | tail -1 | cut -d= -f2; }

# Episódios fictícios abertos há 2 h na unidade A (um para cada cenário).
dono "SELECT set_config('fluxo.usuario_id', '$ENF', true), set_config('fluxo.unidade_ids', '$A', true);
      INSERT INTO fluxo.paciente (id, unidade_id, nome) VALUES
        ('22222222-0000-0000-0000-0000000001d1', '$A', 'Paciente Indicador Corrida Um'),
        ('22222222-0000-0000-0000-0000000001d2', '$A', 'Paciente Indicador Corrida Dois');
      INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde)
      SELECT ('33333333-0000-0000-0000-0000000001d' || n)::uuid, '$A', ('22222222-0000-0000-0000-0000000001d' || n)::uuid,
             '$S1', now() - interval '2 hours', (SELECT id FROM fluxo.etapa WHERE unidade_id = '$A' AND codigo = 'EM_ATENDIMENTO'),
             now() - interval '2 hours'
        FROM generate_series(1, 2) n;" >/dev/null || falha "preparação"
FUSO=$(dono "SELECT fuso_horario FROM fluxo.unidade WHERE id = '$A'")

# Consultas de uma resposta de indicadores (mesmas funções e período: hoje ± 1 dia, datas locais).
PERIODO="(SELECT inicio FROM fluxo.ind_periodo('$FUSO', (now() AT TIME ZONE '$FUSO')::date - 1, (now() AT TIME ZONE '$FUSO')::date + 1)),
         (SELECT fim FROM fluxo.ind_periodo('$FUSO', (now() AT TIME ZONE '$FUSO')::date - 1, (now() AT TIME ZONE '$FUSO')::date + 1))"
Q_DESF="SELECT 'desf=' || coalesce(sum(quantidade), 0) FROM fluxo.ind_desfechos('$A', $PERIODO, NULL);"
Q_RESTO="SELECT 'perm=' || (incluidos + excluidos) FROM fluxo.ind_permanencia('$A', $PERIODO, NULL);
         SELECT 'vol=' || coalesce(sum(saidas), 0) FROM fluxo.ind_volume_diario('$A', '$FUSO',
                (now() AT TIME ZONE '$FUSO')::date - 1, (now() AT TIME ZONE '$FUSO')::date + 1, NULL);
         SELECT 'abertos=' || quantidade FROM fluxo.ind_retrato('$A', NULL, now()) WHERE dimensao = 'ABERTOS';"

encerrar() {  # $1 = episódio — como uma gravação da aplicação, em outra conexão, confirmada
    psql -X -q -v ON_ERROR_STOP=1 -U fluxo_app -d "$DB" >/dev/null <<SQL || falha "encerramento concorrente"
BEGIN; SELECT teste.ctx('$ENF', '$A');
UPDATE fluxo.episodio SET etapa_id = (SELECT id FROM fluxo.etapa WHERE unidade_id = '$A' AND codigo = 'ALTA'),
       etapa_desde = now(), desfecho = 'ALTA', encerrado_em = now(), versao = versao + 1 WHERE id = '$1';
COMMIT;
SQL
    [ "$(dono "SELECT encerrado_em IS NOT NULL FROM fluxo.episodio WHERE id = '$1'")" = "t" ] || falha "encerramento não confirmado"
}

leitura_com_corrida() {  # $1 = modo (BEGIN ...), $2 = episódio encerrado no meio, $3 = log
    mkfifo "$TMP/$3.fifo"
    psql -X -q -At -U fluxo_app -d "$DB" < "$TMP/$3.fifo" > "$TMP/$3" 2>&1 &
    local leitor=$!
    exec 3> "$TMP/$3.fifo"
    echo "$1; SELECT 'modo=' || current_setting('transaction_isolation') || '/' || current_setting('transaction_read_only');
          SELECT teste.ctx('$ENF', '$A'); $Q_DESF \\echo PRIMEIRA_LEITURA" >&3
    esperar "primeira consulta concluída" "grep -q PRIMEIRA_LEITURA '$TMP/$3'"
    encerrar "$2"
    echo "$Q_RESTO COMMIT; \\echo FIM" >&3
    exec 3>&-
    wait "$leitor"
    grep -q FIM "$TMP/$3" || falha "leitura não terminou: $(cat "$TMP/$3")"
    ! grep -q ERROR "$TMP/$3" || falha "erro na leitura: $(cat "$TMP/$3")"
}

# ---------------------------------------------------------------- 1) modo da aplicação
leitura_com_corrida "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY" 33333333-0000-0000-0000-0000000001d1 rr.log
grep -q "modo=repeatable read/on" "$TMP/rr.log" || falha "modo da leitura: $(cat "$TMP/rr.log")"
D=$(valor desf "$TMP/rr.log"); P=$(valor perm "$TMP/rr.log"); V=$(valor vol "$TMP/rr.log"); AB=$(valor abertos "$TMP/rr.log")
[ "$D" = "$P" ] && [ "$P" = "$V" ] || falha "visão única violada: desfechos=$D permanência=$P saídas=$V"
# Nova leitura (outra transação): o encerramento aparece em TODAS as consultas, junto.
psql -X -q -At -U fluxo_app -d "$DB" > "$TMP/depois.log" 2>&1 <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT teste.ctx('$ENF', '$A'); $Q_DESF $Q_RESTO COMMIT;
SQL
D2=$(valor desf "$TMP/depois.log"); P2=$(valor perm "$TMP/depois.log"); V2=$(valor vol "$TMP/depois.log")
AB2=$(valor abertos "$TMP/depois.log")
[ "$D2" -eq $((D + 1)) ] && [ "$P2" -eq $((P + 1)) ] && [ "$V2" -eq $((V + 1)) ] && [ "$AB2" -eq $((AB - 1)) ] \
    || falha "nova leitura deveria ver o encerramento em tudo: desf=$D2 perm=$P2 vol=$V2 abertos=$AB2 (antes $D/$P/$V/$AB)"
echo "1) REPEATABLE READ READ ONLY: desfechos=$D permanência=$P saídas=$V abertos=$AB na mesma resposta (encerramento concorrente fora, inteiro); nova leitura: $D2/$P2/$V2/$AB2"

# ---------------------------------------------------------------- 2) controle em READ COMMITTED
leitura_com_corrida "BEGIN ISOLATION LEVEL READ COMMITTED" 33333333-0000-0000-0000-0000000001d2 rc.log
D=$(valor desf "$TMP/rc.log"); P=$(valor perm "$TMP/rc.log"); V=$(valor vol "$TMP/rc.log")
[ "$P" -eq $((D + 1)) ] && [ "$V" -eq $((D + 1)) ] \
    || falha "controle: em READ COMMITTED a sequência deveria misturar estados (desf=$D perm=$P vol=$V)"
echo "2) controle READ COMMITTED: desfechos=$D mas permanência=$P e saídas=$V na mesma sequência (o teste detecta a mistura)"
