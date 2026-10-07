// Torre de Controle (RF-010/011): episódios abertos da unidade ativa com filtros, ordenação,
// tempos (referência = relógio do servidor), etapa, bloqueio, pendências e alertas OPERACIONAIS
// calculados no servidor. A tela nunca recalcula regras.

import { h, substituir, campo, opcoes, mensagem, carregando, etiqueta } from '../nucleo/dom.js';
import { consulta, mensagemDeErro } from '../nucleo/api.js';
import { criarAtualizador } from '../nucleo/atualizador.js';
import * as rotulos from '../nucleo/rotulos.js';
import { cronometro, dataHora, resumoAlertas, bloqueio, avisoOperacional, situacaoRegras, atualizadoEm }
  from '../nucleo/componentes.js';

const EXIBIR = 300; // pede EXIBIR + 1 para saber, sem ambiguidade, se a lista foi truncada
const INTERVALO_MS = 30000;

export function montar(raiz, ctx) {
  const cat = ctx.catalogo();
  const fuso = ctx.fuso();
  let ativo = true;
  let filtrosAplicados = { ordem: 'TEMPO_NA_ETAPA', decrescente: 'true' };

  // ------------------------------------------------------------ filtros
  const sel = (lista, vazio) => h('select', {}, opcoes(lista, { vazio }));
  const fSetor = sel(cat.setores.filter((s) => s.ativo).map((s) => ({ valor: s.id, rotulo: s.nome })), 'Todos');
  const fEtapa = sel(cat.etapas.filter((e) => e.ativa && !e.desfecho).map((e) => ({ valor: e.id, rotulo: e.nome })), 'Todas');
  const fMotivo = sel(cat.motivos.filter((m) => m.ativo).map((m) => ({ valor: m.id, rotulo: m.descricao })), 'Todos');
  const fCategoria = sel(rotulos.CATEGORIAS.map((c) => ({ valor: c, rotulo: rotulos.categoria(c) })), 'Todas');
  const fEspecialidade = sel(cat.especialidades.map((e) => ({ valor: e.id, rotulo: e.nome })), 'Todas');
  const fResponsavel = sel(cat.profissionais.map((p) => ({ valor: p.id, rotulo: p.nome })), 'Qualquer');
  const fMinutos = h('input', { type: 'number', min: '0', max: '100000', step: '1', inputmode: 'numeric' });
  const fVencidas = h('input', { type: 'checkbox', id: 'f-vencidas' });
  const fOrdem = sel(rotulos.ORDENS.map((o) => ({ valor: o, rotulo: rotulos.ordem(o) })));
  fOrdem.value = 'TEMPO_NA_ETAPA';
  const fDecrescente = h('select', {}, opcoes([{ valor: 'true', rotulo: 'Maior primeiro' },
    { valor: 'false', rotulo: 'Menor primeiro' }]));

  const formFiltros = h('form', { class: 'cartao', 'aria-label': 'Filtros da Torre', aoEnviar: (e) => {
    e.preventDefault();
    if (!formFiltros.checkValidity()) { formFiltros.reportValidity(); return; }
    filtrosAplicados = {
      setor: fSetor.value, etapa: fEtapa.value, motivo: fMotivo.value, categoria: fCategoria.value,
      especialidade: fEspecialidade.value, responsavel: fResponsavel.value, minutosNaEtapa: fMinutos.value,
      somenteVencidas: fVencidas.checked, ordem: fOrdem.value, decrescente: fDecrescente.value, // texto: 'false' não some
    };
    atualizador.atualizarAgora();
  } },
  h('div', { class: 'linha' },
    campo('Setor', fSetor), campo('Etapa', fEtapa), campo('Motivo do bloqueio', fMotivo),
    campo('Categoria do bloqueio', fCategoria), campo('Especialidade de destino', fEspecialidade),
    campo('Responsável por pendência', fResponsavel),
    campo('Mínimo de minutos na etapa', fMinutos),
    h('div', { class: 'campo' }, h('span', {}, ' '), h('label', { for: 'f-vencidas' }, fVencidas,
      ' Só com pendência vencida')),
    campo('Ordenar por', fOrdem), campo('Ordem', fDecrescente)),
  h('div', { class: 'acoes' },
    h('button', { type: 'submit' }, 'Aplicar filtros'),
    h('button', { type: 'button', class: 'botao-secundario', aoClicar: () => {
      formFiltros.reset();
      fOrdem.value = 'TEMPO_NA_ETAPA';
      formFiltros.requestSubmit();
    } }, 'Limpar filtros')));

  // ------------------------------------------------------------ resultado
  const situacao = h('div');
  const resultado = h('div', { 'aria-live': 'off' }, carregando());
  substituir(raiz,
    h('div', { class: 'cabecalho-tela' }, h('h1', {}, 'Torre de Controle'),
      h('div', { class: 'acoes' },
        ctx.pode('EPISODIO_ABRIR') ? h('a', { href: '#/abrir', class: 'botao' }, 'Abrir episódio') : null,
        h('button', { type: 'button', class: 'botao-secundario', aoClicar: () => atualizador.atualizarAgora() },
          'Atualizar agora'))),
    avisoOperacional(),
    formFiltros, situacao, resultado);

  async function carregar() {
    try {
      const f = filtrosAplicados;
      const [torre, regras] = await Promise.all([
        ctx.api.obter(`/api/episodios${consulta({ ...f, limite: EXIBIR + 1 })}`),
        ctx.api.obter('/api/config/regras-alerta'),
      ]);
      if (!ativo) return;
      ctx.sincronizar(torre.agora);
      desenhar(torre, regras.filter((r) => r.ativa).length);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) throw e;
      if (ctx.tratarErroGlobal(e)) return;
      // Mantém a última lista na tela (a faixa indica dados possivelmente desatualizados).
      substituir(situacao, mensagem('erro', `Falha ao atualizar: ${mensagemDeErro(e)}`));
      if (!resultado.querySelector('table, .vazio')) substituir(resultado);
      throw e;
    }
  }

  function desenhar(torre, regrasAtivas) {
    const agora = ctx.agora();
    const truncada = torre.itens.length > EXIBIR;
    const itens = truncada ? torre.itens.slice(0, EXIBIR) : torre.itens;
    const totalAlertas = itens.filter((l) => (torre.alertas[l.episodioId] || []).length > 0).length;
    substituir(situacao,
      atualizadoEm(torre.agora, fuso, false),
      situacaoRegras(regrasAtivas, totalAlertas),
      truncada ? mensagem('aviso', `Lista truncada: exibindo os ${EXIBIR} primeiros casos pela ordenação escolhida. `
        + 'Refine os filtros para ver os demais.') : null);

    const focoAnterior = document.activeElement && document.activeElement.getAttribute
      ? document.activeElement.getAttribute('data-episodio') : null;
    if (itens.length === 0) {
      substituir(resultado, h('p', { class: 'vazio', role: 'status' },
        'Nenhum episódio aberto encontrado com esses filtros.'));
      return;
    }
    const linhas = itens.map((l) => {
      const alertas = torre.alertas[l.episodioId] || [];
      return h('tr', { class: alertas.length ? 'em-alerta' : null },
        h('td', { 'data-rotulo': 'Paciente' },
          h('a', { href: `#/episodio/${l.episodioId}`, 'data-episodio': l.episodioId }, l.pacienteNome),
          h('div', { class: 'discreto' }, l.setorNome)),
        h('td', { 'data-rotulo': 'Etapa' }, l.etapaNome, h('div', { class: 'discreto' },
          rotulos.natureza(l.natureza), ' · há ', cronometro(l.etapaDesde, agora))),
        h('td', { 'data-rotulo': 'Tempo total' }, cronometro(l.entradaEm, agora),
          h('div', { class: 'discreto' }, 'desde ', dataHora(l.entradaEm, fuso))),
        h('td', { 'data-rotulo': 'Bloqueio' }, bloqueio(l, agora)),
        h('td', { 'data-rotulo': 'Pendências' },
          l.pendenciasAbertas === 0 ? h('span', { class: 'discreto' }, 'Nenhuma aberta')
            : h('span', {}, `${l.pendenciasAbertas} aberta(s)`),
          l.pendenciasVencidas > 0 ? h('div', {}, etiqueta('alerta', `${l.pendenciasVencidas} vencida(s)`)) : null,
          l.proximoPrazo ? h('div', { class: 'discreto' }, 'Próximo prazo: ', dataHora(l.proximoPrazo, fuso)) : null),
        h('td', { 'data-rotulo': 'Criticidade operacional' },
          l.maiorCriticidade ? rotulos.criticidade(l.maiorCriticidade) : h('span', { class: 'discreto' }, '—')),
        h('td', { 'data-rotulo': 'Alertas operacionais' }, resumoAlertas(alertas)),
        h('td', { 'data-rotulo': 'Protocolo / destino' },
          l.protocoloNumero ? `${l.protocoloSistema || ''} ${l.protocoloNumero}` : h('span', { class: 'discreto' }, 'Sem protocolo'),
          l.especialidadeNome ? h('div', { class: 'discreto' }, l.especialidadeNome) : null));
    });
    substituir(resultado, h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
      h('caption', {}, `${itens.length} episódio(s) aberto(s)`),
      h('thead', {}, h('tr', {}, ['Paciente / setor', 'Etapa', 'Tempo total', 'Bloqueio', 'Pendências',
        'Criticidade operacional', 'Alertas operacionais', 'Protocolo / destino'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, linhas))));
    if (focoAnterior) {
      const el = resultado.querySelector(`[data-episodio="${CSS.escape(focoAnterior)}"]`);
      if (el) el.focus();
    }
  }

  const atualizador = criarAtualizador({
    carregar, intervaloMs: INTERVALO_MS,
    emEdicao: () => formFiltros.contains(document.activeElement),
    aoMudarSituacao: (s) => { if (ativo) ctx.situacaoAtualizacao(s); },
  });
  atualizador.atualizarAgora();
  atualizador.iniciar();

  return {
    desmontar() { ativo = false; atualizador.parar(); },
    emEdicao: () => false,
  };
}
