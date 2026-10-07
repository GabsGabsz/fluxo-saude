#!/usr/bin/env bash
# =============================================================================
# Operação do ambiente de HOMOLOGAÇÃO do Fluxo Saúde (dados fictícios; não é produção).
# Requer: Docker Engine/Desktop com Compose v2, bash, openssl, sha256sum, curl.
# Linux ou Windows com Docker Desktop + WSL2 (executar DENTRO da distribuição WSL).
#
#   fluxo.sh [-p PROJETO] COMANDO [opções]       (projeto padrão: fluxo-homologacao)
#
#   preparar          gera segredos e configuração do projeto (uma vez; nunca sobrescreve)
#   construir [--tag T] [--sem-ativar]   constrói a imagem (tag nova, commit no rótulo) e a ativa
#   subir             sobe o banco, roda a MIGRAÇÃO com a imagem do projeto e só então a aplicação;
#                     confere a compatibilidade imagem × esquema (nunca constrói em silêncio)
#   parar | reiniciar | estado | logs [serviço] | diagnostico | certificado ARQ
#   primeiro-acesso   1ª unidade + 1º administrador (senha provisória; troca obrigatória)
#   demo --confirmo-dados-ficticios   carrega os dados FICTÍCIOS do E2E (contas com senhas públicas!)
#   backup [--destino DIR]            pg_dump + manifesto + SHA-256 (sem dados de sessão)
#   restaurar ARQ.dump [--projeto-destino NOME] [--porta N] [--rede A.B.C] [--imagem REF]
#                     restaura num projeto/volume NOVO, com a imagem REGISTRADA no backup (ou --imagem
#                     compatível); migração só valida; confere auditoria, contagens e estado final
#   atualizar [--imagem REF]   backup + manutenção + migração separada; distingue falha sem avanço
#                     (volta a imagem anterior) de avanço parcial (aplicação fica parada)
#   atualizar --imagem REF --continuar-parcial   correção PARA A FRENTE de um avanço parcial: confere
#                     as migrações já aplicadas (arquivo + SHA-256) contra a imagem corretiva, faz
#                     backup do estado parcial, aplica só as pendentes e só então volta a atender
#   remover --confirmo-apagar-dados   apaga contêineres, VOLUMES, configuração e segredos do projeto
#
# Ver docs/operacao/homologacao.md. Nenhuma senha é escrita em log, argumento ou artefato.
# =============================================================================
set -euo pipefail
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(cd "$AQUI/../.." && pwd)"
PROJETO="${FLUXO_PROJETO:-fluxo-homologacao}"

erro() { echo "ERRO: $*" >&2; exit 1; }
info() { echo "== $*"; }

if [ "${1:-}" = "-p" ] || [ "${1:-}" = "--projeto" ]; then
    PROJETO="${2:?informe o nome do projeto}"; shift 2
