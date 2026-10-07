// Painel coletivo pseudonimizado (RNF-015): sem nome, CNS ou detalhe de regra. Pensado para
// leitura à distância; "Em alerta" = atingiu algum limite operacional configurado.

import { h, substituir, mensagem, carregando, etiqueta } from '../nucleo/dom.js';
import { mensagemDeErro } from '../nucleo/api.js';
import { criarAtualizador } from '../nucleo/atualizador.js';
import * as rotulos from '../nucleo/rotulos.js';
import { cronometro, avisoOperacional, atualizadoEm } from '../nucleo/componentes.js';

const INTERVALO_MS = 30000;
const LIMITE_SERVIDOR = 500;

export function montar(raiz, ctx) {
  const fuso = ctx.fuso();
  let ativo = true;
  const situacao = h('div');
  const tabela = h('div', {}, carregando());
  substituir(raiz, h('div', { class: 'painel' },
    h('div', { class: 'cabecalho-tela' }, h('h1', {}, 'Painel coletivo'),
      h('button', { type: 'button', class: 'botao-secundario', aoClicar: () => atualizador.atualizarAgora() }, 'Atualizar agora')),
    h('p', { class: 'discreto' }, 'Identificação por pseudônimo: este painel não exibe nomes nem documentos.'),
    avisoOperacional(), situacao, tabela));

  async function carregar() {
    try {
      const p = await ctx.api.obter('/api/painel');
      if (!ativo) return;
      ctx.sincronizar(p.agora);
      desenhar(p);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) throw e;
      if (ctx.tratarErroGlobal(e)) return;
      substituir(situacao, mensagem('erro', `Falha ao atualizar: ${mensagemDeErro(e)}`));
      if (tabela.querySelector('.carregando')) substituir(tabela);
      throw e;
    }
  }

  function desenhar(p) {
    const agora = ctx.agora();
    substituir(situacao, atualizadoEm(p.agora, fuso, false),
      p.itens.length >= LIMITE_SERVIDOR ? mensagem('aviso', `Exibindo os ${LIMITE_SERVIDOR} primeiros casos; pode haver mais.`) : null);
    if (p.itens.length === 0) {
      substituir(tabela, h('p', { class: 'vazio', role: 'status' }, 'Nenhum episódio aberto na unidade.'));
      return;
    }
    substituir(tabela, h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
      h('caption', {}, `${p.itens.length} episódio(s) aberto(s)`),
      h('thead', {}, h('tr', {}, ['Identificação', 'Setor', 'Etapa', 'Tempo total', 'Na etapa', 'Bloqueio',
        'Pendências vencidas', 'Alerta operacional'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, p.itens.map((l) => h('tr', { class: l.emAlerta ? 'em-alerta' : null },
        h('td', { 'data-rotulo': 'Identificação' }, h('strong', {}, l.identificacao)),
        h('td', { 'data-rotulo': 'Setor' }, l.setor),
        h('td', { 'data-rotulo': 'Etapa' }, l.etapa, h('div', { class: 'discreto' }, rotulos.natureza(l.natureza))),
        h('td', { 'data-rotulo': 'Tempo total' }, cronometro(l.entradaEm, agora)),
        h('td', { 'data-rotulo': 'Na etapa' }, cronometro(l.etapaDesde, agora)),
        h('td', { 'data-rotulo': 'Bloqueio' }, l.bloqueioDesde
          ? [etiqueta('bloqueio', rotulos.categoria(l.categoriaBloqueio)), ' há ', cronometro(l.bloqueioDesde, agora)]
          : h('span', { class: 'discreto' }, 'Sem bloqueio')),
        h('td', { 'data-rotulo': 'Pendências vencidas', class: 'numero' }, l.pendenciasVencidas > 0
          ? etiqueta('alerta', String(l.pendenciasVencidas)) : '0'),
        h('td', { 'data-rotulo': 'Alerta operacional' }, l.emAlerta ? etiqueta('alerta', 'Em alerta')
          : h('span', { class: 'discreto' }, 'Não'))))))));
  }

  const atualizador = criarAtualizador({
    carregar, intervaloMs: INTERVALO_MS,
    aoMudarSituacao: (s) => { if (ativo) ctx.situacaoAtualizacao(s); },
  });
  atualizador.atualizarAgora();
  atualizador.iniciar();
  return { desmontar() { ativo = false; atualizador.parar(); }, emEdicao: () => false };
}
