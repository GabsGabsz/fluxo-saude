// Cliente HTTP da interface: mesma origem, sessão no servidor (cookie HttpOnly), CSRF no
// modo SPA do Spring Security (cookie XSRF-TOKEN -> cabeçalho X-XSRF-TOKEN). Nunca guarda
// credencial: nada em localStorage/sessionStorage. Operações de um contexto anterior (antes de
// sair, trocar de unidade ou ter a sessão revogada) não são enviadas, e respostas dele são
// descartadas (ver executar()).

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
  /** A unidade ativa da sessão (compartilhada entre abas) não é a que esta tela mostra. */
  get unidadeAlterada() { return this.status === 409 && this.codigo === 'UNIDADE_ATIVA_ALTERADA'; }
}

/** Falha de rede (servidor fora do ar, sem conexão): os dados na tela podem estar desatualizados. */
export class ErroConexao extends Error {
  constructor(causa) { super('Sem conexão com o servidor'); this.name = 'ErroConexao'; this.causa = causa; }
}

/**
 * Resposta descartada por pertencer a um contexto anterior (sessão/unidade mudou). Com
 * {@code enviada === false}, a operação NEM CHEGOU a ser enviada ao servidor.
 */
export class RespostaDescartada extends Error {
  constructor(enviada = true) {
    super(enviada ? 'Resposta descartada (sessão ou unidade mudou)'
      : 'Operação não enviada: a sessão ou a unidade mudou antes do envio');
    this.name = 'RespostaDescartada';
    this.enviada = enviada;
  }
}

const METODOS_SEGUROS = new Set(['GET', 'HEAD']);
const OPCOES_FETCH = { credentials: 'same-origin', cache: 'no-store', redirect: 'error' };

/**
 * Toda operação pertence ao CONTEXTO (geração + unidade exibida) em que foi iniciada:
 *  - o contexto é capturado na entrada e nunca trocado pelo atual (o cabeçalho X-Fluxo-Unidade é
 *    sempre o da unidade em que a operação foi preparada);
 *  - é conferido de novo depois de obter o token CSRF e imediatamente antes do envio: se mudou,
 *    a operação NÃO é enviada (RespostaDescartada com enviada = false);
 *  - é conferido depois dos cabeçalhos e depois do corpo da resposta: resposta de contexto
 *    anterior (inclusive 401/409) é descartada e não aciona os tratadores globais.
 * Não há exceção genérica a essa regra. Só duas operações não pertencem a um contexto, e por
 * isso têm métodos próprios: obter o token CSRF (não traz dado) e encerrar a sessão (sair vale
 * para qualquer unidade).
 *
 * @param {object} deps fetch, lerCookie(nome), geracao() -> número atual, aoEncerrarSessao(erro),
 *   unidadeEsperada() -> id da unidade exibida (o servidor recusa com 409 UNIDADE_ATIVA_ALTERADA se
 *   a unidade da sessão for outra, ex.: trocada em outra aba), aoMudarUnidade(erro)
 */
