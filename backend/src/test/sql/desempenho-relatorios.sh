#!/usr/bin/env bash
# Tempos dos relatórios gerenciais (V19/V20) em volume sintético (NÃO faz parte da suíte; referência
# para ADR-0010). Uso: PGHOST=... PGUSER=<superusuário> ./desempenho-relatorios.sh [episodios]
# Cria um banco descartável, aplica as migrações, carrega dados sintéticos (gatilhos desligados
# só durante a carga, pelo dono) e mostra EXPLAIN ANALYZE como o papel da aplicação.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
MIG="$DIR/../../main/resources/db/migration"
N="${1:-50000}"
DB="fluxo_desempenho_rel_$$"
psql -X -q -v ON_ERROR_STOP=1 -d postgres <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='fluxo_owner') THEN CREATE ROLE fluxo_owner LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='fluxo_app') THEN CREATE ROLE fluxo_app LOGIN; END IF;
END \$\$;
CREATE DATABASE ${DB} OWNER fluxo_owner;
SQL
trap 'psql -X -q -d postgres -c "DROP DATABASE IF EXISTS ${DB} WITH (FORCE)" >/dev/null' EXIT
for f in $(ls "$MIG"/V*__*.sql | sort -V); do
  sed "s/\${app_role}/fluxo_app/g" "$f" | PGOPTIONS="-c search_path=fluxo,auditoria" \
    psql -X -q -v ON_ERROR_STOP=1 -1 -U fluxo_owner -d "$DB" >/dev/null
done
psql -X -q -v ON_ERROR_STOP=1 -U fluxo_owner -d "$DB" -v n="$N" <<'SQL'
INSERT INTO fluxo.unidade (id, codigo, nome, tipo) VALUES
  ('00000000-0000-0000-0000-00000000000a', 'U_A', 'Unidade A', 'UPA'), ('00000000-0000-0000-0000-00000000000b', 'U_B', 'Unidade B', 'UPA');
SELECT fluxo.provisionar_unidade('00000000-0000-0000-0000-00000000000a');
SELECT fluxo.provisionar_unidade('00000000-0000-0000-0000-00000000000b');
INSERT INTO fluxo.setor (id, unidade_id, codigo, nome)
SELECT gen_random_uuid(), u, 'S1', 'Setor' FROM unnest(ARRAY['00000000-0000-0000-0000-00000000000a','00000000-0000-0000-0000-00000000000b']::uuid[]) u;
INSERT INTO fluxo.usuario (id, login, nome, senha_hash, unidade_gestora_id)
VALUES ('11111111-1111-1111-1111-000000000001', 'carga', 'Carga', '{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$ZmljdGljaW8$ZmljdGljaW8', '00000000-0000-0000-0000-00000000000a');
INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES
  ('11111111-1111-1111-1111-000000000001', '00000000-0000-0000-0000-00000000000a', 'COORDENACAO_FLUXO');
ALTER TABLE fluxo.paciente DISABLE TRIGGER USER;
ALTER TABLE fluxo.episodio DISABLE TRIGGER USER;
ALTER TABLE fluxo.evento_episodio DISABLE TRIGGER USER;
-- metade dos episódios em cada unidade, espalhados em 2 anos; 3 eventos de etapa e 2 de bloqueio cada
WITH g AS (SELECT n, CASE WHEN n % 2 = 0 THEN '00000000-0000-0000-0000-00000000000a'::uuid
                                         ELSE '00000000-0000-0000-0000-00000000000b'::uuid END AS u,
                  now() - (random() * interval '730 days') AS ent
             FROM generate_series(1, :n) n)
, p AS (INSERT INTO fluxo.paciente (id, unidade_id, nome)
        SELECT md5('p' || n)::uuid, u, 'Paciente ' || n FROM g RETURNING id)
INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde, desfecho, encerrado_em,
                            criado_por)
SELECT md5('e' || n)::uuid, u, md5('p' || n)::uuid, (SELECT id FROM fluxo.setor s WHERE s.unidade_id = g.u),
       ent, (SELECT id FROM fluxo.etapa et WHERE et.unidade_id = g.u AND et.codigo = 'ALTA'), ent + interval '20 hours',
       'ALTA', ent + interval '20 hours', '11111111-1111-1111-1111-000000000001'
  FROM g;
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, registrado_em, autor_id, dados)
SELECT gen_random_uuid(), e.unidade_id, e.id, t.tipo, e.entrada_em + t.d, e.entrada_em + t.d, '11111111-1111-1111-1111-000000000001', t.dados::jsonb
  FROM fluxo.episodio e,
       (VALUES ('ETAPA_ALTERADA', interval '2 hours', '{"de":"EM_ATENDIMENTO","para":"TRANSFERENCIA_SOLICITADA"}'),
               ('ETAPA_ALTERADA', interval '10 hours', '{"de":"TRANSFERENCIA_SOLICITADA","para":"ACEITO"}'),
               ('ETAPA_ALTERADA', interval '20 hours', '{"de":"ACEITO","para":"ALTA"}'),
               ('BLOQUEIO_DEFINIDO', interval '3 hours', '{"motivo":"SEM_VAGA","categoria":"REGULACAO"}'),
               ('BLOQUEIO_REMOVIDO', interval '9 hours', '{"motivo":"SEM_VAGA"}')) AS t(tipo, d, dados);
INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, registrado_em, autor_id, dados)
SELECT gen_random_uuid(), e.unidade_id, e.id, 'EPISODIO_ABERTO', e.entrada_em, e.entrada_em,
       '11111111-1111-1111-1111-000000000001', jsonb_build_object('etapa', 'EM_ATENDIMENTO', 'setor_id', e.setor_id::text)
  FROM fluxo.episodio e;
-- 2% dos episódios continuam abertos (bloqueados), para o estoque e os "em curso"
UPDATE fluxo.episodio SET desfecho = NULL, encerrado_em = NULL,
       etapa_id = (SELECT id FROM fluxo.etapa et WHERE et.unidade_id = episodio.unidade_id AND et.codigo = 'ACEITO')
 WHERE ('x' || substr(id::text, 1, 8))::bit(32)::int % 50 = 0;
ALTER TABLE fluxo.paciente ENABLE TRIGGER USER;
ALTER TABLE fluxo.episodio ENABLE TRIGGER USER;
ALTER TABLE fluxo.evento_episodio ENABLE TRIGGER USER;
ANALYZE;
SQL
echo "== volume: $N episódios, $((N * 6)) eventos (2 unidades)"
psql -X -U fluxo_app -d "$DB" <<'SQL'
SELECT set_config('fluxo.usuario_id', '11111111-1111-1111-1111-000000000001', false),
       set_config('fluxo.unidade_ids', '00000000-0000-0000-0000-00000000000a', false) \g /dev/null
\timing on
\echo -- 30 dias
SELECT count(*) FROM fluxo.rel_resumo('00000000-0000-0000-0000-00000000000a', now() - interval '30 days', now(), now(), NULL);
SELECT count(*) FROM fluxo.rel_gargalos('00000000-0000-0000-0000-00000000000a', now() - interval '30 days', now(), now(), NULL, NULL, NULL);
SELECT count(*) FROM fluxo.rel_pendencias('00000000-0000-0000-0000-00000000000a', now() - interval '30 days', now(), now(), NULL, NULL);
SELECT count(*) FROM fluxo.rel_metricas_periodo('00000000-0000-0000-0000-00000000000a', now() - interval '30 days', now(), now(), NULL);
SELECT count(*) FROM fluxo.rel_qualidade('00000000-0000-0000-0000-00000000000a', now() - interval '30 days', now(), now(), NULL);
\echo -- 366 dias
SELECT count(*) FROM fluxo.rel_gargalos('00000000-0000-0000-0000-00000000000a', now() - interval '366 days', now(), now(), NULL, NULL, NULL);
SELECT count(*) FROM fluxo.rel_metricas_periodo('00000000-0000-0000-0000-00000000000a', now() - interval '366 days', now(), now(), NULL);
\echo -- 366 dias, gargalos com filtro de setor e de etapa (interseção pela linha do tempo)
SELECT count(*) FROM fluxo.rel_gargalos('00000000-0000-0000-0000-00000000000a', now() - interval '366 days', now(), now(),
       (SELECT id FROM fluxo.setor WHERE unidade_id = '00000000-0000-0000-0000-00000000000a'),
       (SELECT id FROM fluxo.etapa WHERE unidade_id = '00000000-0000-0000-0000-00000000000a' AND codigo = 'TRANSFERENCIA_SOLICITADA'), NULL);
SELECT count(*) FROM fluxo.rel_resumo('00000000-0000-0000-0000-00000000000a', now() - interval '366 days', now(), now(), NULL);
\echo -- 366 dias, qualidade sem e com filtro de setor (V20: setor do fato pela linha do tempo; inícios normalizados)
SELECT count(*) FROM fluxo.rel_qualidade('00000000-0000-0000-0000-00000000000a', now() - interval '366 days', now(), now(), NULL);
SELECT count(*) FROM fluxo.rel_qualidade('00000000-0000-0000-0000-00000000000a', now() - interval '366 days', now(), now(),
       (SELECT id FROM fluxo.setor WHERE unidade_id = '00000000-0000-0000-0000-00000000000a'));
\timing off
SQL