fi
[[ "$PROJETO" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] || erro "nome de projeto inválido (minúsculas, dígitos, - e _)"
COMANDO="${1:-ajuda}"; shift || true

AMBIENTES="$AQUI/ambientes"
ENVF="$AMBIENTES/$PROJETO.env"
# Marca de atualização com AVANÇO PARCIAL pendente (e inventário das migrações já aplicadas: arquivo +
# SHA-256, vindo das imagens que as aplicaram). Enquanto existir, nada volta a atender sozinho.
MARCA="$AMBIENTES/$PROJETO.parcial"
INVENT="$AMBIENTES/$PROJETO.parcial.inventario"

carregar() {
    [ -f "$ENVF" ] || erro "projeto '$PROJETO' não preparado: rode '$0 -p $PROJETO preparar'"
    set -a
    # shellcheck source=/dev/null
    . "$ENVF"
    set +a
}
# Projeto ainda sem imagem (antes de "construir"): um nome-sentinela permite ps/stop/down; "subir" e
# "restaurar" exigem uma imagem real e existente.
dc() { FLUXO_IMAGEM="${FLUXO_IMAGEM:-fluxo-saude:nao-construida}" docker compose -f "$AQUI/compose.yaml" -p "$PROJETO" --env-file "$ENVF" "$@"; }

aleatorio() { openssl rand -hex 32; }

definir() {   # definir CHAVE VALOR  -> grava/atualiza no arquivo de ambiente do projeto
    local k="$1" v="$2"
    if grep -q "^$k=" "$ENVF"; then sed -i "s|^$k=.*|$k=$v|" "$ENVF"; else echo "$k=$v" >> "$ENVF"; fi
    export "$k=$v"
}

imagem_id() { docker image inspect -f '{{.Id}}' "$1" 2>/dev/null; }

# Inventário completo da imagem: "<sha256>  V<n>__<nome>.sql" por migração embutida.
inventario_imagem() { docker run --rm --network none --entrypoint cat "$1" /app/migracoes.txt; }

# filtrar_versoes "1,2,3" < inventário  -> só as linhas dessas versões
filtrar_versoes() {
    awk -v lista="$1" 'BEGIN { n = split(lista, v, ","); for (i = 1; i <= n; i++) quer[v[i]] = 1 }
        { if (match($2, /^V[0-9]+__/)) { ver = substr($2, 2, RLENGTH - 3); if (ver in quer) print } }'
}

exigir_sem_parcial() {
    [ ! -f "$MARCA" ] || erro "há uma atualização com AVANÇO PARCIAL pendente em '$PROJETO' ($(sed -n 's/^migracoes_depois=//p' "$MARCA")).
  Nenhuma imagem volta a atender sozinha. Use a correção para a frente:
    bash $0 -p $PROJETO atualizar --imagem <imagem-corrigida> --continuar-parcial
  ou a restauração isolada do backup anterior à atualização:
    bash $0 -p $PROJETO restaurar $(sed -n 's/^backup_previo=//p' "$MARCA")
  (docs/operacao/homologacao.md §7)"
}
imagem_commit() { docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$1" 2>/dev/null; }

exigir_imagem() {   # exigir_imagem REF [contexto]
    imagem_id "$1" >/dev/null || erro "imagem '$1' não existe neste host${2:+ ($2)}. Construa-a explicitamente: 'fluxo.sh -p $PROJETO construir' (nada é construído em silêncio)"
}

# Versões de migração EMBUTIDAS na imagem (inventário /app/migracoes.txt gerado no build).
versoes_imagem() {
    docker run --rm --network none --entrypoint cat "$1" /app/migracoes.txt \
        | sed -nE 's/^[0-9a-f]{64}  V([0-9]+)__.*$/\1/p' | sort -n | paste -sd, -
}

# Versões APLICADAS no banco do projeto (vazio se o histórico ainda não existe).
versoes_banco() {
    if [ "$(psql_super -At -c "SELECT to_regclass('fluxo.flyway_schema_history') IS NOT NULL")" != "t" ]; then echo ""; return; fi
    psql_super -At -c "SELECT coalesce(string_agg(version, ',' ORDER BY version::numeric), '')
                         FROM fluxo.flyway_schema_history WHERE success AND version IS NOT NULL"
}

app_id_em_execucao() { local c; c="$(dc ps -q app 2>/dev/null || true)"; [ -n "$c" ] && docker inspect -f '{{.Image}}' "$c"; }

parar_atendimento() { dc stop app proxy >/dev/null 2>&1 || true; }

esperar_saudavel() {   # esperar_saudavel SERVIÇO SEGUNDOS
    local svc="$1" limite="$2" id estado
    for _ in $(seq 1 "$limite"); do
        id="$(dc ps -q "$svc" 2>/dev/null || true)"
        if [ -n "$id" ]; then
            estado="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")"
            [ "$estado" = "healthy" ] && return 0
            [ "$estado" = "unhealthy" ] && return 1
        fi
        sleep 1
    done
    return 1
}

url_base() { echo "https://localhost:${FLUXO_PORTA_HTTPS}"; }

esperar_https() {
    for _ in $(seq 1 60); do
        if curl -fsSk --max-time 5 "$(url_base)/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep 2
    done
    return 1
}

psql_dono() { dc exec -T db psql -X -v ON_ERROR_STOP=1 -U fluxo_owner -d fluxo "$@"; }
psql_super() { dc exec -T db psql -X -v ON_ERROR_STOP=1 -U postgres -d fluxo "$@"; }

# Contagens e marcadores de integridade (superusuário: vê todas as unidades; só leitura).
SQL_MANIFESTO="
SELECT format('%s=%s', k, v) FROM (
  SELECT 1 AS o, 'migracao_versao' AS k, (SELECT version FROM fluxo.flyway_schema_history WHERE success
                                          ORDER BY installed_rank DESC LIMIT 1) AS v
  UNION ALL SELECT 2, 'unidades', count(*)::text FROM fluxo.unidade
  UNION ALL SELECT 3, 'usuarios', count(*)::text FROM fluxo.usuario
  UNION ALL SELECT 4, 'lotacoes', count(*)::text FROM fluxo.lotacao
  UNION ALL SELECT 5, 'pacientes', count(*)::text FROM fluxo.paciente
  UNION ALL SELECT 6, 'episodios', count(*)::text FROM fluxo.episodio
  UNION ALL SELECT 7, 'eventos', count(*)::text FROM fluxo.evento_episodio
  UNION ALL SELECT 8, 'pendencias', count(*)::text FROM fluxo.pendencia
  UNION ALL SELECT 9, 'regras_alerta', count(*)::text FROM fluxo.regra_alerta
  UNION ALL SELECT 10, 'passagens_plantao', count(*)::text FROM fluxo.passagem_plantao
  UNION ALL SELECT 11, 'auditoria_registros', count(*)::text FROM auditoria.registro
  UNION ALL SELECT 12, 'auditoria_ultimo_hash', (SELECT encode(ultimo_hash, 'hex') FROM auditoria.cadeia_cabeca)
  UNION ALL SELECT 13, 'auditoria_cadeia_problemas', (SELECT count(*)::text FROM auditoria.verificar_cadeia())
  UNION ALL SELECT 14, 'migracoes_aplicadas', (SELECT coalesce(string_agg(version, ',' ORDER BY version::numeric), '')
                                               FROM fluxo.flyway_schema_history WHERE success AND version IS NOT NULL)
) m ORDER BY o;"

cmd_preparar() {
    local porta=8443 rede=172.31.240 dominio=localhost endereco=127.0.0.1
    while [ $# -gt 0 ]; do
        case "$1" in
            --porta) porta="$2"; shift 2 ;;
            --rede) rede="$2"; shift 2 ;;
            --dominio) dominio="$2"; shift 2 ;;
            --endereco) endereco="$2"; shift 2 ;;
            *) erro "opção desconhecida: $1" ;;
        esac
    done
    [[ "$porta" =~ ^[0-9]{2,5}$ ]] || erro "porta inválida"
    [[ "$rede" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || erro "--rede deve ser um prefixo /24 (ex.: 172.31.240)"
    [[ "$dominio" =~ ^[A-Za-z0-9.-]+$ ]] || erro "domínio inválido"
    [[ "$endereco" =~ ^[0-9.]+$ ]] || erro "endereço de publicação inválido"
    [ -e "$ENVF" ] && erro "o projeto '$PROJETO' já está preparado ($ENVF); nada foi sobrescrito"
    local seg="$AQUI/segredos/$PROJETO"
    [ -e "$seg" ] && erro "já existem segredos em $seg; nada foi sobrescrito"
    command -v openssl >/dev/null || erro "openssl não encontrado"
    umask 077
    mkdir -p "$AMBIENTES" "$seg"
    chmod 700 "$AQUI/segredos" "$seg"
    for s in pg_superusuario db_dono db_app; do
        aleatorio > "$seg/$s"
        # Legível pelo usuário do contêiner (bind mount); a proteção é o diretório 700 no host.
        chmod 644 "$seg/$s"
    done
    cat > "$ENVF" <<EOF
# Projeto $PROJETO — configuração NÃO secreta (gerada por fluxo.sh preparar). Não versionar.
FLUXO_SEGREDOS=$seg
FLUXO_PORTA_HTTPS=$porta
FLUXO_REDE_PREFIXO=$rede
FLUXO_DOMINIO=$dominio
FLUXO_ENDERECO_PUBLICACAO=$endereco
FLUXO_IMAGEM=
FLUXO_FLYWAY_ALVO=latest
EOF
    chmod 600 "$ENVF"
    info "projeto '$PROJETO' preparado: segredos em $seg (fora do Git); configuração em $ENVF"
}

cmd_construir() {
    carregar
    local tag="" ativar=1 commit="desconhecido" ts
    while [ $# -gt 0 ]; do
        case "$1" in
            --tag) tag="$2"; shift 2 ;;
            --sem-ativar) ativar=0; shift ;;
            *) erro "opção desconhecida: $1" ;;
        esac
    done
    ts="$(date -u +%Y%m%d%H%M%S)"
    if git -C "$RAIZ" rev-parse --verify HEAD >/dev/null 2>&1; then
        commit="$(git -C "$RAIZ" rev-parse HEAD)"
        [ -z "$(git -C "$RAIZ" status --porcelain -- backend)" ] || commit="$commit-modificado"
    fi
    tag="${tag:-fluxo-saude:${commit:0:12}-$ts}"
    [[ "$tag" =~ ^[a-z0-9._/-]+:[A-Za-z0-9._-]+$ ]] || erro "tag inválida: $tag"
    info "construindo $tag (commit $commit)"
    FLUXO_IMAGEM="$tag" FLUXO_COMMIT="$commit" dc build app >&2
    [ "$ativar" = 1 ] && definir FLUXO_IMAGEM "$tag"
    info "imagem $tag = $(imagem_id "$tag")"
    echo "$tag"
}

