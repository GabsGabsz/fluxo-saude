// Relatórios gerenciais (extensão aprovada do projeto, issue #9 — não é requisito da ERS original).
// O servidor calcula cada relatório UMA vez (mesmo instantâneo do banco) e devolve o resultado com a
// assinatura do conjunto e um comprovante. A tela, a versão de impressão e o CSV são gerados a partir
// DESSE resultado (nucleo/relatorios.js), sem recalcular: não há como o arquivo divergir do que foi
// visto. Antes de liberar o CSV ou abrir a impressão, a exportação é registrada no servidor (que confere
// o comprovante e a permissão). A impressão/PDF é feita PELO NAVEGADOR (não há PDF gerado no servidor) e
// o sistema não tem como comprovar que ela foi concluída. Nada é guardado no navegador.

import { h, substituir, campo, opcoes, mensagem, carregando, etiqueta } from '../nucleo/dom.js';
import { mensagemDeErro, ErroApi } from '../nucleo/api.js';
import { formatarDataHora, isoParaLocalDaUnidade } from '../nucleo/tempo.js';
import * as rotulos from '../nucleo/rotulos.js';
import { tabelas, comparacao, textoComparacao, gerarCsv, nomeArquivo, filtrosTexto } from '../nucleo/relatorios.js';

const TIPOS = [
  ['RESUMO', 'Resumo gerencial da operação'],
  ['GARGALOS', 'Gargalos por etapa, setor e categoria de bloqueio'],
  ['PENDENCIAS', 'Acompanhamento de pendências'],
  ['EVOLUCAO', 'Evolução entre períodos comparáveis'],
  ['QUALIDADE', 'Qualidade e atualidade dos registros'],
];
const COM_ETAPA = new Set(['GARGALOS']);
const COM_CATEGORIA = new Set(['GARGALOS', 'PENDENCIAS']);

