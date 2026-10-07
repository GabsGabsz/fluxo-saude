#!/usr/bin/env bash
# =============================================================================
# Operação do ambiente de HOMOLOGAÇÃO do Fluxo Saúde (dados fictícios; não é produção).
# Requer: Docker Engine/Desktop com Compose v2, bash, openssl, sha256sum, curl.
# Linux ou Windows com Docker Desktop + WSL2 (executar DENTRO da distribuição WSL).
#
#   fluxo.sh [-p PROJETO] COMANDO [opções]       (projeto padrão: fluxo-homologacao)
#
#   preparar          gera segredos e configuração do projeto (uma vez; nunca sobrescreve)
#   subir             constrói (se preciso), sobe o banco, roda a MIGRAÇÃO e só então a aplicação
#   parar | reiniciar | estado | logs [serviço] | diagnostico | certificado ARQ
#   primeiro-acesso   1ª unidade + 1º administrador (senha provisória; troca obrigatória)
#   demo --confirmo-dados-ficticios   carrega os dados FICTÍCIOS do E2E (contas com senhas públicas!)
#   backup [--destino DIR]            pg_dump + manifesto + SHA-256 (sem dados de sessão)
#   restaurar ARQ.dump [--projeto-destino NOME] [--porta N] [--rede A.B.C]
#                     restaura num projeto/volume NOVO e separado; confere cadeia de auditoria e
#                     contagens; invalida sessões; sobe a aplicação recuperada
#   atualizar         backup obrigatório + nova imagem + migração separada + aplicação
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

carregar() {
    [ -f "$ENVF" ] || erro "projeto '$PROJETO' não preparado: rode '$0 -p $PROJETO preparar'"
    set -a
    # shellcheck source=/dev/null
    . "$ENVF"
    set +a
}
dc() { docker compose -f "$AQUI/compose.yaml" -p "$PROJETO" --env-file "$ENVF" "$@"; }

aleatorio() { openssl rand -hex 32; }

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
FLUXO_VERSAO=local
EOF
    chmod 600 "$ENVF"
    info "projeto '$PROJETO' preparado: segredos em $seg (fora do Git); configuração em $ENVF"
}

cmd_subir() {
    carregar
    info "banco ($PROJETO)"
    dc up -d --wait --wait-timeout 180 db
    info "migração (job separado) e aplicação"
    # Sem --build: a imagem é construída só se não existir (atualizar reconstrói de propósito);
    # assim uma reversão para fluxo-saude:anterior-* não é sobrescrita.
    if ! dc up -d app proxy; then
        echo "A aplicação NÃO foi iniciada. Últimas linhas da migração:" >&2
        dc logs --no-color --tail 40 migracao >&2 || true
        exit 1
    fi
    local cod
    cod="$(docker inspect -f '{{.State.ExitCode}}' "$(dc ps -aq migracao)")"
    [ "$cod" = "0" ] || erro "migração terminou com código $cod; aplicação não deve ser usada"
    esperar_saudavel app 180 || { dc logs --no-color --tail 60 app >&2; erro "aplicação não ficou saudável"; }
    esperar_https || erro "proxy HTTPS não respondeu em $(url_base)"
    info "no ar: $(url_base)/  (migração versão $(psql_super -At -c "SELECT version FROM fluxo.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"))"
}

cmd_parar() { carregar; dc stop; }