export function criarApi({ fetch, lerCookie, geracao, aoEncerrarSessao = () => {}, unidadeEsperada = () => null,
  aoMudarUnidade = () => {} }) {
  /** Busca o token CSRF (cookie XSRF-TOKEN) se ainda não houver. Não lê nem altera contexto. */
  async function garantirCsrf() {
    if (lerCookie('XSRF-TOKEN')) return;
    let r;
    try {
      r = await fetch('/api/sessao/csrf', { method: 'GET', headers: { Accept: 'application/json' }, ...OPCOES_FETCH });
    } catch (e) {
      throw new ErroConexao(e);
    }
    if (!r.ok) throw new ErroApi(r.status, null);
  }

  async function executar(metodo, caminho, corpo, opcoes = {}) {
    if (typeof caminho !== 'string' || !caminho.startsWith('/api/')) {
      throw new Error('caminho de API inválido');
    }
    // Contexto da operação: capturado UMA vez, na entrada.
    const contexto = { geracao: geracao(), unidade: unidadeEsperada() || null };
    const vigente = () => geracao() === contexto.geracao && (unidadeEsperada() || null) === contexto.unidade;
    const corpoJson = corpo === undefined ? undefined : JSON.stringify(corpo); // preparado no contexto de origem

    const seguro = METODOS_SEGUROS.has(metodo);
    if (!seguro) {
      try {
        await garantirCsrf();
      } catch (e) {
        if (!vigente()) throw new RespostaDescartada(false); // falhou, mas o contexto já é outro
        throw e;
      }
      if (!vigente()) throw new RespostaDescartada(false); // mudou enquanto esperava o token
    }
    const cabecalhos = { Accept: 'application/json' };
    if (contexto.unidade) cabecalhos['X-Fluxo-Unidade'] = contexto.unidade;
    if (corpoJson !== undefined) cabecalhos['Content-Type'] = 'application/json';
    if (!seguro) {
      const token = lerCookie('XSRF-TOKEN');
      if (token) cabecalhos['X-XSRF-TOKEN'] = token;
    }
    // Última conferência, sem nenhum await entre ela e o envio.
    if (!vigente()) throw new RespostaDescartada(false);
    let resposta;
    try {
      resposta = await fetch(caminho, {
        method: metodo, headers: cabecalhos, body: corpoJson, signal: opcoes.signal, ...OPCOES_FETCH,
      });
    } catch (e) {
      if (e && e.name === 'AbortError') throw e;
      if (!vigente()) throw new RespostaDescartada();
      throw new ErroConexao(e);
    }
    if (!vigente()) { descartarCorpo(resposta); throw new RespostaDescartada(); } // cabeçalhos tardios
    let texto = '';
    if (resposta.status !== 204) {
      try {
        texto = await resposta.text();
      } catch (e) {
        if (!vigente()) throw new RespostaDescartada();
        throw new ErroConexao(e);
      }
    }
    if (!vigente()) throw new RespostaDescartada(); // corpo terminou de chegar depois da mudança
    let dados = null;
    if (texto) {
      try { dados = JSON.parse(texto); } catch { dados = null; }
    }
    if (!resposta.ok) {
      const erro = new ErroApi(resposta.status, dados);
      // Só respostas do contexto VIGENTE chegam aqui: uma 401/409 antiga nunca mexe no contexto novo.
      if (erro.sessaoEncerrada && !opcoes.login) aoEncerrarSessao(erro);
      if (erro.unidadeAlterada) aoMudarUnidade(erro);
      throw erro;
    }
    return { status: resposta.status, dados };
  }

  /**
   * Sair: encerra a sessão do servidor qualquer que seja o contexto (não envia unidade, não
   * aciona tratadores globais). 401 = já estava encerrada.
   */
  async function encerrarSessao() {
    await garantirCsrf();
    const token = lerCookie('XSRF-TOKEN');
    let r;
    try {
      r = await fetch('/api/sessao', { method: 'DELETE',
        headers: token ? { Accept: 'application/json', 'X-XSRF-TOKEN': token } : { Accept: 'application/json' },
        ...OPCOES_FETCH });
    } catch (e) {
      throw new ErroConexao(e);
    }
    if (!r.ok && r.status !== 401) throw new ErroApi(r.status, null);
  }

  const json = (metodo) => async (caminho, corpo, opcoes) => (await executar(metodo, caminho, corpo, opcoes)).dados;
  return {
    executar,
    obter: json('GET'),
    criar: json('POST'),
    substituir: json('PUT'),
    alterar: json('PATCH'),
    remover: json('DELETE'),
    encerrarSessao,
  };
}

function descartarCorpo(resposta) {
  try { if (resposta.body && resposta.body.cancel) resposta.body.cancel().catch(() => {}); } catch { /* ignora */ }
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
    if (e.unidadeAlterada) return 'A unidade ativa foi trocada (por exemplo, em outra aba). Nada foi gravado; a tela será recarregada.';
    if (e.status === 409) return 'Este registro foi alterado por outra pessoa. Atualize a tela e refaça a ação, se ainda for necessária.';
    if (e.trocaDeSenha) return 'É necessário trocar a senha antes de continuar.';
    if (e.status === 403) return 'Ação recusada: sem permissão para ela, ou a proteção da sessão expirou. Se você tem permissão, tente de novo; persistindo, saia e entre novamente.';
    if (e.status === 404) return 'Registro não encontrado (ou fora da unidade ativa).';
    if (e.status === 429) return 'Muitas tentativas. Aguarde alguns minutos.';
    if (e.status === 503) return 'Servidor ocupado. Tente novamente em instantes.';
    if (e.status >= 500) return `Erro inesperado no servidor${e.correlacao ? ` (código ${e.correlacao})` : ''}.`;
    return e.message;
  }
  return 'Erro inesperado.';
}
