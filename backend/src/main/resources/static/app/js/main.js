// Ponto de entrada da interface (ADR-0008): roteamento por hash, sessão, unidade ativa e menu
// conforme as permissões. Toda decisão de acesso é REFEITA no servidor; o menu só evita mostrar
// o que o perfil não pode usar.

import { criarApi, mensagemDeErro, ErroApi } from './nucleo/api.js';
import { criarEstado, telasPermitidas } from './nucleo/estado.js';
import { criarRelogio, formatarDuracao, decorrido, formatarHora } from './nucleo/tempo.js';
import { h, substituir, mensagem, carregando, opcoes } from './nucleo/dom.js';
import * as telaLogin from './telas/login.js';
import * as telaSenha from './telas/senha.js';
import * as telaTorre from './telas/torre.js';
import * as telaEpisodio from './telas/episodio.js';
import * as telaAbrir from './telas/abrir.js';
import * as telaTravados from './telas/travados.js';
import * as telaPainel from './telas/painel.js';
import * as telaUsuarios from './telas/usuarios.js';
import * as telaRegras from './telas/regras.js';

const TELAS = {
  torre: { modulo: telaTorre, permissao: 'EPISODIO_VER' },
  travados: { modulo: telaTravados, permissao: 'EPISODIO_VER' },
  abrir: { modulo: telaAbrir, permissao: 'EPISODIO_ABRIR' },
  episodio: { modulo: telaEpisodio, permissao: 'EPISODIO_VER' },
  painel: { modulo: telaPainel, permissao: 'PAINEL_COLETIVO_VER' },
  usuarios: { modulo: telaUsuarios, permissao: 'USUARIO_GERENCIAR' },
  regras: { modulo: telaRegras, permissao: 'CONFIGURACAO_GERENCIAR' },
  senha: { modulo: telaSenha, permissao: null },
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const principal = document.getElementById('conteudo');
const topo = document.getElementById('topo');
const faixa = document.getElementById('faixa-desatualizado');
const anuncio = document.getElementById('anuncio');

const estado = criarEstado();
const relogio = criarRelogio();
let telaAtual = null;          // { desmontar(), emEdicao() }
let avisoLogin = null;         // mensagem a exibir na tela de login (sessão encerrada, saída...)
let ultimaAtualizacaoOk = null;

function lerCookie(nome) {
  for (const parte of document.cookie.split(';')) {
    const [k, ...v] = parte.trim().split('=');
    if (k === nome) return decodeURIComponent(v.join('='));
  }
  return '';
}

const api = criarApi({
  fetch: (...args) => window.fetch(...args),
  lerCookie,
  geracao: () => estado.geracao(),
  aoEncerrarSessao: () => encerrarLocalmente('Sua sessão foi encerrada (expirou, foi revogada ou a senha foi alterada). Entre novamente.'),
});

// ------------------------------------------------------------------ contexto entregue às telas
const ctx = {
  api,
  estado,
  pode: (p) => estado.pode(p),
  agora: () => relogio.agora(),
  sincronizar: (agoraIso) => relogio.sincronizar(agoraIso),
  fuso: () => {
    const c = estado.catalogo();
    return c && c.unidade ? c.unidade.fusoHorario : 'UTC';
  },
  catalogo: () => estado.catalogo(),
  anunciar(texto) {
    anuncio.textContent = '';
    setTimeout(() => { anuncio.textContent = texto; }, 50);
  },
  /** Atualização periódica da tela: falha de conexão => faixa de "dados possivelmente desatualizados". */
  situacaoAtualizacao({ falhou }) {
    if (!falhou) {
      ultimaAtualizacaoOk = relogio.agora();
      faixa.hidden = true;
      faixa.textContent = '';
      return;
    }
    faixa.hidden = false;
    faixa.textContent = ultimaAtualizacaoOk
      ? `⚠ Sem conexão com o servidor. Dados exibidos podem estar desatualizados (última atualização às ${
        formatarHora(new Date(ultimaAtualizacaoOk).toISOString(), ctx.fuso())}).`
      : '⚠ Sem conexão com o servidor. Dados exibidos podem estar desatualizados.';
  },
  irPara,
  recarregarSessao,
  /** 403 TROCA_DE_SENHA_OBRIGATORIA no meio do uso: volta para a troca de senha. */
  tratarErroGlobal(e) {
    if (e instanceof ErroApi && e.trocaDeSenha) {
      recarregarSessao();
      return true;
    }
    return false;
  },
};

// ------------------------------------------------------------------ sessão
async function recarregarSessao() {
  try {
    const sessao = await api.obter('/api/sessao', undefined, { login: true });
    estado.definirSessao(sessao);
  } catch (e) {
    if (e instanceof ErroApi && e.status === 401) {
      estado.limpar();
    } else if (e && e.name === 'RespostaDescartada') {
      return;
    } else {
      desmontar();
      topo.hidden = true;
      substituir(principal, mensagem('erro', mensagemDeErro(e)),
        h('button', { type: 'button', aoClicar: () => recarregarSessao() }, 'Tentar novamente'));
      return;
    }
  }
  await renderizar();
}

function encerrarLocalmente(texto) {
  if (!estado.sessao()) return;
  desmontar();
  estado.limpar();
  avisoLogin = texto;
  faixa.hidden = true;
  renderizar();
}

async function sair() {
  if (telaAtual && telaAtual.emEdicao && telaAtual.emEdicao()
    && !window.confirm('Há alterações não enviadas nesta tela. Sair mesmo assim?')) return;
  desmontar();
  substituir(principal, carregando('Saindo…'));
  let texto = 'Você saiu do sistema.';
  try {
    await api.remover('/api/sessao', undefined, { semGeracao: true });
  } catch (e) {
    if (!(e instanceof ErroApi && e.status === 401)) {
      texto = 'Não foi possível confirmar a saída no servidor. Os dados foram apagados desta tela; '
        + 'se estiver em computador compartilhado, feche o navegador.';
    }
  }
  estado.limpar();
  avisoLogin = texto;
  faixa.hidden = true;
  history.replaceState(null, '', '#/');
  renderizar();
}

async function carregarContexto() {
  const [catalogo, unidades] = await Promise.all([
    api.obter('/api/catalogo'),
    api.obter('/api/sessao/unidades'),
  ]);
  estado.definirUnidades(unidades);
  estado.definirCatalogo(catalogo);
}

async function trocarUnidade(unidadeId) {
  const sessao = estado.sessao();
  if (!sessao || unidadeId === sessao.unidadeAtiva) return;
  if (telaAtual && telaAtual.emEdicao && telaAtual.emEdicao()
    && !window.confirm('Há alterações não enviadas nesta tela. Trocar de unidade descarta essas alterações. Continuar?')) {
    desenharTopo();
    return;
  }
  // Tira da tela TUDO da unidade anterior antes de pedir a troca e invalida o que estiver em voo.
  desmontar();
  estado.invalidar();
  faixa.hidden = true;
  substituir(principal, carregando('Trocando de unidade…'));
  try {
    const nova = await api.substituir('/api/sessao/unidade', { unidadeId });
    estado.definirSessao(nova);
    history.replaceState(null, '', '#/');
    await renderizar();
    ctx.anunciar('Unidade ativa alterada.');
  } catch (e) {
    if (e && e.name === 'RespostaDescartada') return;
    if (e instanceof ErroApi && e.status === 401) return;
    substituir(principal, mensagem('erro', `Não foi possível trocar de unidade: ${mensagemDeErro(e)}`));
    await recarregarSessao();
  }
}

// ------------------------------------------------------------------ topo e menu
function rotaAtual() {
  const m = /^#\/([a-z]+)(?:\/([0-9a-f-]{36}))?$/.exec(location.hash);
  if (!m) return { nome: null, id: null };
  return { nome: m[1], id: m[2] && UUID.test(m[2]) ? m[2] : null };
}

function desenharTopo() {
  const sessao = estado.sessao();
  if (!sessao || sessao.deveTrocarSenha) {
    topo.hidden = true;
    substituir(topo);
    return;
  }
  const rota = rotaAtual().nome;
  const unidades = estado.unidades();
  const catalogo = estado.catalogo();
  const nomeUnidade = catalogo && catalogo.unidade ? catalogo.unidade.nome : '';
  let seletor;
  if (unidades.length > 1) {
    const sel = h('select', { id: 'unidade-ativa', aoMudar: (e) => trocarUnidade(e.target.value) },
      opcoes(unidades.map((u) => ({ valor: u.id, rotulo: u.nome }))));
    sel.value = sessao.unidadeAtiva;
    seletor = h('span', {}, h('label', { for: 'unidade-ativa' }, 'Unidade ativa: '), sel);
  } else {
    seletor = h('span', {}, 'Unidade: ', h('strong', {}, nomeUnidade));
  }
  substituir(topo,
    h('span', { class: 'marca' }, 'Fluxo Saúde'),
    h('nav', { 'aria-label': 'Telas' }, h('ul', {}, telasPermitidas(ctx.pode).map((t) => h('li', {},
      h('a', { href: `#/${t.rota}`, 'aria-current': t.rota === rota ? 'page' : null }, t.titulo))))),
    h('div', { class: 'usuario' },
      seletor,
      h('span', {}, sessao.nome),
      h('a', { href: '#/senha', class: 'botao botao-secundario', 'aria-current': rota === 'senha' ? 'page' : null },
        'Trocar senha'),
      h('button', { type: 'button', class: 'botao-secundario', aoClicar: sair }, 'Sair')));
  topo.hidden = false;
}

// ------------------------------------------------------------------ roteamento
function desmontar() {
  if (telaAtual) {
    try { telaAtual.desmontar(); } catch { /* tela já removida */ }
  }
  telaAtual = null;
}

function irPara(rota) {
  const alvo = `#/${rota}`;
  if (location.hash === alvo) renderizar();
  else location.hash = alvo;
}

let renderizando = 0;
async function renderizar() {
  const minha = ++renderizando;
  desmontar();
  const sessao = estado.sessao();

  if (!sessao) {
    topo.hidden = true;
    substituir(topo); // nada do usuário anterior permanece no DOM
    const aviso = avisoLogin;
    avisoLogin = null;
    telaAtual = telaLogin.montar(principal, ctx, { aviso, aoEntrar: async (s) => {
      estado.definirSessao(s);
      await renderizar();
    } });
    return;
  }
  if (sessao.deveTrocarSenha) {
    topo.hidden = true;
    substituir(topo);
    telaAtual = telaSenha.montar(principal, ctx, { obrigatoria: true });
    return;
  }
  if (!estado.catalogo()) {
    substituir(principal, carregando('Carregando dados da unidade…'));
    try {
      await carregarContexto();
    } catch (e) {
      if (minha !== renderizando || (e && e.name === 'RespostaDescartada')) return;
      if (ctx.tratarErroGlobal(e) || (e instanceof ErroApi && e.status === 401)) return;
      substituir(principal, mensagem('erro', `Não foi possível carregar os dados da unidade: ${mensagemDeErro(e)}`),
        h('button', { type: 'button', aoClicar: () => renderizar() }, 'Tentar novamente'));
      return;
    }
    if (minha !== renderizando) return;
  }

  const permitidas = telasPermitidas(ctx.pode);
  let { nome, id } = rotaAtual();
  if (!nome) {
    if (permitidas.length === 0) {
      desenharTopo();
      substituir(principal, h('h1', { tabindex: '-1' }, 'Fluxo Saúde'),
        mensagem('info', 'Seu perfil nesta unidade ainda não tem telas disponíveis nesta versão '
          + '(ex.: transporte e auditoria serão entregues em etapas futuras).'));
      return;
    }
    history.replaceState(null, '', `#/${permitidas[0].rota}`);
    nome = permitidas[0].rota;
  }
  desenharTopo();
  const tela = TELAS[nome];
  if (!tela || (nome === 'episodio' && !id)) {
    substituir(principal, h('h1', { tabindex: '-1' }, 'Página não encontrada'),
      h('p', {}, 'Use o menu para escolher uma tela.'));
    focarTitulo();
    return;
  }
  if (tela.permissao && !ctx.pode(tela.permissao)) {
    substituir(principal, h('h1', { tabindex: '-1' }, 'Acesso não permitido'),
      mensagem('aviso', 'Seu perfil na unidade ativa não tem acesso a esta tela.'));
    focarTitulo();
    return;
  }
  telaAtual = tela.modulo.montar(principal, ctx, { id });
  hashExibido = location.hash;
  focarTitulo();
}

function focarTitulo() {
  const titulo = principal.querySelector('h1');
  if (titulo) {
    titulo.setAttribute('tabindex', '-1');
    titulo.focus({ preventScroll: false });
  }
}

// ------------------------------------------------------------------ cronômetros (sem requisição)
function atualizarCronometros() {
  if (!relogio.sincronizado()) return;
  const agora = relogio.agora();
  for (const el of document.querySelectorAll('[data-desde]')) {
    const texto = formatarDuracao(decorrido(el.getAttribute('data-desde'), agora));
    if (el.textContent !== texto) el.textContent = texto;
  }
}

// ------------------------------------------------------------------ início
let hashExibido = location.hash;
window.addEventListener('hashchange', () => {
  if (telaAtual && telaAtual.emEdicao && telaAtual.emEdicao()
    && !window.confirm('Há alterações não enviadas nesta tela. Sair dela mesmo assim?')) {
    history.replaceState(null, '', hashExibido); // permanece na tela (e no endereço) atual
    return;
  }
  renderizar();
});
document.getElementById('pular').addEventListener('click', () => {
  const alvo = principal.querySelector('h1') || principal;
  alvo.setAttribute('tabindex', '-1');
  alvo.focus();
});
setInterval(atualizarCronometros, 10000);
estado.aoMudar(() => { if (estado.sessao() && estado.catalogo()) desenharTopo(); });

recarregarSessao();