cmd_reiniciar() {
    carregar
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
    local destino="$AQUI/backups"
    while [ $# -gt 0 ]; do
        case "$1" in
            --destino) destino="$2"; shift 2 ;;
            *) erro "opção desconhecida: $1" ;;
        esac
    done
    umask 077
    mkdir -p "$destino"; chmod 700 "$destino"
    local ts base
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    base="$destino/fluxo-$PROJETO-$ts"
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
    local destino="" porta="" rede=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --projeto-destino) destino="$2"; shift 2 ;;
            --porta) porta="$2"; shift 2 ;;
            --rede) rede="$2"; shift 2 ;;
            *) erro "opção desconhecida: $1" ;;
        esac
    done
    destino="${destino:-$PROJETO-recuperacao-$(date -u +%Y%m%d%H%M%S)}"
    porta="${porta:-$((FLUXO_PORTA_HTTPS + 1000))}"
    rede="${rede:-172.31.241}"
    [ "$destino" != "$PROJETO" ] || erro "o destino deve ser um projeto NOVO (nunca o original)"
    [ -f "$arquivo" ] || erro "arquivo não encontrado: $arquivo"
    local dir base
    dir="$(cd "$(dirname "$arquivo")" && pwd)"
    base="$(basename "$arquivo" .dump)"
    [ -f "$dir/$base.sha256" ] || erro "sem $base.sha256: integridade do backup não verificável"
    ( cd "$dir" && sha256sum -c --quiet "$base.sha256" ) || erro "SHA-256 não confere: backup alterado ou corrompido"
    if docker volume inspect "${destino}_dados" >/dev/null 2>&1; then
        erro "já existe o volume ${destino}_dados: nada será sobrescrito"
    fi
    info "restaurando em projeto SEPARADO '$destino' (porta $porta, rede $rede.0/24); '$PROJETO' não é tocado"
    "$AQUI/fluxo.sh" -p "$destino" preparar --porta "$porta" --rede "$rede"
    local origem_envf="$ENVF"
    PROJETO="$destino"; ENVF="$AMBIENTES/$destino.env"; carregar
    dc up -d --wait --wait-timeout 180 db
    dc exec -T db pg_restore -U postgres -d fluxo --exit-on-error --single-transaction < "$arquivo"
    local relatorio="$AQUI/relatorios/restauracao-$destino.txt"
    mkdir -p "$AQUI/relatorios"
    {
        echo "restauracao_de=$arquivo"
        echo "projeto_origem=$(basename "$origem_envf" .env)"
        echo "projeto_destino=$destino"
        echo "restaurado_em=$(date -u +%Y%m%dT%H%M%SZ)"
        psql_super -At -c "$SQL_MANIFESTO"
    } > "$relatorio"
    grep -q '^auditoria_cadeia_problemas=0$' "$relatorio" || erro "cadeia de auditoria INVÁLIDA após a restauração (ver $relatorio)"
    if [ -f "$dir/$base.manifesto" ]; then
        if diff <(grep -v -e '^projeto=' -e '^gerado_em=' "$dir/$base.manifesto") \
                <(grep -v -e '^restauracao_de=' -e '^projeto_' -e '^restaurado_em=' "$relatorio"); then
            echo "conferencia_manifesto=OK" >> "$relatorio"
        else
            echo "conferencia_manifesto=DIVERGENTE" >> "$relatorio"
            erro "contagens/hash da auditoria diferem do manifesto do backup (ver $relatorio)"
        fi
    fi
    # Sessões: o backup não as contém; ainda assim, nenhuma sessão restaurada pode valer.
    local removidas
    removidas="$(psql_super -At -c "WITH d AS (DELETE FROM sessao.spring_session RETURNING 1) SELECT count(*) FROM d")"
    echo "sessoes_invalidadas=$removidas" >> "$relatorio"
    info "subindo a aplicação recuperada (migração no modo de validação)"
    cmd_subir
    echo "aplicacao_recuperada=$(url_base)" >> "$relatorio"
    chmod 600 "$relatorio"
    info "restauração conferida: cadeia de auditoria íntegra, contagens = manifesto, sessões invalidadas"
    info "relatório: $relatorio — ambiente recuperado em $(url_base)/ (isolado do original)"
}

cmd_atualizar() {
    carregar
    info "1/3 backup obrigatório antes da atualização"
    cmd_backup >/dev/null
    local ts; ts="$(date -u +%Y%m%d%H%M%S)"
    if docker image inspect "fluxo-saude:${FLUXO_VERSAO}" >/dev/null 2>&1; then
        docker image tag "fluxo-saude:${FLUXO_VERSAO}" "fluxo-saude:anterior-$ts"
        info "imagem atual preservada como fluxo-saude:anterior-$ts (reversão da APLICAÇÃO)"
    fi
    info "2/3 nova imagem"
    dc build app
    info "3/3 migração separada e aplicação"
    cmd_subir || {
        echo "Atualização FALHOU. Ver docs/operacao/homologacao.md#recuperação-após-atualização-malsucedida" >&2
        exit 1
    }
}

cmd_remover() {
    carregar
    [ "${1:-}" = "--confirmo-apagar-dados" ] || erro "isto APAGA o banco do projeto '$PROJETO'. Confirme com: remover --confirmo-apagar-dados"
    dc down -v --remove-orphans
    rm -rf "${FLUXO_SEGREDOS:?}" "$ENVF"
    info "projeto '$PROJETO' removido (contêineres, volumes, segredos e configuração)"
}

case "$COMANDO" in
    preparar) cmd_preparar "$@" ;;
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
    atualizar) cmd_atualizar ;;
    remover) cmd_remover "$@" ;;
    ajuda|-h|--help) sed -n '2,25p' "$0" ;;
    *) erro "comando desconhecido: $COMANDO (use 'ajuda')" ;;
esac
