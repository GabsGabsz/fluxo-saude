#!/usr/bin/env python3
"""Verificações do ambiente de HOMOLOGAÇÃO pela API real, através do proxy HTTPS (só biblioteca padrão).

Usado pelo job "Homologação" do CI e, opcionalmente, pelo operador. Usa apenas dados FICTÍCIOS.
Senhas são lidas de ARQUIVOS (nunca de argumentos) e nunca são impressas.

    verificar.py --base https://localhost:8443 --ca ca.crt COMANDO [opções]

Comandos:
  cabecalhos                      página como NAVEGADOR (text/html) + recursos estáticos; HTTPS, HSTS,
                                  CSP, cookies Secure/HttpOnly/SameSite e __Host-
  primeiro-acesso                 login com senha provisória -> 403 até trocar -> troca -> permissões
  origem-forjada --correlacao ID  login inválido com X-Forwarded-For forjado e X-Correlation-Id próprio
                                  (o CI confere no banco o IP do evento DESSA requisição)

Opção global --diagnostico ARQ: em caso de falha, grava status, Content-Type, cabeçalhos seguros e um
trecho SANITIZADO da resposta (sem cookies, tokens ou senhas) para o artefato de evidências.
  criar-dados --saida ARQ         abre um episódio FICTÍCIO identificável (marcador) e guarda o cookie
  conferir-dados --entrada ARQ    o episódio do marcador existe (após reinício ou restauração)
  sessao-antiga --entrada ARQ --espera STATUS   reutiliza o cookie guardado: 200 (válida) ou 401
"""
import argparse
import html.parser
import http.cookiejar
import json
import pathlib
import re
import ssl
import sys
import urllib.error
import urllib.request

RAIZ = pathlib.Path(__file__).resolve().parents[2]
JSON = "application/json"
NAVEGADOR = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"   # como um navegador
CABECALHOS_SEGUROS = ("Content-Type", "Content-Length", "Location", "Strict-Transport-Security",
                      "Content-Security-Policy", "X-Frame-Options", "X-Content-Type-Options", "Cache-Control",
                      "X-Correlation-Id", "Vary")
DIAGNOSTICO = None          # arquivo de diagnóstico (opção --diagnostico)
ULTIMA = {}                 # resumo sanitizado da última resposta, para o diagnóstico


def sanitizar(texto):
    """Remove de um trecho de resposta qualquer coisa parecida com credencial (para logs/artefatos)."""
    t = re.sub(r'(?i)"(senha[^"]*|token|cookie|comprovante|assinatura|senhaProvisoria)"\s*:\s*"[^"]*"', r'"\1":"[removido]"', texto)
    t = re.sub(r"(?i)(__Host-[A-Za-z]+|XSRF-TOKEN|JSESSIONID|SESSION)=[^;\s]+", r"\1=[removido]", t)
    t = re.sub(r"[A-Za-z0-9+/_=-]{32,}", "[longo-removido]", t)
    return t[:600]


