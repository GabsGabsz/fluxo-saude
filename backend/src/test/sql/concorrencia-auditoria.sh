#!/usr/bin/env bash
# Estresse da cadeia de hash: N sessões simultâneas gravando auditoria (via triggers
# de negócio e via auditoria.registrar). Ao final a cadeia deve estar íntegra.
# Uso: DB=<banco já migrado com fixtures> ./concorrencia-auditoria.sh [sessoes] [ops]
set -euo pipefail
DB="${DB:?informe DB}"; N="${1:-8}"; OPS="${2:-50}"
worker() {
  local w=$1
  for i in $(seq 1 "$OPS"); do
    psql -X -q -v ON_ERROR_STOP=1 -U fluxo_app -d "$DB" >/dev/null <<SQL
BEGIN;
SELECT teste.ctx('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a');
INSERT INTO fluxo.paciente (unidade_id, nome) VALUES ('00000000-0000-0000-0000-00000000000a', 'Carga $w-$i');
SELECT auditoria.registrar('CARGA', 'teste', '$w-$i');
COMMIT;
SQL
  done
}
antes=$(psql -X -At -U fluxo_owner -d "$DB" -c "SELECT count(*) FROM auditoria.registro")
for w in $(seq 1 "$N"); do worker "$w" & done
wait
depois=$(psql -X -At -U fluxo_owner -d "$DB" -c "SELECT count(*) FROM auditoria.registro")
quebras=$(psql -X -At -U fluxo_owner -d "$DB" -c "SELECT count(*) FROM auditoria.verificar_cadeia()")
esperado=$((N * OPS * 2))
echo "registros novos: $((depois - antes)) (esperado $esperado); quebras na cadeia: $quebras"
[ "$((depois - antes))" -eq "$esperado" ] && [ "$quebras" -eq 0 ]