cmd_subir() {
    carregar
    exigir_sem_parcial
    [ -n "${FLUXO_IMAGEM:-}" ] || erro "nenhuma imagem definida para '$PROJETO': rode 'fluxo.sh -p $PROJETO construir'"
    exigir_imagem "$FLUXO_IMAGEM"
    info "banco ($PROJETO)"
    dc up -d --wait --wait-timeout 180 db
    # 1) Migração EXPLÍCITA (job separado) com a imagem do projeto, ANTES de qualquer aplicação subir.
    info "migração (job separado, alvo ${FLUXO_FLYWAY_ALVO}) com $FLUXO_IMAGEM"
    if ! dc run --rm -T --no-deps migracao >&2; then
        parar_atendimento
        echo "A migração FALHOU; aplicação e proxy parados. Últimas linhas:" >&2
        dc logs --no-color --tail 40 migracao app >&2 || true
        erro "migração com falha (ver 'diagnostico')"
    fi
    # 2) Compatibilidade, também ANTES de subir: o esquema aplicado deve ser EXATAMENTE o conjunto
    #    embutido na imagem. Ex.: o Flyway ignora migrações "futuras" por padrão, então uma imagem
    #    antiga passaria pela migração sobre um esquema mais novo — aqui ela é recusada.
    local vb vi
    vb="$(versoes_banco)"; vi="$(versoes_imagem "$FLUXO_IMAGEM")"
    if [ "$vb" != "$vi" ]; then
        parar_atendimento
        erro "imagem $FLUXO_IMAGEM incompatível com o esquema: banco [$vb] × imagem [$vi]; aplicação NÃO iniciada (ver docs/operacao/homologacao.md §7)"
    fi
    # 3) Aplicação e proxy (o depends_on reexecuta a migração: sem efeito, o esquema já confere).
    info "aplicação e proxy"
    if ! dc up -d --no-build app proxy; then
        parar_atendimento
        dc logs --no-color --tail 40 migracao app >&2 || true
        erro "a aplicação não subiu; aplicação e proxy parados"
    fi
    [ "$(app_id_em_execucao)" = "$(imagem_id "$FLUXO_IMAGEM")" ] \
        || { parar_atendimento; erro "a aplicação em execução não usa a imagem $FLUXO_IMAGEM; aplicação parada"; }
    esperar_saudavel app 180 || { dc logs --no-color --tail 60 app >&2; erro "aplicação não ficou saudável"; }
    esperar_https || erro "proxy HTTPS não respondeu em $(url_base)"
    info "no ar: $(url_base)/  (imagem $FLUXO_IMAGEM; migrações ${vb##*,})"
}

cmd_parar() { carregar; dc stop; }

cmd_reiniciar() {
    carregar
    exigir_sem_parcial
    dc restart db
    dc up -d --wait --wait-timeout 180 db
    dc restart app proxy
    esperar_saudavel app 180 || erro "aplicação não ficou saudável após reinício"
    esperar_https || erro "proxy HTTPS não respondeu"
    info "reiniciado: $(url_base)/"
}

cmd_estado() {
    carregar
    dc ps -a
    printf 'saúde via HTTPS: '
    curl -sSk --max-time 5 "$(url_base)/actuator/health" || echo "(sem resposta)"
    echo
}

cmd_logs() { carregar; dc logs --no-color --tail "${LINHAS:-200}" "$@"; }

cmd_diagnostico() {
    carregar
    info "contêineres"; dc ps -a
    info "banco"
    if dc exec -T db pg_isready -U postgres -d fluxo; then echo "banco aceita conexões"; else echo "BANCO INDISPONÍVEL"; fi
    info "migração"
    local mid; mid="$(dc ps -aq migracao || true)"
    if [ -n "$mid" ]; then echo "código de saída: $(docker inspect -f '{{.State.ExitCode}}' "$mid")"; else echo "não executada"; fi
    info "aplicação"
    local aid; aid="$(dc ps -q app || true)"
    if [ -n "$aid" ]; then echo "saúde (Docker): $(docker inspect -f '{{.State.Health.Status}}' "$aid")"; else echo "APLICAÇÃO PARADA"; fi
    printf 'saúde via HTTPS: '; curl -sSk --max-time 5 "$(url_base)/actuator/health" || echo "(sem resposta)"; echo
    info "imagem e esquema"
    echo "imagem configurada: ${FLUXO_IMAGEM:-(nenhuma)}"
    if [ -n "${FLUXO_IMAGEM:-}" ] && imagem_id "$FLUXO_IMAGEM" >/dev/null; then
        echo "migrações da imagem: $(versoes_imagem "$FLUXO_IMAGEM")"
    fi
    echo "migrações no banco:  $(versoes_banco 2>/dev/null || echo '(banco indisponível)')"
    info "últimas linhas (aplicação e migração; sem segredos)"
    dc logs --no-color --tail 20 migracao app 2>/dev/null || true
}

