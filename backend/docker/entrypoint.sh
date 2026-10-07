#!/usr/bin/env bash
# Entrada da imagem: lê as senhas de ARQUIVOS de segredo (montados em tempo de execução, fora da
# imagem e do Git) e inicia a aplicação. Nenhuma senha é escrita em log nem em argumento de
# linha de comando.
#   FLUXO_DB_APP_PASSWORD_FILE   -> aplicação (papel fluxo_app, sujeito a RLS)
#   FLUXO_DB_OWNER_PASSWORD_FILE -> SOMENTE o job de migração (perfil "migracao")
#   FLUXO_PROXY_CONFIAVEL        -> IP do proxy reverso: só dele os cabeçalhos X-Forwarded-*
#                                   são aceitos (sem isso, nenhum cabeçalho de origem é aceito).
set -euo pipefail

ler_segredo() {   # ler_segredo VAR  -> exporta VAR a partir de VAR_FILE, se houver
    local var="$1" arquivo_var="$1_FILE"
    local arquivo="${!arquivo_var:-}"
    if [ -n "$arquivo" ]; then
        [ -r "$arquivo" ] || { echo "segredo ilegível: $arquivo_var" >&2; exit 78; }
        export "$var"="$(cat "$arquivo")"
        unset "$arquivo_var"
    fi
}
ler_segredo FLUXO_DB_APP_PASSWORD
ler_segredo FLUXO_DB_OWNER_PASSWORD

if [ -n "${FLUXO_PROXY_CONFIAVEL:-}" ]; then
    case "$FLUXO_PROXY_CONFIAVEL" in
        *[!0-9.]*|"") echo "FLUXO_PROXY_CONFIAVEL deve ser um único IPv4" >&2; exit 78 ;;
    esac
    export SERVER_FORWARD_HEADERS_STRATEGY=native
    # Expressão regular exata do IP (pontos escapados): nenhum outro endereço é confiável.
    export SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES="${FLUXO_PROXY_CONFIAVEL//./\\.}"
    export SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=X-Forwarded-For
    export SERVER_TOMCAT_REMOTEIP_PROTOCOL_HEADER=X-Forwarded-Proto
fi

exec java -jar /app/app.jar "$@"