class Cliente:
    def __init__(self, base, ca):
        self.base = base.rstrip("/")
        self.ctx = ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPSHandler(context=self.ctx),
                                              urllib.request.HTTPCookieProcessor(self.jar))

    def chamar(self, metodo, caminho, corpo=None, cabecalhos=None, usar_jar=True, accept=JSON):
        h = {"Accept": accept}
        if corpo is not None:
            h["Content-Type"] = "application/json"
        if metodo != "GET":
            token = self.cookie("XSRF-TOKEN")
            if token is None and usar_jar:
                self.chamar("GET", "/api/sessao/csrf")
                token = self.cookie("XSRF-TOKEN")
            if token:
                h["X-XSRF-TOKEN"] = token
        h.update(cabecalhos or {})
        dados = json.dumps(corpo).encode() if corpo is not None else None
        req = urllib.request.Request(self.base + caminho, data=dados, method=metodo, headers=h)
        abrir = self.op.open if usar_jar else urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=self.ctx)).open
        try:
            with abrir(req, timeout=30) as r:
                st, hd, corpo = r.status, r.headers, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            st, hd, corpo = e.code, e.headers, e.read().decode("utf-8", "replace")
        ULTIMA.clear()
        ULTIMA.update({"requisicao": f"{metodo} {caminho} (Accept: {h['Accept']})", "status": st,
                       "cabecalhos": {k: hd.get(k) for k in CABECALHOS_SEGUROS if hd.get(k)},
                       "set_cookie_nomes": [v.split("=", 1)[0] for v in (hd.get_all("Set-Cookie") or [])],
                       "corpo": sanitizar(corpo)})
        return st, hd, corpo

    def cookie(self, nome):
        for c in self.jar:
            if c.name == nome:
                return c.value
        return None

    def entrar(self, login, senha, com_cabecalhos=False):
        st, h, corpo = self.chamar("POST", "/api/sessao", {"login": login, "senha": senha})
        exigir(st == 200, f"login de {login}: HTTP {st}")
        return (json.loads(corpo), h) if com_cabecalhos else json.loads(corpo)

    def usar_unidade(self, codigo):
        st, _, corpo = self.chamar("GET", "/api/sessao/unidades")
        exigir(st == 200, f"unidades: HTTP {st}")
        alvo = next(u for u in json.loads(corpo) if u["codigo"] == codigo)
        st, _, _ = self.chamar("PUT", "/api/sessao/unidade", {"unidadeId": alvo["id"]})
        exigir(st in (200, 204), f"troca de unidade: HTTP {st}")


def exigir(cond, msg):
    if not cond:
        print(f"FALHOU: {msg}", file=sys.stderr)
        diag = json.dumps({"falha": msg, "ultima_resposta": ULTIMA}, ensure_ascii=False, indent=2)
        print(diag, file=sys.stderr)
        if DIAGNOSTICO:
            with open(DIAGNOSTICO, "a", encoding="utf-8") as f:
                f.write(diag + "\n")
        sys.exit(1)
    print(f"ok: {msg}")


def info(msg):
    print(f"info: {msg}")
    if DIAGNOSTICO:
        with open(DIAGNOSTICO, "a", encoding="utf-8") as f:
            f.write(f"info: {msg}\n")


class Recursos(html.parser.HTMLParser):
    """Coleta os recursos estáticos referenciados pela página (script src, link href)."""
    def __init__(self):
        super().__init__()
        self.lista = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "script" and a.get("src"):
            self.lista.append(("script", a["src"]))
        if tag == "link" and a.get("href"):
            self.lista.append((a.get("rel", "link"), a["href"]))


def ler(caminho):
    return pathlib.Path(caminho).read_text(encoding="utf-8").strip()


def senha_fixture(login):
    dados = json.loads((RAIZ / "e2e" / "dados" / "fixtures.json").read_text(encoding="utf-8"))
    return next(u["senha"] for u in dados["usuarios"] if u["login"] == login)