cmd_certificado() {
    carregar
    local destino="${1:?informe o arquivo de destino}"
    dc exec -T proxy cat /data/caddy/pki/authorities/local/root.crt > "$destino"
    info "CA de homologação do proxy salva em $destino (instale só em máquinas de teste)"
}

cmd_primeiro_acesso() {
    carregar
    local codigo="" nome="" tipo=UPA encerra=true login="" admin_nome="" senha_em=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --unidade-codigo) codigo="$2"; shift 2 ;;
            --unidade-nome) nome="$2"; shift 2 ;;
            --unidade-tipo) tipo="$2"; shift 2 ;;
            --internacao-encerra) encerra="$2"; shift 2 ;;
            --admin-login) login="$2"; shift 2 ;;
            --admin-nome) admin_nome="$2"; shift 2 ;;
            --senha-em) senha_em="$2"; shift 2 ;;
            *) erro "opção desconhecida: $1" ;;
        esac
    done
    [ -n "$codigo" ] && [ -n "$nome" ] && [ -n "$login" ] && [ -n "$admin_nome" ] \
        || erro "uso: primeiro-acesso --unidade-codigo C --unidade-nome N --admin-login L --admin-nome M [--unidade-tipo UPA] [--internacao-encerra true|false] [--senha-em ARQ]"
    [[ "$encerra" =~ ^(true|false)$ ]] || erro "--internacao-encerra deve ser true ou false"
    exigir_imagem "${FLUXO_IMAGEM:-}"
    local existentes
    existentes="$(psql_super -At -c "SELECT count(*) FROM fluxo.lotacao WHERE papel = 'ADMINISTRADOR'")"
    [ "$existentes" = "0" ] || erro "já existe administrador; use a tela 'Usuários' (nada foi alterado)"
    # Senha provisória aleatória (só para o 1º acesso; a troca é obrigatória no login).
    local senha=""
    while [ "$(printf '%s' "$senha" | fold -w1 | sort -u | wc -l)" -lt 10 ]; do
        senha="$(openssl rand -base64 30 | tr -dc 'A-Za-z0-9' | cut -c1-20)"
    done
    local hash
    hash="$(printf '%s\n' "$senha" | dc run --rm -T --no-deps --entrypoint java app \
        -cp /app/app.jar -Dloader.main=br.fluxosaude.identidade.infra.GerarHashSenha \
        org.springframework.boot.loader.launch.PropertiesLauncher 2>/dev/null | tail -1)"
    [[ "$hash" == "{argon2@"* ]] || erro "não foi possível gerar o hash da senha"
    psql_dono -q -v unidade_codigo="$codigo" -v unidade_nome="$nome" -v unidade_tipo="$tipo" \
        -v internacao_encerra="$encerra" -v admin_login="$login" -v admin_nome="$admin_nome" \
        -v admin_hash="$hash" -f - < "$RAIZ/infra/db/admin-inicial.sql" >/dev/null
    if [ -n "$senha_em" ]; then
        ( umask 077; printf '%s\n' "$senha" > "$senha_em" )
        info "unidade $codigo e administrador '$login' criados; senha provisória gravada em $senha_em (600)"
    else
        info "unidade $codigo e administrador '$login' criados."
        echo "Senha PROVISÓRIA (exibida uma única vez; troca obrigatória no 1º acesso): $senha"
    fi
}

cmd_demo() {
    carregar
    [ "${1:-}" = "--confirmo-dados-ficticios" ] \
        || erro "os dados de demonstração criam CONTAS COM SENHAS PÚBLICAS (e2e/dados/fixtures.json). Use apenas em ambiente isolado: demo --confirmo-dados-ficticios"
    info "gerando e carregando dados FICTÍCIOS (unidades E2E_NORTE/E2E_SUL, usuários *.e2e)"
    docker run --rm -v "$RAIZ/e2e/dados:/dados:ro" python:3.12-slim sh -c \
        'pip install -q --disable-pip-version-check --root-user-action=ignore argon2-cffi==25.1.0 >&2 && python /dados/gerar-seed.py' \
        | psql_dono -q >/dev/null
    info "dados fictícios carregados"
}

