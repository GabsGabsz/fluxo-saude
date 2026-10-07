// Indicadores (M07: RF-019, RF-020, CA-09) e dicionário de cálculo (RF-039). Só dados agregados:
// nenhum nome, documento, identificador ou link para casos (apto ao perfil Direção). O RETRATO
// ATUAL (agora) é separado do HISTÓRICO do período. Fórmulas são PROPOSTAS até a validação
// institucional (V-09). Sem atualização automática: o cálculo é refeito por ação explícita.

import { h, substituir, campo, opcoes, mensagem, carregando, etiqueta } from '../nucleo/dom.js';
import { mensagemDeErro } from '../nucleo/api.js';
import { formatarDuracao, formatarDataHora, isoParaLocalDaUnidade } from '../nucleo/tempo.js';
import * as rotulos from '../nucleo/rotulos.js';

const SEM_DADOS = 'sem dados';

export function montar(raiz, ctx) {
  let ativo = true;
  const cat = ctx.catalogo();
  const fuso = ctx.fuso();
  const hoje = isoParaLocalDaUnidade(ctx.agora(), fuso).slice(0, 10);
  const seteDiasAtras = (() => {
    const d = new Date(`${hoje}T12:00:00Z`);
    d.setUTCDate(d.getUTCDate() - 6);
    return d.toISOString().slice(0, 10);
  })();

  const inicio = h('input', { type: 'date', required: true, value: seteDiasAtras, max: hoje });
  const fim = h('input', { type: 'date', required: true, value: hoje, max: hoje });
  const setor = h('select', {}, opcoes(cat.setores.map((s) => ({ valor: s.id, rotulo: s.nome })), { vazio: 'Todos os setores' }));
  const situacao = h('div', { 'aria-live': 'polite' });
  const resultado = h('div');
  const dicionario = h('section', { class: 'cartao', 'aria-labelledby': 'tit-dic' });
  const form = h('form', { class: 'cartao', 'aria-label': 'Período dos indicadores', aoEnviar: (e) => {
    e.preventDefault();
    if (!form.checkValidity()) { form.reportValidity(); return; }
    calcular();
  } },
  h('div', { class: 'linha' },
    campo(`Início (data local da unidade)`, inicio), campo('Fim (inclusive)', fim), campo('Setor', setor)),
  h('div', { class: 'acoes' }, h('button', { type: 'submit' }, 'Calcular')));

  substituir(raiz,
    h('h1', {}, 'Indicadores'),
    mensagem('info', 'Indicadores OPERACIONAIS e agregados: não avaliam desempenho individual, não indicam risco clínico '
      + 'e não trazem metas. Todas as fórmulas são PROPOSTAS até a validação institucional (V-09) — veja o dicionário.'),
    form, situacao, resultado, dicionario);

  let calculando = false;
  async function calcular() {
    if (calculando) return;
    calculando = true;
    substituir(situacao, carregando('Calculando…'));
    try {
      const q = new URLSearchParams({ inicio: inicio.value, fim: fim.value });
      if (setor.value) q.set('setor', setor.value);
      const r = await ctx.api.obter(`/api/indicadores?${q.toString()}`);
      if (!ativo) return;
      ctx.sincronizar(r.agora);
      substituir(situacao);
      substituir(resultado, retrato(r), historico(r));
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      if (ctx.tratarErroGlobal(e)) return;
      substituir(situacao, mensagem('erro', mensagemDeErro(e)));
      substituir(resultado);
    } finally {
      calculando = false;
    }
  }

  // ------------------------------------------------------------ retrato atual
  function retrato(r) {
    const itens = r.retrato.itens;
    const valor = (dim) => (itens.find((i) => i.dimensao === dim) || { quantidade: 0 }).quantidade;
    const tabela = (dim, rotulo) => {
      const linhas = itens.filter((i) => i.dimensao === dim).sort((a, b) => b.quantidade - a.quantidade);
      return linhas.length === 0 ? h('p', { class: 'vazio' }, `Nenhum caso por ${rotulo.toLowerCase()} agora.`)
        : tabelaSimples([rotulo, 'Casos ativos'], linhas.map((l) => [l.nome || '—', String(l.quantidade)]));
    };
    let limites;
    if (!r.retrato.regrasConfiguradas) {
      limites = mensagem('info', 'Nenhuma regra de alerta ativa: "acima dos limites" não é calculado (não significa zero).');
    } else if (!r.retrato.acimaDosLimitesDisponivel) {
      limites = mensagem('aviso', 'Indisponível: a unidade tem mais episódios abertos que o limite técnico do cálculo. '
        + 'Nenhum valor parcial é exibido.');
    } else {
      limites = tabelaSimples(['Regra (limite operacional)', 'Casos ativos acima agora'],
        r.retrato.acimaDosLimites.map((a) => [`${a.regraNome} (${rotulos.tipoRegra(a.tipo)})`, String(a.episodios)]));
    }
    return h('section', { class: 'cartao', 'aria-labelledby': 'tit-retrato' },
      h('h2', { id: 'tit-retrato' }, 'Retrato atual da unidade (agora)'),
      h('p', { class: 'discreto' }, `Situação em ${formatarDataHora(r.agora, fuso)} (horário do servidor). `,
        'Não depende do período escolhido. Conta TODOS os episódios abertos (não uma página da Torre).'),
      h('dl', { class: 'dados' },
        h('dt', {}, 'Casos ativos'), h('dd', { class: 'numero' }, String(valor('ABERTOS'))),
        h('dt', {}, 'Sem bloqueio'), h('dd', { class: 'numero' }, String(valor('SEM_BLOQUEIO'))),
        h('dt', {}, 'Pendências vencidas'), h('dd', { class: 'numero' }, String(valor('PENDENCIAS_VENCIDAS'))),
        h('dt', {}, 'Casos com pendência vencida'), h('dd', { class: 'numero' }, String(valor('EPISODIOS_COM_VENCIDA')))),
      h('h3', {}, 'Por etapa'), tabela('ETAPA', 'Etapa'),
      h('h3', {}, 'Por motivo de bloqueio atual'), tabela('MOTIVO', 'Motivo'),
      h('h3', {}, 'Acima dos limites configurados'), limites);
  }

  // ------------------------------------------------------------ histórico do período
  function historico(r) {
    const hst = r.historico;
    const perm = hst.permanencia;
    const limites = hst.acimaDosLimites.length === 0
      ? mensagem('info', 'Nenhum limite de permanência configurado (regra ativa "tempo total" sem etapa): indicador indisponível.')
      : tabelaSimples(['Limite', 'Encerrados acima', 'Base', '%'], hst.acimaDosLimites.map((l) => [
        `${l.regra.regraNome} (${formatarDuracao(l.regra.limiteMin * 60000)})`, String(l.regra.acima), String(l.regra.populacao),
        l.percentual.valor === null ? `${SEM_DADOS} (base zero)` : `${l.percentual.valor.toFixed(1)}%`]));
    const desfechos = hst.desfechos.length === 0 ? h('p', { class: 'vazio' }, 'Nenhuma saída no período.')
      : tabelaSimples(['Desfecho', 'Saídas'], hst.desfechos.map((d) => [rotulos.desfecho(d.desfecho), String(d.quantidade)]));
    const motivos = hst.motivos.length === 0 ? h('p', { class: 'vazio' }, `Nenhum bloqueio no período (${SEM_DADOS}).`)
      : h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
        h('thead', {}, h('tr', {}, ['Motivo', 'Categoria', 'Tempo bloqueado no período', '% do tempo', 'Intervalos iniciados',
          'Episódios'].map((t) => h('th', { scope: 'col' }, t)))),
        h('tbody', {}, hst.motivos.map((m) => h('tr', {},
          h('td', { 'data-rotulo': 'Motivo' }, m.motivo.descricao || m.motivo.codigo),
          h('td', { 'data-rotulo': 'Categoria' }, rotulos.categoria(m.motivo.categoria)),
          h('td', { 'data-rotulo': 'Tempo bloqueado', class: 'numero' }, formatarDuracao(m.motivo.minutos * 60000)),
          h('td', { 'data-rotulo': '% do tempo' }, m.percentualDoTempo.valor === null ? '—' : [
            h('progress', { max: '100', value: String(Math.round(m.percentualDoTempo.valor)), 'aria-hidden': 'true' }), ' ',
            `${m.percentualDoTempo.valor.toFixed(1)}%`]),
          h('td', { 'data-rotulo': 'Intervalos iniciados', class: 'numero' }, String(m.motivo.inicios)),
          h('td', { 'data-rotulo': 'Episódios', class: 'numero' }, String(m.motivo.episodios)))))));
    const maxDia = Math.max(1, ...hst.volumeDiario.map((d) => Math.max(d.entradas, d.saidas)));
    const volume = h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
      h('thead', {}, h('tr', {}, ['Dia', 'Entradas', 'Saídas'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, hst.volumeDiario.map((d) => h('tr', {},
        h('td', { 'data-rotulo': 'Dia' }, d.dia.split('-').reverse().join('/')),
        h('td', { 'data-rotulo': 'Entradas' }, h('progress', { max: String(maxDia), value: String(d.entradas), 'aria-hidden': 'true' }),
          ` ${d.entradas}`),
        h('td', { 'data-rotulo': 'Saídas' }, h('progress', { max: String(maxDia), value: String(d.saidas), 'aria-hidden': 'true' }),
          ` ${d.saidas}`))))));
    return h('section', { class: 'cartao', 'aria-labelledby': 'tit-hist' },
      h('h2', { id: 'tit-hist' }, 'Histórico do período'),
      h('p', { class: 'discreto' }, `De ${dataBr(r.inicio)} a ${dataBr(r.fim)} (datas locais, fuso ${r.fuso}): `,
        `[${formatarDataHora(r.inicioEm, fuso)}, ${formatarDataHora(r.fimEm, fuso)}). `,
        r.setor ? `Setor: ${(cat.setores.find((s) => s.id === r.setor) || {}).nome || '—'}. ` : 'Todos os setores. ',
        'Calculado sobre todos os registros da unidade.'),
      h('h3', {}, 'Permanência dos episódios encerrados no período'), etiqueta('neutro', 'Proposta (V-09)'),
      h('dl', { class: 'dados' },
        h('dt', {}, 'Encerrados incluídos'), h('dd', { class: 'numero' }, String(perm.incluidos)),
        h('dt', {}, 'Excluídos (encerramento administrativo)'), h('dd', { class: 'numero' }, String(perm.naoIncluidos)),
        h('dt', {}, 'Média'), h('dd', {}, duracao(perm.mediaMin)),
        h('dt', {}, 'Mediana'), h('dd', {}, duracao(perm.medianaMin)),
        h('dt', {}, 'Menor / maior'), h('dd', {}, `${duracao(perm.minimoMin)} / ${duracao(perm.maximoMin)}`)),
      h('h3', {}, 'Encerrados acima dos limites configurados'), limites,
      h('h3', {}, `Saídas por desfecho (transferências: ${hst.transferencias})`), desfechos,
      h('h3', {}, 'Tempos de transferência (reconstruídos da linha do tempo)'),
      tabelaSimples(['Intervalo', 'Episódios', 'Sem marco inicial (dado ausente)', 'Média', 'Mediana'], [
        ['Solicitação → aceite', String(hst.solicitacaoAceite.incluidos), String(hst.solicitacaoAceite.naoIncluidos),
          duracao(hst.solicitacaoAceite.mediaMin), duracao(hst.solicitacaoAceite.medianaMin)],
        ['Aceite → saída', String(hst.aceiteSaida.incluidos), String(hst.aceiteSaida.naoIncluidos),
          duracao(hst.aceiteSaida.mediaMin), duracao(hst.aceiteSaida.medianaMin)]]),
      h('h3', {}, 'Motivos de atraso/gargalo (RF-020)'),
      hst.motivos.length ? h('p', { class: 'discreto' }, `Tempo bloqueado total no período: ${formatarDuracao(hst.minutosBloqueadosTotal * 60000)}.`) : null,
      motivos,
      h('h3', {}, 'Volume diário (tendência)'), volume);
  }

  // ------------------------------------------------------------ dicionário
  async function carregarDicionario() {
    substituir(dicionario, h('h2', { id: 'tit-dic' }, 'Dicionário de cálculo (RF-039)'), carregando());
    try {
      const defs = await ctx.api.obter('/api/indicadores/dicionario');
      if (!ativo) return;
      const linha = (rotulo, texto) => [h('dt', {}, rotulo), h('dd', {}, texto)];
      substituir(dicionario, h('h2', { id: 'tit-dic' }, 'Dicionário de cálculo (RF-039)'),
        h('p', { class: 'discreto' }, 'Como cada número é calculado. Os valores oficiais e as fórmulas institucionais '
          + 'dependem de validação (V-09).'),
        defs.map((d) => h('details', { class: 'cartao' }, h('summary', {}, d.nome, ' ', etiqueta('neutro', 'Proposta')),
          h('dl', { class: 'dados' },
            linha('Requisitos', d.requisitos), linha('Finalidade', d.finalidade), linha('Fórmula', d.formula),
            linha('Unidade de medida', d.unidadeMedida), linha('População', d.populacao), linha('Exclusões', d.exclusoes),
            linha('Denominador', d.denominador), linha('Marco inicial', d.marcoInicial), linha('Marco final', d.marcoFinal),
            linha('Evento e campo temporal', d.campoTemporal), linha('Abertos e encerrados', d.abertosEEncerrados),
            linha('Dados ausentes', d.dadosAusentes), linha('Repetições', d.repeticoes),
            linha('Período e fronteiras', d.periodoEFronteiras), linha('Situação', d.situacao)))));
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      substituir(dicionario, h('h2', { id: 'tit-dic' }, 'Dicionário de cálculo (RF-039)'), mensagem('erro', mensagemDeErro(e)));
    }
  }

  calcular();
  carregarDicionario();
  return { desmontar() { ativo = false; }, emEdicao: () => false };
}

function duracao(min) {
  return min === null || min === undefined ? SEM_DADOS : formatarDuracao(min * 60000);
}

function dataBr(iso) {
  return iso ? iso.split('-').reverse().join('/') : '—';
}

function tabelaSimples(cabecalho, linhas) {
  return h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
    h('thead', {}, h('tr', {}, cabecalho.map((t) => h('th', { scope: 'col' }, t)))),
    h('tbody', {}, linhas.map((l) => h('tr', {}, l.map((v, i) => h('td', { 'data-rotulo': cabecalho[i] }, v)))))));
}
