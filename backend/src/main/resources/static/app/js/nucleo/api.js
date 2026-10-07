// Cliente HTTP da interface: mesma origem, sessão no servidor (cookie HttpOnly), CSRF no
// modo SPA do Spring Security (cookie XSRF-TOKEN -> cabeçalho X-XSRF-TOKEN). Nunca guarda
// credencial: nada em localStorage/sessionStorage. Respostas de uma "geração" anterior
// (antes de sair, trocar de unidade ou ter a sessão revogada) são descartadas.

/** Erro de API com o corpo problem+json do servidor (codigo, detail, correlacao). */
export class ErroApi extends Error {
  constructor(status, corpo) {
    super((corpo && (corpo.detail || corpo.title)) || `Erro ${status}`);
    this.name = 'ErroApi';
    this.status = status;
    this.codigo = corpo && corpo.codigo ? String(corpo.codigo) : null;
    this.correlacao = corpo && corpo.correlacao ? String(corpo.correlacao) : null;
  }
  get conflito() { return this.status === 409; }
  get sessaoEncerrada() { return this.status === 401; }
  get trocaDeSenha() { return this.status === 403 && this.codigo === 'TROCA_DE_SENHA_OBRIGATORIA'; }
}

/** Falha de rede (servidor fora do ar, sem conexão): os dados na tela podem estar desatualizados. */
export class ErroConexao extends Error {
  constructor(causa) { super('Sem conexão com o servidor'); this.name = 'ErroConexao'; this.causa = causa; }
}

/** Resposta descartada por pertencer a uma geração anterior da sessão/unidade. */
export class RespostaDescartada extends Error {
  constructor() { super('Resposta descartada (sessão ou unidade mudou)'); this.name = 'RespostaDescartada'; }
}

const METODOS_SEGUROS = new Set(['GET', 'HEAD']);

/**
 * @param {object} deps fetch, lerCookie(nome), geracao() -> número atual, aoEncerrarSessao(erro)
 */
export function criarApi({ fetch, lerCookie, geracao, aoEncerrarSessao = () => {} }) {
  async function garantirCsrf() {
    if (!lerCookie('XSRF-TOKEN')) {
      await executar('GET', '/api/sessao/csrf', undefined, { semGeracao: true });
    }
  }

  async function executar(metodo, caminho, corpo, opcoes = {}) {
    if (typeof caminho !== 'string' || !caminho.startsWith('/api/')) {
      throw new Error('caminho de API inválido');
    }
    const geracaoInicial = geracao();
    if (!METODOS_SEGUROS.has(metodo)) {
      await garantirCsrf();
    }
    const cabecalhos = { Accept: 'application/json' };
    if (corpo !== undefined) cabecalhos['Content-Type'] = 'application/json';
    const token = lerCookie('XSRF-TOKEN');
    if (!METODOS_SEGUROS.has(metodo) && token) cabecalhos['X-XSRF-TOKEN'] = token;
    let resposta;
    try {
      resposta = await fetch(caminho, {
        method: metodo,
        headers: cabecalhos,
        body: corpo === undefined ? undefined : JSON.stringify(corpo),
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        signal: opcoes.signal,
      });
    } catch (e) {
      if (e && e.name === 'AbortError') throw e;
      throw new ErroConexao(e);
    }
    if (!opcoes.semGeracao && geracao() !== geracaoInicial) {
      throw new RespostaDescartada();
    }
    const texto = resposta.status === 204 ? '' : await resposta.text();
    let dados = null;
    if (texto) {
      try { dados = JSON.parse(texto); } catch { dados = null; }
    }
    if (!resposta.ok) {
      const erro = new ErroApi(resposta.status, dados);
      if (erro.sessaoEncerrada && !opcoes.login) aoEncerrarSessao(erro);
      throw erro;
    }
    return { status: resposta.status, dados };
  }

  const json = (metodo) => async (caminho, corpo, opcoes) => (await executar(metodo, caminho, corpo, opcoes)).dados;
  return {
    executar,
    obter: json('GET'),
    criar: json('POST'),
    substituir: json('PUT'),
    alterar: json('PATCH'),
    remover: json('DELETE'),
    /** Força um token CSRF novo (após login e troca de senha o servidor rotaciona o token). */
    async renovarCsrf() { await executar('GET', '/api/sessao/csrf', undefined, { semGeracao: true }); },
  };
}

/** Monta query string só com valores preenchidos. */
export function consulta(parametros) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(parametros)) {
    if (v !== undefined && v !== null && v !== '' && v !== false) p.set(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : '';
}

/** Mensagem para o usuário a partir de um erro (sem detalhes técnicos). */
export function mensagemDeErro(e) {
  if (e instanceof ErroConexao) return 'Sem conexão com o servidor. Os dados exibidos podem estar desatualizados.';
  if (e instanceof ErroApi) {
    if (e.status === 409) return 'Este registro foi alterado por outra pessoa. Atualize a tela e refaça a ação, se ainda for necessária.';
    if (e.status === 403) return e.trocaDeSenha ? 'É necessário trocar a senha antes de continuar.' : 'Você não tem permissão para esta ação.';
    if (e.status === 404) return 'Registro não encontrado (ou fora da unidade ativa).';
    if (e.status === 429) return 'Muitas tentativas. Aguarde alguns minutos.';
    if (e.status === 503) return 'Servidor ocupado. Tente novamente em instantes.';
    if (e.status >= 500) return `Erro inesperado no servidor${e.correlacao ? ` (código ${e.correlacao})` : ''}.`;
    return e.message;
  }
  return 'Erro inesperado.';
}
