#!/usr/bin/env bash
# Corrida na troca da própria senha (V12): duas sessões do mesmo usuário, com a MESMA versão
# de credencial, trocam a senha ao mesmo tempo. Exatamente uma grava; a outra não grava nada
# (NULL → a aplicação trata como sessão revogada). Sem "lost update" que mantivesse viva a
# sessão perdedora ou desfizesse uma redefinição.
set -uo pipefail
DB="${DB:?informe DB}"
ENF=11111111-1111-1111-1111-000000000002
dono() { psql -X -At -v ON_ERROR_STOP=1 -U fluxo_owner -d "$DB" -c "$1"; }
V=$(dono "SELECT credencial_versao FROM fluxo.usuario WHERE id = '$ENF'")
H1='{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=19456,t=2,p=1$dGVzdGU$cHJpbWVpcmE'
H2='{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=19456,t=2,p=1$dGVzdGU$c2VndW5kYQ'
psql -X -At -U fluxo_app -d "$DB" >/tmp/cred1.log 2>&1 <<SQL &
BEGIN; SELECT fluxo.aplicar_contexto('$ENF', NULL, NULL, NULL, $V) IS NOT NULL;
SELECT 'r1=' || coalesce(fluxo.alterar_senha_propria('$H1', $V)::text, 'NULL');
SELECT pg_sleep(1.5);
COMMIT;
SQL
sleep 0.5
psql -X -At -U fluxo_app -d "$DB" >/tmp/cred2.log 2>&1 <<SQL
BEGIN; SELECT fluxo.aplicar_contexto('$ENF', NULL, NULL, NULL, $V) IS NOT NULL;
SELECT 'r2=' || coalesce(fluxo.alterar_senha_propria('$H2', $V)::text, 'NULL');
COMMIT;
SQL
wait
r1=$(grep -o 'r1=[^ ]*' /tmp/cred1.log); r2=$(grep -o 'r2=[^ ]*' /tmp/cred2.log)
final=$(dono "SELECT credencial_versao FROM fluxo.usuario WHERE id = '$ENF'")
hash_ok=$(dono "SELECT senha_hash = '$H1' FROM fluxo.usuario WHERE id = '$ENF'")
echo "troca concorrente da própria senha: $r1, $r2, versão final $final, senha da vencedora mantida: $hash_ok"
[ "$r1" = "r1=$((V + 1))" ] && [ "$r2" = "r2=NULL" ] && [ "$final" -eq $((V + 1)) ] && [ "$hash_ok" = "t" ]