def cmd_cabecalhos(a):
    c = Cliente(a.base, a.ca)
    # Diagnóstico (não é asserção): a página inicial do Spring Boot (WelcomePageHandlerMapping) só é
    # servida quando o Accept inclui text/html; com Accept: application/json a resposta não é a página.
    st_json, h_json, _ = c.chamar("GET", "/", accept=JSON)
    info(f"GET / com Accept: application/json -> HTTP {st_json}, Content-Type {h_json.get('Content-Type')}")
    # A página como um NAVEGADOR a pede.
    st, h, corpo = c.chamar("GET", "/", accept=NAVEGADOR)
    exigir(st == 200 and (h.get("Content-Type") or "").startswith("text/html"),
           "interface servida por HTTPS na mesma origem (text/html, como no navegador)")
    rec = Recursos()
    rec.feed(corpo)
    exigir(any(t == "script" for t, _ in rec.lista) and any(t == "stylesheet" for t, _ in rec.lista),
           f"página referencia script e folha de estilo ({len(rec.lista)} recursos)")
    tipos = {"script": "javascript", "stylesheet": "text/css", "icon": "image/svg"}
    for tipo, caminho in rec.lista:
        exigir(caminho.startswith("/") and not caminho.startswith("//"), f"recurso na mesma origem: {caminho}")
        st_r, h_r, corpo_r = c.chamar("GET", caminho, accept="*/*")
        esperado = tipos.get(tipo, "")
        exigir(st_r == 200 and esperado in (h_r.get("Content-Type") or ""),
               f"recurso estático {caminho}: HTTP {st_r}, {h_r.get('Content-Type')}")
        if tipo == "script":   # módulos ES importados pelo main.js também carregam
            for imp in sorted(set(re.findall(r"from '(\./[^']+\.js)'", corpo_r)))[:3]:
                alvo = caminho.rsplit("/", 1)[0] + "/" + imp[2:]
                st_m, h_m, _ = c.chamar("GET", alvo, accept="*/*")
                exigir(st_m == 200 and "javascript" in (h_m.get("Content-Type") or ""), f"módulo {alvo}: HTTP {st_m}")
    exigir("max-age" in (h.get("Strict-Transport-Security") or ""),
           "HSTS presente (a aplicação reconhece o HTTPS informado pelo proxy confiável)")
    exigir("frame-ancestors 'none'" in (h.get("Content-Security-Policy") or ""), "CSP restritiva")
    exigir((h.get("X-Frame-Options") or "").upper() == "DENY", "X-Frame-Options: DENY")
    exigir("Server" not in h or "Caddy" not in h.get("Server", ""), "proxy não anuncia o servidor")
    st, h, _ = c.chamar("GET", "/api/sessao/csrf")
    csrf = [v for v in (h.get_all("Set-Cookie") or []) if v.startswith("XSRF-TOKEN=")]
    exigir(st == 200 and csrf and "secure" in csrf[0].lower(), "cookie CSRF com Secure")
    st, h, _ = c.chamar("GET", "/actuator/health")
    exigir(st == 200, "saúde pública só com UP/DOWN")


def cmd_primeiro_acesso(a):
    c = Cliente(a.base, a.ca)
    sessao, h = c.entrar(a.login, ler(a.senha_arquivo), com_cabecalhos=True)
    exigir(sessao.get("deveTrocarSenha") is True and not sessao.get("permissoes"),
           "administrador inicial obrigado a trocar a senha (sem permissões até lá)")
    sc = [v.lower() for v in (h.get_all("Set-Cookie") or []) if v.startswith("__Host-")]
    exigir(sc and "secure" in sc[0] and "httponly" in sc[0] and "samesite=strict" in sc[0] and "path=/" in sc[0]
           and "domain=" not in sc[0], "cookie de sessão __Host-, Secure, HttpOnly, SameSite=Strict, sem Domain")
    st, _, corpo = c.chamar("GET", "/api/episodios")
    exigir(st == 403 and "TROCA_DE_SENHA_OBRIGATORIA" in corpo, "API recusa uso antes da troca de senha")
    st, _, _ = c.chamar("PUT", "/api/sessao/senha", {"senhaAtual": ler(a.senha_arquivo),
                                                     "novaSenha": ler(a.nova_senha_arquivo)})
    exigir(st in (200, 204), "troca de senha no primeiro acesso")
    st, _, corpo = c.chamar("GET", "/api/sessao")
    s = json.loads(corpo)
    exigir(st == 200 and s.get("deveTrocarSenha") is False and "USUARIO_GERENCIAR" in s.get("permissoes", []),
           "administrador liberado após a troca")
    c2 = Cliente(a.base, a.ca)
    st, _, _ = c2.chamar("POST", "/api/sessao", {"login": a.login, "senha": ler(a.senha_arquivo)})
    exigir(st == 401, "senha provisória não vale mais")