cmd_backup() {
    carregar
    local destino="$AQUI/backups" rotulo=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --destino) destino="$2"; shift 2 ;;
            --rotulo) rotulo="$2"; shift 2; [[ "$rotulo" =~ ^[a-z0-9-]+$ ]] || erro "rótulo inválido" ;;
            *) erro "opção desconhecida: $1" ;;
        esac
    done
    umask 077
    mkdir -p "$destino"; chmod 700 "$destino"
    local ts base
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    base="$destino/fluxo-$PROJETO-$ts${rotulo:+-$rotulo}"
    # Identidade IMUTÁVEL da imagem que atende este banco (ID do conteúdo, não só a tag), o commit
    # e as migrações que ela embute: a restauração exige uma imagem compatível com o backup.
    local img_id img_origem="em_execucao"
    img_id="$(app_id_em_execucao || true)"
    if [ -z "$img_id" ]; then
        img_origem="configurada"
        img_id="$(imagem_id "${FLUXO_IMAGEM:-}" || true)"
    fi
    [ -n "$img_id" ] || erro "não há imagem identificável para registrar no backup (FLUXO_IMAGEM vazia/ausente)"
    info "backup lógico (pg_dump -Fc) — dados de sessão HTTP EXCLUÍDOS (são credenciais)"
    # Dump e manifesto do MESMO instantâneo: uma transação REPEATABLE READ exporta o snapshot, o
    # pg_dump o reutiliza e as contagens são lidas nele. Gravações concorrentes não geram
    # divergência entre o arquivo e o manifesto.
    coproc PG { dc exec -T db psql -X -q -At -v ON_ERROR_STOP=1 -U postgres -d fluxo 2>&1; }
    printf '%s\n' "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;" "SELECT pg_export_snapshot();" >&"${PG[1]}"
    local snap=""
    read -r -t 60 snap <&"${PG[0]}" || true
    [[ "$snap" =~ ^[0-9A-F-]+$ ]] || erro "não foi possível exportar o snapshot do banco: $snap"
    dc exec -T db pg_dump -U postgres -d fluxo -Fc --snapshot="$snap" --exclude-table-data='sessao.*' > "$base.dump"
    {
        echo "projeto=$PROJETO"
        echo "gerado_em=$ts"
        echo "imagem_id=$img_id"
        echo "imagem_origem=$img_origem"
        echo "imagem_ref=${FLUXO_IMAGEM:-}"
        echo "imagem_commit=$(imagem_commit "$img_id")"
        echo "imagem_migracoes=$(versoes_imagem "$img_id")"
        echo "estado=$([ -f "$MARCA" ] && echo PARCIAL || echo NORMAL)"
        printf '%s\n' "$SQL_MANIFESTO" "SELECT 'FIM_MANIFESTO';" >&"${PG[1]}"
        local linha
        while read -r -t 300 linha <&"${PG[0]}"; do
            [ "$linha" = "FIM_MANIFESTO" ] && break
            echo "$linha"
        done
    } > "$base.manifesto"
    printf '%s\n' "COMMIT;" '\q' >&"${PG[1]}"
    wait "$PG_PID" || true
    [ -s "$base.dump" ] || erro "backup vazio"
    dc exec -T db pg_restore -l < "$base.dump" > /dev/null || erro "arquivo de backup ilegível"
    grep -q '^migracao_versao=' "$base.manifesto" || erro "manifesto incompleto: $base.manifesto"
    [ "$(sed -n 's/^imagem_migracoes=//p' "$base.manifesto")" = "$(sed -n 's/^migracoes_aplicadas=//p' "$base.manifesto")" ] \
        || echo "ATENÇÃO: o esquema do banco difere das migrações da imagem registrada (ver $base.manifesto)" >&2
    grep -q '^auditoria_cadeia_problemas=0$' "$base.manifesto" \
        || echo "ATENÇÃO: a cadeia de auditoria do banco de ORIGEM tem problemas (ver $base.manifesto)" >&2
    ( cd "$destino" && sha256sum "$(basename "$base.dump")" "$(basename "$base.manifesto")" > "$(basename "$base").sha256" )
    chmod 600 "$base".*
    info "backup: $base.dump (+ .manifesto, .sha256)"
    echo "$base.dump"
}