export function montar(raiz, ctx) {
  let ativo = true;
  let atual = null;          // resultado exibido (fonte única de tela, impressão e CSV)
  let dicionario = null;
  const cat = ctx.catalogo();
  const fuso = ctx.fuso();
  const hoje = isoParaLocalDaUnidade(ctx.agora(), fuso).slice(0, 10);
  const seteDias = (() => { const d = new Date(`${hoje}T12:00:00Z`); d.setUTCDate(d.getUTCDate() - 6); return d.toISOString().slice(0, 10); })();

  const tipo = h('select', { required: true }, opcoes(TIPOS.map(([v, r]) => ({ valor: v, rotulo: r }))));
  const inicio = h('input', { type: 'date', required: true, value: seteDias, max: hoje });
  const fim = h('input', { type: 'date', required: true, value: hoje, max: hoje });
  const setor = h('select', {}, opcoes(cat.setores.map((s) => ({ valor: s.id, rotulo: s.nome })), { vazio: 'Todos os setores' }));
  const etapa = h('select', {}, opcoes(cat.etapas.filter((e) => e.natureza !== 'DESFECHO').map((e) => ({ valor: e.id, rotulo: e.nome })),
    { vazio: 'Todas as etapas' }));
  const categoria = h('select', {}, opcoes(rotulos.CATEGORIAS.concat(['NAO_DEFINIDA']).map((c) => ({ valor: c, rotulo: rotulos.categoria(c) })),
    { vazio: 'Todas as categorias' }));
  const campoEtapa = campo('Etapa (só gargalos)', etapa);
  const campoCategoria = campo('Categoria de bloqueio', categoria);
  const situacao = h('div', { 'aria-live': 'polite' });
  const resultado = h('div');
  const dic = h('section', { class: 'cartao nao-imprimir', 'aria-labelledby': 'tit-dic-rel' });
  const botao = h('button', { type: 'submit' }, 'Calcular');
  const form = h('form', { class: 'cartao', 'aria-label': 'Filtros do relatório', aoEnviar: (e) => {
    e.preventDefault();
    if (!form.checkValidity()) { form.reportValidity(); return; }
    calcular();
  } },
  h('div', { class: 'linha' }, campo('Relatório', tipo), campo('Início (data local da unidade)', inicio), campo('Fim (inclusive)', fim)),
  h('div', { class: 'linha' }, campo('Setor', setor), campoEtapa, campoCategoria),
  h('div', { class: 'acoes' }, botao));

  function ajustarFiltros() {
    const t = tipo.value;
    campoEtapa.hidden = !COM_ETAPA.has(t);
    campoCategoria.hidden = !COM_CATEGORIA.has(t);
    if (!COM_ETAPA.has(t)) etapa.value = '';
    if (!COM_CATEGORIA.has(t)) categoria.value = '';
  }
  tipo.addEventListener('change', () => { ajustarFiltros(); atual = null; substituir(resultado); });
  ajustarFiltros();

  substituir(raiz,
    h('h1', {}, 'Relatórios gerenciais'),
    mensagem('info', 'Relatórios OPERACIONAIS sobre o que a equipe registrou na unidade: não representam toda a rede, não '
      + 'trazem conclusão causal, recomendação clínica nem ranking individual. Fórmulas são PROPOSTAS (V-09) — veja o dicionário.'),
    form, situacao, resultado, dic);

  let calculando = false;
  async function calcular() {
    if (calculando) return;
    calculando = true;
    botao.disabled = true;
    form.setAttribute('aria-busy', 'true');
    atual = null;
    substituir(situacao, carregando('Calculando…'));
    substituir(resultado);
    try {
      const q = new URLSearchParams({ inicio: inicio.value, fim: fim.value });
      if (setor.value) q.set('setor', setor.value);
      if (etapa.value && COM_ETAPA.has(tipo.value)) q.set('etapa', etapa.value);
      if (categoria.value && COM_CATEGORIA.has(tipo.value)) q.set('categoria', categoria.value);
      const r = await ctx.api.obter(`/api/relatorios/${tipo.value.toLowerCase()}?${q.toString()}`);
      if (!ativo) return;
      ctx.sincronizar(r.referencia);
      atual = r;
      substituir(situacao);
      desenhar(r);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      if (ctx.tratarErroGlobal(e)) return;
      substituir(situacao, mensagem('erro', mensagemDeErro(e)));
    } finally {
      calculando = false;
      botao.disabled = false;
      form.removeAttribute('aria-busy');
    }
  }

  // ------------------------------------------------------------ exportação (sem recalcular)
  async function exportar(formato, botaoExp) {
    const r = atual;
    if (!r) return;
    botaoExp.disabled = true;
    try {
      // Registro no servidor ANTES de liberar: confere comprovante, usuário, unidade e permissão vigente.
      await ctx.api.criar('/api/relatorios/exportacoes', { comprovante: r.comprovante, formato });
      if (!ativo || atual !== r) return;  // a tela mudou (outro cálculo ou unidade): nada é liberado
      if (formato === 'CSV') {
        baixar(gerarCsv(r), nomeArquivo(r));
        ctx.anunciar('Arquivo CSV gerado a partir do relatório exibido.');
      } else {
        ctx.anunciar('Impressão solicitada ao navegador (a conclusão não é comprovada pelo sistema).');
        window.print();
      }
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      if (ctx.tratarErroGlobal(e)) return;
      substituir(situacao, mensagem('erro', e instanceof ErroApi && e.codigo === 'RELATORIO_EXPIRADO'
        ? `${e.message}` : mensagemDeErro(e)));
    } finally {
      botaoExp.disabled = false;
    }
  }

  function baixar(texto, nome) {
    const url = URL.createObjectURL(new Blob([texto], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = nome;
    a.hidden = true;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  // ------------------------------------------------------------ desenho
  function desenhar(r) {
    const csv = h('button', { type: 'button', class: 'botao-secundario' }, 'Baixar CSV');
    csv.addEventListener('click', () => exportar('CSV', csv));
    const imp = h('button', { type: 'button', class: 'botao-secundario' }, 'Imprimir / salvar em PDF pelo navegador');
    imp.addEventListener('click', () => exportar('IMPRESSAO', imp));
    const geral = (r.limitacoes || []).filter((x) => !x.secao);
    substituir(resultado, h('article', { class: 'relatorio', 'aria-label': 'Relatório' },
      h('section', { class: 'cartao cabecalho-relatorio', 'aria-label': 'Identificação do relatório' },
        h('h2', {}, r.titulo),
        h('dl', { class: 'dados' },
          h('dt', {}, 'Unidade'), h('dd', {}, r.unidadeNome),
          h('dt', {}, 'Período'), h('dd', {}, `${dataBr(r.inicio)} a ${dataBr(r.fim)} (datas locais; intervalo `,
            `[${formatarDataHora(r.inicioEm, fuso)}, ${formatarDataHora(r.fimEm, fuso)}))`),
          r.comparacao ? [h('dt', {}, 'Comparado com'), h('dd', {}, `${dataBr(r.comparacao.inicio)} a ${dataBr(r.comparacao.fim)} `
            + '(período anterior de mesma duração)')] : null,
          h('dt', {}, 'Fuso horário'), h('dd', {}, r.fuso),
          h('dt', {}, 'Filtros'), h('dd', {}, filtrosTexto(r)),
          h('dt', {}, 'Instante de referência'), h('dd', {}, formatarDataHora(r.referencia, fuso), ' (estoque e idades)'),
          h('dt', {}, 'Gerado em'), h('dd', {}, formatarDataHora(r.geradoEm, fuso)),
          h('dt', {}, 'Versão dos cálculos'), h('dd', {}, r.versaoCalculo, ' — fórmulas propostas (V-09)'),
          h('dt', {}, 'Linhas de dados'), h('dd', { class: 'numero' }, String(r.linhas.length)),
          h('dt', {}, 'Assinatura do conjunto'), h('dd', { class: 'assinatura' }, r.assinatura)),
        h('p', { class: 'discreto' }, r.cobertura),
        h('div', { class: 'acoes nao-imprimir' }, imp, csv),
        h('p', { class: 'discreto nao-imprimir' }, 'Impressão e CSV reproduzem exatamente este resultado (mesma assinatura), '
          + 'sem novo cálculo. O PDF é gerado pela impressão do navegador ("Salvar como PDF"); o sistema não gera PDF '
          + 'no servidor nem comprova que a impressão foi concluída.')),
      geral.length ? h('section', { class: 'cartao', 'aria-label': 'Limitações do relatório' }, h('h2', {}, 'Limitações e cobertura'),
        h('ul', {}, geral.map((x) => h('li', { 'data-limitacao': x.codigo }, x.texto)))) : null,
      r.tipo === 'EVOLUCAO' ? secaoEvolucao(r) : tabelas(r).map(secaoTabela),
      r.tipo === 'PENDENCIAS' ? secaoLista(r) : null));
  }

  function secaoTabela(t) {
    const def = verbete(t.verbete);
    return h('section', { class: 'cartao secao-relatorio', 'aria-label': t.titulo, 'data-secao': t.secao },
      h('h3', {}, t.titulo, ' ', etiqueta('neutro', 'Proposta (V-09)')),
      def ? h('p', { class: 'discreto' }, `Cálculo: ${def.formula}. População: ${def.populacao}`) : null,
      t.limitacoes.map((x) => h('p', { class: 'mensagem mensagem-aviso limitacao', role: 'note', 'data-limitacao': x.codigo },
        h('strong', {}, 'Limitação: '), x.texto)),
      t.vazia ? h('p', { class: 'vazio' }, 'Sem dados para este recorte (ausência de registro não é ausência de problema).')
        : tabela(t.cabecalho, t.linhas));
  }

  function secaoEvolucao(r) {
    const linhas = comparacao(r).map((c) => { const t = textoComparacao(c); return [c.titulo, t.anterior, t.atual, t.abs, t.pct]; });
    return h('section', { class: 'cartao secao-relatorio', 'aria-label': 'Comparação entre períodos', 'data-secao': 'EVOLUCAO' },
      h('h3', {}, 'Comparação entre períodos', ' ', etiqueta('neutro', 'Proposta (V-09)')),
      h('p', { class: 'discreto' }, 'Valores absolutos dos dois períodos (mesmas definições, mesmo instantâneo do banco); variação '
        + 'percentual ausente quando o período anterior é zero.'),
      tabela(['Métrica', `Anterior (${dataBr(r.comparacao.inicio)}–${dataBr(r.comparacao.fim)})`,
        `Atual (${dataBr(r.inicio)}–${dataBr(r.fim)})`, 'Variação', 'Variação %'], linhas));
  }

  function secaoLista(r) {
    const l = r.listaPendencias;
    const titulo = 'Pendências abertas — lista operacional (nominal)';
    if (!l || !l.disponivel) {
      return h('section', { class: 'cartao', 'aria-label': titulo }, h('h3', {}, titulo),
        mensagem('info', l && l.motivo ? l.motivo : 'Lista indisponível.'));
    }
    return h('section', { class: 'cartao secao-relatorio', 'aria-label': titulo, 'data-secao': 'LISTA_PENDENCIAS' },
      h('h3', {}, `${titulo} (${l.itens.length})`),
      h('p', { class: 'discreto' }, 'Ordenada por prazo. A ação é a descrição registrada pela equipe; nenhuma ação clínica é inferida. '
        + 'Esta leitura é registrada na auditoria.'),
      l.itens.length === 0 ? h('p', { class: 'vazio' }, 'Nenhuma pendência aberta neste recorte.')
        : h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
          h('thead', {}, h('tr', {}, ['Paciente', 'Setor', 'Etapa', 'Motivo atual', 'Ação registrada', 'Categoria', 'Criticidade',
            'Responsável', 'Prazo', 'Situação'].map((c) => h('th', { scope: 'col' }, c)))),
          h('tbody', {}, l.itens.map((p) => h('tr', { 'data-pendencia': p.pendenciaId },
            h('td', { 'data-rotulo': 'Paciente' }, h('a', { href: `#/episodio/${p.episodioId}` }, p.pacienteNome)),
            h('td', { 'data-rotulo': 'Setor' }, p.setorNome),
            h('td', { 'data-rotulo': 'Etapa' }, p.etapaNome),
            h('td', { 'data-rotulo': 'Motivo atual' }, p.motivoDescricao || '—'),
            h('td', { 'data-rotulo': 'Ação registrada' }, p.descricao),
            h('td', { 'data-rotulo': 'Categoria' }, rotulos.categoria(p.categoria)),
            h('td', { 'data-rotulo': 'Criticidade' }, rotulos.criticidade(p.criticidade)),
            h('td', { 'data-rotulo': 'Responsável' }, p.responsavelTipo === 'PERFIL' ? rotulos.papel(p.responsavelNome)
              : p.responsavelNome || '—'),
            h('td', { 'data-rotulo': 'Prazo' }, formatarDataHora(p.prazo, fuso)),
            h('td', { 'data-rotulo': 'Situação' }, p.vencida ? etiqueta('alerta', 'Vencida') : etiqueta('neutro', 'No prazo'))))))));
  }

  function tabela(cabecalho, linhas) {
    return h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
      h('thead', {}, h('tr', {}, cabecalho.map((c) => h('th', { scope: 'col' }, c)))),
      h('tbody', {}, linhas.map((l) => h('tr', {}, l.map((v, i) => h('td', { 'data-rotulo': cabecalho[i] }, v)))))));
  }

  function verbete(codigo) {
    return dicionario ? dicionario.definicoes.find((d) => d.codigo === codigo) : null;
  }

  async function carregarDicionario() {
    substituir(dic, h('h2', { id: 'tit-dic-rel' }, 'Dicionário dos relatórios'), carregando());
    try {
      dicionario = await ctx.api.obter('/api/relatorios/dicionario');
      if (!ativo) return;
      const linha = (rotulo, texto) => [h('dt', {}, rotulo), h('dd', {}, texto)];
      substituir(dic, h('h2', { id: 'tit-dic-rel' }, `Dicionário dos relatórios (${dicionario.versao})`),
        h('p', { class: 'discreto' }, 'Como cada número é calculado. Extensão aprovada do projeto (issue #9); fórmulas institucionais '
          + 'pendentes de validação (V-09).'),
        dicionario.definicoes.map((d) => h('details', { class: 'cartao' }, h('summary', {}, d.nome, ' ', etiqueta('neutro', 'Proposta')),
          h('dl', { class: 'dados' },
            linha('Fórmula', d.formula), linha('Unidade de medida', d.unidadeMedida), linha('População', d.populacao),
            linha('Exclusões', d.exclusoes), linha('Denominador', d.denominador), linha('Marco inicial', d.marcoInicial),
            linha('Marco final', d.marcoFinal), linha('Fonte e campo temporal', d.campoTemporal),
            linha('Abertos e encerrados', d.abertosEEncerrados), linha('Dados ausentes', d.dadosAusentes),
            linha('Repetições', d.repeticoes), linha('Período e fronteiras', d.periodoEFronteiras), linha('Situação', d.situacao)))));
      if (atual) desenhar(atual);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      substituir(dic, h('h2', { id: 'tit-dic-rel' }, 'Dicionário dos relatórios'), mensagem('erro', mensagemDeErro(e)));
    }
  }

  carregarDicionario();
  return { desmontar() { ativo = false; atual = null; }, emEdicao: () => false };
}

function dataBr(iso) {
  return iso ? iso.split('-').reverse().join('/') : '—';
}