def cmd_origem_forjada(a):
    c = Cliente(a.base, a.ca)
    st, h, _ = c.chamar("POST", "/api/sessao", {"login": f"inexistente.{a.correlacao.lower()}", "senha": "senha-ficticia-qualquer-1"},
                        cabecalhos={"X-Forwarded-For": "203.0.113.99", "X-Real-IP": "203.0.113.99",
                                    "Forwarded": "for=203.0.113.99", "X-Correlation-Id": a.correlacao})
    exigir(st == 401, f"login inválido via proxy recusado por CREDENCIAL (HTTP {st}; 403 seria CSRF)")
    exigir(h.get("X-Correlation-Id") == a.correlacao, "a aplicação adotou o identificador de correlação enviado")


def cmd_criar_dados(a):
    c = Cliente(a.base, a.ca)
    c.entrar(a.login, senha_fixture(a.login))
    c.usar_unidade(a.unidade)
    st, _, corpo = c.chamar("GET", "/api/catalogo")
    exigir(st == 200, "catálogo da unidade")
    setor = json.loads(corpo)["setores"][0]["id"]
    nome = f"Paciente Ficticio Homologacao {a.marcador}"
    st, _, corpo = c.chamar("POST", "/api/episodios", {"novoPaciente": {"nome": nome}, "setorId": setor})
    exigir(st == 201, f"episódio fictício aberto ({nome})")
    ep = json.loads(corpo)["id"]
    sessao = next(ck for ck in c.jar if ck.name.startswith("__Host-"))
    pathlib.Path(a.saida).write_text(json.dumps({"episodio": ep, "nome": nome, "unidade": a.unidade, "login": a.login,
                                                 "cookie": f"{sessao.name}={sessao.value}"}), encoding="utf-8")
    pathlib.Path(a.saida).chmod(0o600)


def cmd_conferir_dados(a):
    d = json.loads(ler(a.entrada))
    c = Cliente(a.base, a.ca)
    c.entrar(d["login"], senha_fixture(d["login"]))
    c.usar_unidade(d["unidade"])
    st, _, corpo = c.chamar("GET", f"/api/episodios/{d['episodio']}")
    exigir(st == 200 and d["nome"] in corpo, f"episódio fictício presente: {d['nome']}")
    st, _, corpo = c.chamar("GET", "/api/episodios")
    exigir(st == 200 and d["nome"] in corpo, "episódio visível na Torre")


def cmd_sessao_antiga(a):
    d = json.loads(ler(a.entrada))
    c = Cliente(a.base, a.ca)
    st, _, _ = c.chamar("GET", "/api/sessao", cabecalhos={"Cookie": d["cookie"]}, usar_jar=False)
    exigir(st == int(a.espera), f"sessão guardada responde {st} (esperado {a.espera})")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", required=True)
    p.add_argument("--ca")
    p.add_argument("--diagnostico", help="arquivo onde gravar diagnóstico SANITIZADO em caso de falha")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("cabecalhos")
    s = sub.add_parser("primeiro-acesso")
    s.add_argument("--login", required=True)
    s.add_argument("--senha-arquivo", required=True)
    s.add_argument("--nova-senha-arquivo", required=True)
    s = sub.add_parser("origem-forjada")
    s.add_argument("--correlacao", required=True)
    s = sub.add_parser("criar-dados")
    s.add_argument("--login", default="coord.e2e")
    s.add_argument("--unidade", default="E2E_NORTE")
    s.add_argument("--marcador", required=True)
    s.add_argument("--saida", required=True)
    s = sub.add_parser("conferir-dados")
    s.add_argument("--entrada", required=True)
    s = sub.add_parser("sessao-antiga")
    s.add_argument("--entrada", required=True)
    s.add_argument("--espera", required=True)
    a = p.parse_args()
    global DIAGNOSTICO
    DIAGNOSTICO = a.diagnostico
    {"cabecalhos": cmd_cabecalhos, "primeiro-acesso": cmd_primeiro_acesso, "origem-forjada": cmd_origem_forjada,
     "criar-dados": cmd_criar_dados, "conferir-dados": cmd_conferir_dados,
     "sessao-antiga": cmd_sessao_antiga}[a.cmd](a)


if __name__ == "__main__":
    main()