cmd_restaurar() {
    carregar
    local arquivo="${1:?informe o arquivo .dump}"; shift
    local destino="" porta="" rede="" imagem=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --projeto-destino) destino="$2"; shift 2 ;;
            --porta) porta="$2"; shift 2 ;;
            --rede) rede="$2"; shift 2 ;;
            --imagem) imagem="$2"; shift 2 ;;
            *) erro "opção desconhecida: $1" ;;
        esac
    done
    destino="${destino:-$PROJETO-recuperacao-$(date -u +%Y%m%d%H%M%S)}"
    porta="${porta:-$((FLUXO_PORTA_HTTPS + 1000))}"
    rede="${rede:-172.31.241}"
    [ "$destino" != "$PROJETO" ] || erro "o destino deve ser um projeto NOVO (nunca o original)"
    [ -f "$arquivo" ] || erro "arquivo não encontrado: $arquivo"
    local dir base man
    dir="$(cd "$(dirname "$arquivo")" && pwd)"
    base="$(basename "$arquivo" .dump)"
    man="$dir/$base.manifesto"
    [ -f "$dir/$base.sha256" ] || erro "sem $base.sha256: integridade do backup não verificável"
    [ -f "$man" ] || erro "sem $base.manifesto: versão do esquema e imagem do backup desconhecidas"
    ( cd "$dir" && sha256sum -c --quiet "$base.sha256" ) || erro "SHA-256 não confere: backup alterado ou corrompido"
    if [ -e "$AMBIENTES/$destino.env" ] || docker volume inspect "${destino}_dados" >/dev/null 2>&1; then
        erro "o projeto/volume de destino '$destino' já existe: nada será sobrescrito"
    fi

    # 1) Imagem de recuperação: a registrada no backup (ID imutável) ou --imagem explícita. Ela é
    #    verificada ANTES de qualquer processo de migração: precisa existir e embutir EXATAMENTE as
    #    migrações aplicadas no backup. Nada é construído; a tag "local"/atual não é usada por omissão.
    local mig_backup img_backup commit_backup
    mig_backup="$(sed -n 's/^migracoes_aplicadas=//p' "$man")"
    img_backup="$(sed -n 's/^imagem_id=//p' "$man")"
    commit_backup="$(sed -n 's/^imagem_commit=//p' "$man")"
    [ -n "$mig_backup" ] || erro "manifesto sem 'migracoes_aplicadas': backup anterior a este procedimento"
    imagem="${imagem:-$img_backup}"
    [ -n "$imagem" ] || erro "manifesto sem 'imagem_id' e nenhuma --imagem informada"
    imagem_id "$imagem" >/dev/null || erro "imagem de recuperação '$imagem' (commit ${commit_backup:-?}) não está neste host.
  Reconstrua-a a partir do MESMO commit e informe-a explicitamente, por exemplo:
    git checkout ${commit_backup%-modificado} && bash $0 -p $PROJETO construir --tag fluxo-saude:recuperacao --sem-ativar
    bash $0 -p $PROJETO restaurar $arquivo --imagem fluxo-saude:recuperacao
  (ou carregue a imagem preservada com 'docker load')."
    local id_img mig_img
    id_img="$(imagem_id "$imagem")"
    mig_img="$(versoes_imagem "$id_img")"
    [ "$mig_img" = "$mig_backup" ] || erro "imagem '$imagem' INCOMPATÍVEL com o backup: embute migrações [$mig_img], o backup tem [$mig_backup]. Use a imagem do commit ${commit_backup:-registrado} (--imagem)."
    [ "$id_img" = "$img_backup" ] || echo "AVISO: imagem diferente da registrada no backup ($img_backup), mas com as mesmas migrações; seguindo com $id_img" >&2

    info "restaurando em projeto SEPARADO '$destino' (porta $porta, rede $rede.0/24) com a imagem $id_img; '$PROJETO' não é tocado"
    "$AQUI/fluxo.sh" -p "$destino" preparar --porta "$porta" --rede "$rede"
    local origem="$PROJETO" tag_rec="fluxo-saude:recuperacao-$destino"
    docker image tag "$id_img" "$tag_rec"
    # Daqui em diante tudo se refere ao projeto de DESTINO (inclusive a marca de estado parcial: a do
    # original continua lá, e o original continua parado).
    PROJETO="$destino"; ENVF="$AMBIENTES/$destino.env"
    MARCA="$AMBIENTES/$destino.parcial"; INVENT="$AMBIENTES/$destino.parcial.inventario"; carregar
    definir FLUXO_IMAGEM "$tag_rec"
    definir FLUXO_FLYWAY_ALVO current          # recuperação: Flyway só valida; nunca migra para a frente

    # 2) Restauração do banco (separada de qualquer atualização).
    dc up -d --wait --wait-timeout 180 db
    dc exec -T db pg_restore -U postgres -d fluxo --exit-on-error --single-transaction < "$arquivo"
    local relatorio="$AQUI/relatorios/restauracao-$destino.txt" antes
    mkdir -p "$AQUI/relatorios"
    antes="$(psql_super -At -c "$SQL_MANIFESTO")"
    {
        echo "restauracao_de=$(basename "$arquivo")"
        echo "projeto_origem=$origem"
        echo "projeto_destino=$destino"
        echo "restaurado_em=$(date -u +%Y%m%dT%H%M%SZ)"
        echo "imagem_backup=$img_backup"
        echo "imagem_recuperacao=$id_img"
        echo "imagem_migracoes=$mig_img"
        echo "$antes"
    } > "$relatorio"
    grep -q '^auditoria_cadeia_problemas=0$' "$relatorio" || erro "cadeia de auditoria INVÁLIDA após a restauração (ver $relatorio)"
    if diff <(grep -v -e '^projeto=' -e '^gerado_em=' -e '^imagem_' -e '^estado=' "$man") <(echo "$antes"); then
        echo "conferencia_manifesto=OK" >> "$relatorio"
    else
        echo "conferencia_manifesto=DIVERGENTE" >> "$relatorio"
        erro "contagens/hash da auditoria/migrações diferem do manifesto do backup (ver $relatorio)"
    fi
    # Sessões: o backup não as contém; ainda assim, nenhuma sessão restaurada pode valer.
    local removidas
    removidas="$(psql_super -At -c "WITH d AS (DELETE FROM sessao.spring_session RETURNING 1) SELECT count(*) FROM d")"
    echo "sessoes_invalidadas=$removidas" >> "$relatorio"

    # 3) Aplicação recuperada: migração só VALIDA (alvo current) e subir confere imagem × esquema.
    info "subindo a aplicação recuperada (Flyway alvo current: valida, não migra)"
    cmd_subir
    # 4) Estado FINAL, depois da subida: mesmo esquema, mesmos dados, imagem certa em execução.
    local depois
    depois="$(psql_super -At -c "$SQL_MANIFESTO")"
    [ "$depois" = "$antes" ] || { echo "estado_final=DIVERGENTE" >> "$relatorio"; erro "estado do banco mudou ao subir a aplicação (ver $relatorio)"; }
    [ "$(app_id_em_execucao)" = "$id_img" ] || erro "a aplicação recuperada não usa a imagem $id_img"
    {
        echo "estado_final=OK"
        echo "imagem_em_execucao=$(app_id_em_execucao)"
        echo "aplicacao_recuperada=$(url_base)"
    } >> "$relatorio"
    chmod 600 "$relatorio"
    info "restauração conferida: imagem compatível, auditoria íntegra, manifesto e estado final iguais, sessões invalidadas"
    info "relatório: $relatorio — ambiente recuperado em $(url_base)/ (isolado do original)"
}

