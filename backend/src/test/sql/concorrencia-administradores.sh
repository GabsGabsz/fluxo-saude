#!/usr/bin/env bash
# Corridas na gestão de usuários (V11):
#  1) dois administradores alteram os papéis do MESMO usuário a partir da mesma versão:
#     exatamente um vence; o outro recebe "versão desatualizada" (nada é sobrescrito);
#  2) dois administradores trocam, ao mesmo tempo, o papel de administrador UM DO OUTRO
#     por outro papel (exercita exclusão + inserção de lotação): sem deadlock, exatamente
#     um vence e a unidade nunca termina sem administrador ativo.
set -uo pipefail
DB="${DB:?informe DB}"
A=00000000-0000-0000-0000-00000000000a
ADM_A=11111111-1111-1111-1111-000000000001
ENF=11111111-1111-1111-1111-000000000002
ADM3=11111111-1111-1111-1111-000000000901
dono() { psql -X -At -v ON_ERROR_STOP=1 -U fluxo_owner -d "$DB" -c "$1"; }

dono "INSERT INTO fluxo.usuario (id, login, nome, senha_hash) VALUES ('$ADM3', 'admin3.a', 'Admin Tres',
      '{argon2@SpringSecurity_v5_8}\$argon2id\$v=19\$m=16384,t=2,p=1\$ZmljdGljaW8\$ZmljdGljaW8');
      INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES ('$ADM3', '$A', 'ADMINISTRADOR');" >/dev/null

# ---- 1) conflito de versão
V=$(dono "SELECT versao FROM fluxo.usuario WHERE id = '$ENF'")
psql -X -q -U fluxo_app -d "$DB" >/tmp/adm1.log 2>&1 <<SQL &
BEGIN; SELECT teste.ctx('$ADM_A', '$A');
SELECT fluxo.admin_definir_papeis('$ENF', $V, ARRAY['ENFERMAGEM','MEDICO']::fluxo.papel[]);
SELECT pg_sleep(1.5);
COMMIT;
SQL
sleep 0.5
psql -X -q -U fluxo_app -d "$DB" >/tmp/adm2.log 2>&1 <<SQL
BEGIN; SELECT teste.ctx('$ADM3', '$A');
SELECT fluxo.admin_definir_papeis('$ENF', $V, ARRAY['TRANSPORTE']::fluxo.papel[]);
COMMIT;
SQL
wait
papeis=$(dono "SELECT string_agg(papel::text, ',' ORDER BY papel) FROM fluxo.lotacao WHERE usuario_id = '$ENF' AND unidade_id = '$A'")
conflito=$(grep -c 'versão desatualizada' /tmp/adm2.log || true)
echo "papéis após corrida de versão: $papeis (perdedora recebeu conflito: $conflito)"
[ "$papeis" = "ENFERMAGEM,MEDICO" ] && [ "$conflito" -ge 1 ] || exit 1

# ---- 2) revogação cruzada de administradores
VA=$(dono "SELECT versao FROM fluxo.usuario WHERE id = '$ADM_A'")
V3=$(dono "SELECT versao FROM fluxo.usuario WHERE id = '$ADM3'")
psql -X -q -U fluxo_app -d "$DB" >/tmp/adm3.log 2>&1 <<SQL &
BEGIN; SELECT teste.ctx('$ADM_A', '$A');
SELECT fluxo.admin_definir_papeis('$ADM3', $V3, ARRAY['AUDITORIA']::fluxo.papel[]);
SELECT pg_sleep(1.5);
COMMIT;
SQL
sleep 0.5
psql -X -q -U fluxo_app -d "$DB" >/tmp/adm4.log 2>&1 <<SQL
BEGIN; SELECT teste.ctx('$ADM3', '$A');
SELECT fluxo.admin_definir_papeis('$ADM_A', $VA, ARRAY['AUDITORIA']::fluxo.papel[]);
COMMIT;
SQL
wait
admins=$(dono "SELECT count(*) FROM fluxo.lotacao l JOIN fluxo.usuario u ON u.id = l.usuario_id AND u.ativo
               WHERE l.unidade_id = '$A' AND l.papel = 'ADMINISTRADOR'")
recusa=$(grep -cE 'exige administrador|sem administrador ativo' /tmp/adm4.log || true)
deadlock=$(cat /tmp/adm3.log /tmp/adm4.log | grep -c 'deadlock' || true)
echo "administradores ativos após revogação cruzada: $admins (segunda recusada: $recusa, deadlocks: $deadlock)"
[ "$admins" -eq 1 ] && [ "$recusa" -ge 1 ] && [ "$deadlock" -eq 0 ]