cmd_atualizar() {
    carregar
    local nova="" continuar=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --imagem) nova="$2"; shift 2 ;;
            --continuar-parcial) continuar=1; shift ;;
            *) erro "opção desconhecida: $1" ;;
        esac
    done
    if [ "$continuar" = 1 ]; then
        [ -f "$MARCA" ] || erro "não há atualização parcial pendente em '$PROJETO'; use 'atualizar' sem --continuar-parcial"
        [ -n "$nova" ] || erro "informe a imagem corrigida: atualizar --imagem <imagem-corrigida> --continuar-parcial"
        continuar_parcial "$nova"
        return
    fi
    exigir_sem_parcial
    local anterior="${FLUXO_IMAGEM:?projeto sem imagem}" ts relatorio
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    relatorio="$AQUI/relatorios/atualizacao-$PROJETO-$ts.txt"
    mkdir -p "$AQUI/relatorios"
    exigir_imagem "$anterior"
    info "1/5 backup obrigatório (com a imagem atual: $anterior)"
    local bkp; bkp="$(cmd_backup | tail -1)"
    info "2/5 nova imagem"
    if [ -n "$nova" ]; then exigir_imagem "$nova"; else nova="$(cmd_construir --sem-ativar | tail -1)"; fi
    local antes vi_ant vi_nova
    antes="$(versoes_banco)"; vi_ant="$(versoes_imagem "$anterior")"; vi_nova="$(versoes_imagem "$nova")"
    # Atualização NORMAL só parte de um estado coerente (esquema == imagem atual). Um avanço parcial
    # registrado é recusado antes (exigir_sem_parcial) com o comando de correção para a frente.
    [ "$antes" = "$vi_ant" ] || erro "estado inicial inconsistente: banco [$antes] × imagem atual [$vi_ant]; nada foi alterado.
  Sem marca de avanço parcial registrada ($MARCA), não há como conferir o histórico aplicado:
  análise manual ou restauração isolada de um backup (docs/operacao/homologacao.md §6–§7)"
    {
        echo "projeto=$PROJETO"; echo "inicio=$ts"; echo "backup_previo=$(basename "$bkp")"
        echo "imagem_anterior=$anterior ($(imagem_id "$anterior"))"; echo "imagem_nova=$nova ($(imagem_id "$nova"))"
        echo "migracoes_antes=$antes"; echo "migracoes_imagem_nova=$vi_nova"
    } > "$relatorio"
    # Nenhuma aplicação atende enquanto o esquema muda: a antiga pode não ser compatível com o
    # esquema parcialmente migrado, e a nova ainda não foi validada.
    info "3/5 manutenção: aplicação e proxy parados durante a migração"
    parar_atendimento
    info "4/5 migração separada com $nova"
    local rc=0
    FLUXO_IMAGEM="$nova" FLUXO_FLYWAY_ALVO=latest dc run --rm -T --no-deps migracao >&2 || rc=$?
    local depois; depois="$(versoes_banco)"
    { echo "migracao_codigo=$rc"; echo "migracoes_depois=$depois"; } >> "$relatorio"
    if [ "$rc" = 0 ] && [ "$depois" = "$vi_nova" ]; then
        info "5/5 aplicação nova"
        definir FLUXO_IMAGEM "$nova"
        cmd_subir
        echo "resultado=SUCESSO" >> "$relatorio"
        info "atualização concluída: $nova (relatório $relatorio)"
        return 0
    fi
    if [ "$depois" = "$antes" ]; then
        # Falha SEM avanço de esquema: o banco tem exatamente as migrações da imagem anterior, então
        # a compatibilidade da aplicação anterior está comprovada — ela volta a atender.
        echo "resultado=FALHA_SEM_AVANCO" >> "$relatorio"
        echo "Atualização FALHOU sem alterar o esquema (banco [$depois] = imagem anterior). Restabelecendo $anterior." >&2
        cmd_subir
        echo "aplicacao=REVERTIDA_PARA_ANTERIOR" >> "$relatorio"
        chmod 600 "$relatorio"
        erro "atualização não aplicada; aplicação anterior restabelecida (relatório $relatorio)"
    fi
    # Avanço PARCIAL: parte das migrações confirmou. Nem a imagem anterior nem a nova são
    # comprovadamente compatíveis: a aplicação fica PARADA. Não há rollback automático.
    {
        echo "resultado=FALHA_COM_AVANCO_PARCIAL"
        echo "aplicacao=PARADA"
    } >> "$relatorio"
    chmod 600 "$relatorio"
    registrar_parcial "$anterior" "$nova" "$antes" "$depois" "$bkp" "$relatorio"
    cat >&2 <<EOF
ATUALIZAÇÃO FALHOU COM AVANÇO PARCIAL DO ESQUEMA.
  antes:  [$antes]
  depois: [$depois]   (imagem nova embute [$vi_nova])
A aplicação e o proxy ficam PARADOS: nenhuma imagem disponível é comprovadamente compatível com este
esquema. NÃO volte para a imagem anterior: o Flyway a aceitaria (ignora migrações "futuras"),
mas ela não conhece o esquema novo; por isso 'subir' a recusa pela verificação de compatibilidade. Escolha:
  a) correção para a frente: imagem com as migrações JÁ APLICADAS idênticas e as demais corrigidas:
       bash $0 -p $PROJETO atualizar --imagem <imagem-corrigida> --continuar-parcial
  b) restauração ISOLADA do backup feito antes da atualização, com a imagem registrada nele:
       bash $0 -p $PROJETO restaurar $bkp
     e, depois de conferir, promover o projeto recuperado (docs/operacao/homologacao.md §7).
O projeto '$PROJETO' e o volume dele são preservados como estão. Relatório: $relatorio
EOF
    exit 3
}

# Guarda o estado parcial: marca (versões, imagens, backup prévio) e inventário das migrações já
# aplicadas, com o arquivo e o SHA-256 de quem as aplicou (imagem anterior para as antigas; imagem que
# falhou para as novas). É contra ESTE inventário que a imagem corretiva é conferida.
registrar_parcial() {   # anterior falhou antes depois backup relatorio
    local anterior="$1" falhou="$2" antes="$3" depois="$4" bkp="$5" rel="$6" novas
    novas="$(comm -13 <(tr , '\n' <<<"$antes" | sort) <(tr , '\n' <<<"$depois" | sort) | paste -sd, -)"
    ( umask 077
      { inventario_imagem "$anterior" | filtrar_versoes "$antes"
        inventario_imagem "$falhou" | filtrar_versoes "$novas"; } > "$INVENT"
      {
        echo "parcial_desde=$(date -u +%Y%m%dT%H%M%SZ)"
        echo "imagem_anterior=$(imagem_id "$anterior")"
        echo "imagem_que_falhou=$(imagem_id "$falhou")"
        echo "migracoes_antes=$antes"
        echo "migracoes_depois=$depois"
        echo "backup_previo=$bkp"
        echo "relatorio=$rel"
      } > "$MARCA" )
    [ "$(cut -d' ' -f3 "$INVENT" | sed -nE 's/^V([0-9]+)__.*/\1/p' | sort -n | paste -sd, -)" = "$depois" ] \
        || erro "inventário do estado parcial incompleto ($INVENT); não prossiga sem análise manual"
}

# Correção para a frente a partir de um avanço parcial (atualizar --imagem C --continuar-parcial).
continuar_parcial() {
    local corr="$1" ts relatorio bkp_parcial
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    relatorio="$AQUI/relatorios/atualizacao-$PROJETO-$ts.txt"
    mkdir -p "$AQUI/relatorios"
    exigir_imagem "$corr"
    # 1) Nada atende enquanto o banco está parcialmente migrado.
    parar_atendimento
    local antes vi_corr
    antes="$(versoes_banco)"; vi_corr="$(versoes_imagem "$corr")"
    [ "$antes" = "$(sed -n 's/^migracoes_depois=//p' "$MARCA")" ] \
        || erro "o esquema mudou desde a falha registrada ([$antes]); análise manual necessária — nada foi alterado"
    {
        echo "projeto=$PROJETO"; echo "inicio=$ts"; echo "modo=CONTINUACAO_PARCIAL"
        echo "backup_previo_original=$(basename "$(sed -n 's/^backup_previo=//p' "$MARCA")")"
        echo "imagem_corretiva=$corr ($(imagem_id "$corr"))"
        echo "migracoes_antes=$antes"; echo "migracoes_imagem_corretiva=$vi_corr"
    } > "$relatorio"
    # 2) Histórico e somas: cada migração já aplicada precisa existir na imagem corretiva com o MESMO
    #    arquivo e o MESMO SHA-256 de quando foi aplicada. (O Flyway confere de novo o checksum dele.)
    local divergencias
    divergencias="$(comm -23 <(sort "$INVENT") <(inventario_imagem "$corr" | filtrar_versoes "$antes" | sort))"
    if [ -n "$divergencias" ]; then
        { echo "resultado=RECUSADA_HISTORICO_DIVERGENTE"; echo "aplicacao=PARADA"; } >> "$relatorio"
        chmod 600 "$relatorio"
        erro "a imagem corretiva NÃO contém, idênticas, as migrações já aplicadas:
$divergencias
Nada foi alterado; aplicação continua PARADA. Relatório: $relatorio"
    fi
    echo "historico_conferido=OK" >> "$relatorio"
    # 3) Backup do ESTADO PARCIAL, separado (o backup anterior à atualização é preservado).
    info "backup do estado parcial (o backup anterior à atualização defeituosa é preservado)"
    bkp_parcial="$(cmd_backup --rotulo parcial | tail -1)"
    echo "backup_estado_parcial=$(basename "$bkp_parcial")" >> "$relatorio"
    # 4) Só as migrações pendentes, com a imagem corretiva.
    info "migração separada com $corr (aplica só as pendentes)"
    local rc=0 depois
    FLUXO_IMAGEM="$corr" FLUXO_FLYWAY_ALVO=latest dc run --rm -T --no-deps migracao >&2 || rc=$?
    depois="$(versoes_banco)"
    { echo "migracao_codigo=$rc"; echo "migracoes_depois=$depois"; } >> "$relatorio"
    if [ "$rc" = 0 ] && [ "$depois" = "$vi_corr" ]; then
        # 5) Esquema completo e idêntico ao da imagem corretiva: ativa-a e só então volta a atender
        #    (subir confere esquema × imagem, imagem em execução e saúde).
        definir FLUXO_IMAGEM "$corr"
        mv "$MARCA" "$AQUI/relatorios/parcial-resolvido-$PROJETO-$ts.txt"
        rm -f "$INVENT"
        echo "esquema=COMPLETO_E_IGUAL_AO_DA_IMAGEM_CORRETIVA" >> "$relatorio"
        cmd_subir
        echo "imagem_em_execucao=$(app_id_em_execucao)" >> "$relatorio"
        echo "resultado=SUCESSO" >> "$relatorio"
        chmod 600 "$relatorio"
        info "correção para a frente concluída: $corr (relatório $relatorio)"
        return 0
    fi
    if [ "$depois" = "$antes" ]; then
        # Sem novo avanço: o ponto de partida JÁ era parcial, então não há imagem comprovadamente
        # compatível para reativar (a anterior não conhece o esquema). Continua parado.
        { echo "resultado=FALHA_SEM_NOVO_AVANCO"; echo "aplicacao=PARADA"; } >> "$relatorio"
        chmod 600 "$relatorio"
        echo "ERRO: correção falhou sem novo avanço; o banco continua parcial e a aplicação PARADA (nenhuma imagem é reativada). Relatório: $relatorio" >&2
        exit 4
    fi
    # Novo avanço parcial: atualiza a marca/inventário com as migrações aplicadas pela imagem corretiva.
    local novas
    novas="$(comm -13 <(tr , '\n' <<<"$antes" | sort) <(tr , '\n' <<<"$depois" | sort) | paste -sd, -)"
    ( umask 077; inventario_imagem "$corr" | filtrar_versoes "$novas" >> "$INVENT" )
    sed -i "s|^migracoes_depois=.*|migracoes_depois=$depois|" "$MARCA"
    { echo "resultado=FALHA_COM_NOVO_AVANCO_PARCIAL"; echo "aplicacao=PARADA"; } >> "$relatorio"
    chmod 600 "$relatorio"
    echo "Correção avançou parcialmente ([$antes] -> [$depois]); aplicação PARADA. Relatório: $relatorio" >&2
    exit 3
}

cmd_remover() {
    carregar
    [ "${1:-}" = "--confirmo-apagar-dados" ] || erro "isto APAGA o banco do projeto '$PROJETO'. Confirme com: remover --confirmo-apagar-dados"
    dc down -v --remove-orphans
    rm -rf "${FLUXO_SEGREDOS:?}" "$ENVF" "$MARCA" "$INVENT"   # o volume com o esquema parcial também foi apagado
    info "projeto '$PROJETO' removido (contêineres, volumes, segredos e configuração)"
}

case "$COMANDO" in
    preparar) cmd_preparar "$@" ;;
    construir) cmd_construir "$@" ;;
    subir) cmd_subir ;;
    parar) cmd_parar ;;
    reiniciar) cmd_reiniciar ;;
    estado) cmd_estado ;;
    logs) cmd_logs "$@" ;;
    diagnostico) cmd_diagnostico ;;
    certificado) cmd_certificado "$@" ;;
    primeiro-acesso) cmd_primeiro_acesso "$@" ;;
    demo) cmd_demo "$@" ;;
    backup) cmd_backup "$@" ;;
    restaurar) cmd_restaurar "$@" ;;
    atualizar) cmd_atualizar "$@" ;;
    remover) cmd_remover "$@" ;;
    ajuda|-h|--help) sed -n '2,30p' "$0" ;;
    *) erro "comando desconhecido: $COMANDO (use 'ajuda')" ;;
esac
