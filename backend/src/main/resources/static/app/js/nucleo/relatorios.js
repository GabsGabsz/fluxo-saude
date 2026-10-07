// Relatórios gerenciais (issue #9): apresentação e CSV a partir do MESMO resultado devolvido pelo
// servidor (nenhum recálculo). As DEFINIÇÕES dos cálculos e as limitações vêm dentro do próprio
// resultado (cobertas pela assinatura): tela, impressão e CSV não dependem de outra consulta. Funções puras — a tela, a versão de impressão e o CSV usam as mesmas
// definições de seção, rótulos e cálculos derivados (percentual, variação), então representam o mesmo
// conjunto de dados. Nada aqui grava no navegador.

import { formatarDuracao, formatarDataHora } from './tempo.js';
import * as rotulos from './rotulos.js';

export const SEM_DADOS = 'sem dados';

const tipoResponsavel = (v) => ({ USUARIO: 'Profissional', SETOR: 'Setor', PERFIL: 'Perfil' }[v] || v || '—');
const situacaoEncerrada = (v) => rotulos.statusPendencia(v);

/** Percentual parte ÷ base × 100; ausente com base zero ou ausente (nunca divide por zero). */
export function percentual(parte, base) {
  if (parte === null || parte === undefined || !base) return null;
  return (100 * parte) / base;
}

export const fmtPct = (v) => (v === null || v === undefined ? SEM_DADOS : `${v.toFixed(1).replace('.', ',')}%`);
export const fmtMin = (v) => (v === null || v === undefined ? SEM_DADOS : formatarDuracao(v * 60000));
export const fmtN = (v) => (v === null || v === undefined ? SEM_DADOS : String(v));

// Colunas: [título, função(linha, ctx) → texto]
const n = (titulo, campo) => [titulo, (l) => fmtN(l[campo])];
const m = (titulo, campo) => [titulo, (l) => fmtMin(l[campo])];
const pct = (titulo) => [titulo, (l) => fmtPct(percentual(l.parte, l.base))];
const pctQ = (titulo) => [titulo, (l) => fmtPct(percentual(l.quantidade, l.base))];
const grupo = (titulo, f) => [titulo, (l, c) => f(l, c)];

const rotuloOuChave = (l) => l.rotulo || l.chave || '—';
const etapa = grupo('Etapa', rotuloOuChave);
const setor = grupo('Setor', rotuloOuChave);
const categoria = grupo('Categoria', (l) => rotulos.categoria(l.chave));
const regra = grupo('Regra (versão)', (l) => `${l.rotulo || 'Regra'} (${l.grupo || '—'})`);
const estatisticas = [m('Mediana', 'mediana'), m('P90', 'p90'), m('Máximo', 'maximo')];

/**
 * Seções exibidas por tipo de relatório. {total} = seção sem chave (uma linha); {chave} = uma linha por
 * grupo. A ordem aqui é a ordem na tela, na impressão e no CSV.
 */
export const SECOES = {
  RESUMO: [
    { secao: 'ENTRADAS', titulo: 'Entradas no período', colunas: [n('Entradas', 'quantidade'), n('Sem setor de entrada registrado', 'parte')] },
    { secao: 'ENCERRAMENTOS', titulo: 'Encerramentos no período, por desfecho', colunas: [grupo('Desfecho', (l) => rotulos.desfecho(l.chave)), n('Encerramentos', 'quantidade')], total: 'ENCERRAMENTOS_TOTAL' },
    { secao: 'ABERTOS', titulo: 'Casos abertos no instante de referência (estoque)', colunas: [n('Abertos', 'quantidade')] },
    { secao: 'ABERTOS_ETAPA', titulo: 'Abertos por etapa atual', colunas: [etapa, n('Abertos', 'quantidade'), pctQ('% dos abertos')] },
    { secao: 'ABERTOS_SETOR', titulo: 'Abertos por setor atual', colunas: [setor, n('Abertos', 'quantidade'), pctQ('% dos abertos')] },
    { secao: 'BLOQUEADOS_AGORA', titulo: 'Abertos com bloqueio registrado', colunas: [n('Com bloqueio', 'quantidade'), n('Abertos', 'base'), pctQ('%')] },
    { secao: 'PENDENCIAS_ABERTAS', titulo: 'Pendências abertas', colunas: [n('Abertas', 'quantidade'), n('Vencidas', 'parte'), n('Casos com pendência', 'episodios')] },
    { secao: 'CASOS_COM_VENCIDA', titulo: 'Casos com pendência vencida', colunas: [n('Casos', 'quantidade'), n('Abertos', 'base'), pctQ('%')] },
    { secao: 'CASOS_EM_ALERTA', titulo: 'Casos em alerta operacional agora', colunas: [n('Casos em alerta', 'quantidade'), n('Abertos', 'base'), pctQ('%')] },
    { secao: 'ALERTA_REGRA', titulo: 'Casos em alerta por regra (agora)', colunas: [regra, n('Casos', 'quantidade'), n('Abertos', 'base'), pctQ('%')] },
  ],
  GARGALOS: [
    { secao: 'ETAPA_CONCLUIDA', titulo: 'Duração das etapas CONCLUÍDAS no período', colunas: [etapa, n('Intervalos', 'quantidade'), n('Episódios', 'episodios'), ...estatisticas, m('Média', 'media')] },
    { secao: 'ETAPA_EM_CURSO', titulo: 'Idade das etapas EM CURSO (espera que ainda não terminou)', colunas: [etapa, n('Casos', 'quantidade'), ...estatisticas] },
    { secao: 'SETOR_TEMPO', titulo: 'Tempo nos setores durante o período (pela linha do tempo)', colunas: [setor, m('Tempo no período', 'minutos'), n('Estadias', 'quantidade'), n('Episódios', 'episodios')] },
    { secao: 'BLOQUEIO_CATEGORIA', titulo: 'Tempo bloqueado por categoria (registrada na época)', colunas: [categoria, m('Tempo no período', 'minutos'), grupo('% do tempo bloqueado', (l, c) => fmtPct(percentual(l.minutos, c.somaMinutos))), n('Intervalos com tempo', 'quantidade'), n('Iniciados no período', 'parte'), n('Episódios', 'episodios')] },
    { secao: 'BLOQUEIO_MOTIVO', titulo: 'Motivos registrados (não são causa comprovada)', colunas: [grupo('Motivo', rotuloOuChave), grupo('Categoria', (l) => rotulos.categoria(l.grupo)), m('Tempo no período', 'minutos'), n('Intervalos com tempo', 'quantidade'), n('Iniciados no período', 'parte'), n('Episódios', 'episodios')] },
    { secao: 'BLOQUEIO_CONCLUIDO', titulo: 'Duração dos bloqueios CONCLUÍDOS no período', colunas: [categoria, n('Bloqueios', 'quantidade'), ...estatisticas] },
    { secao: 'BLOQUEIO_EM_CURSO', titulo: 'Idade dos bloqueios EM CURSO', colunas: [categoria, n('Casos', 'quantidade'), ...estatisticas] },
    { secao: 'PENDENCIA_CATEGORIA', titulo: 'Pendências abertas por categoria', colunas: [categoria, n('Abertas', 'quantidade'), n('Vencidas', 'parte'), m('Idade mediana', 'mediana'), m('P90', 'p90')] },
    { secao: 'PENDENCIA_RESPONSAVEL', titulo: 'Pendências abertas por tipo de responsável', colunas: [grupo('Responsável', (l) => tipoResponsavel(l.chave)), n('Abertas', 'quantidade'), n('Vencidas', 'parte'), m('Idade mediana', 'mediana')] },
  ],
  PENDENCIAS: [
    { secao: 'CRIADAS_TOTAL', titulo: 'Pendências criadas no período', colunas: [n('Criadas', 'quantidade'), n('Com prazo alterado depois', 'parte'), n('Casos', 'episodios')] },
    { secao: 'CRIADAS', titulo: 'Criadas por categoria', colunas: [categoria, n('Criadas', 'quantidade')] },
    { secao: 'ENCERRADAS', titulo: 'Encerradas no período, por situação', colunas: [grupo('Situação', (l) => situacaoEncerrada(l.chave)), n('Encerradas', 'quantidade'), n('Até o prazo', 'parte'), pct('% até o prazo'), m('Tempo até encerrar (mediana)', 'mediana'), m('P90', 'p90')] },
    { secao: 'ENCERRADAS_CATEGORIA', titulo: 'Encerradas por categoria', colunas: [categoria, n('Encerradas', 'quantidade'), n('Até o prazo', 'parte'), pct('% até o prazo'), m('Tempo até encerrar (mediana)', 'mediana')] },
    { secao: 'ABERTAS_TOTAL', titulo: 'Abertas no instante de referência', colunas: [n('Abertas', 'quantidade'), n('Vencidas', 'parte'), m('Idade mediana', 'mediana'), m('P90', 'p90'), m('Máximo', 'maximo')] },
    { secao: 'ABERTAS', titulo: 'Abertas por categoria', colunas: [categoria, n('Abertas', 'quantidade'), n('Vencidas', 'parte'), m('Idade mediana', 'mediana'), m('P90', 'p90')] },
    { secao: 'ABERTAS_CRITICIDADE', titulo: 'Abertas por criticidade operacional', colunas: [grupo('Criticidade', (l) => rotulos.criticidade(l.chave)), n('Abertas', 'quantidade'), n('Vencidas', 'parte')] },
    { secao: 'ABERTAS_RESPONSAVEL', titulo: 'Abertas por tipo de responsável', colunas: [grupo('Responsável', (l) => tipoResponsavel(l.chave)), n('Abertas', 'quantidade'), n('Vencidas', 'parte')] },
    { secao: 'VENCIDAS_ATRASO', titulo: 'Atraso das pendências vencidas', colunas: [n('Vencidas', 'quantidade'), ...estatisticas] },
  ],
  QUALIDADE: [
    { secao: 'ATUALIDADE', titulo: 'Tempo desde o último registro (abertos)', colunas: [n('Com registro', 'quantidade'), n('Abertos', 'base'), ...estatisticas] },
    { secao: 'ATUALIDADE_SETOR', titulo: 'Tempo desde o último registro, por setor atual', colunas: [setor, n('Com registro', 'quantidade'), n('Abertos', 'base'), m('Mediana', 'mediana'), m('P90', 'p90')] },
    { secao: 'SEM_ATUALIZACAO_REGRA', titulo: 'Abertos além do critério "sem atualização" CONFIGURADO', colunas: [regra, m('Limite configurado', 'minutos'), n('Além do limite', 'quantidade'), n('Sujeitos à regra', 'base'), pctQ('%')] },
    { secao: 'REGISTROS_RETROATIVOS', titulo: 'Registros feitos no período e retroativos', colunas: [n('Registros', 'quantidade'), n('Retroativos (ajuste manual)', 'parte'), pct('%'), m('Atraso mediano dos retroativos', 'mediana'), m('P90', 'p90')] },
    { secao: 'CAUSA_EM_INVESTIGACAO_AGORA', titulo: 'Bloqueados agora com causa ainda em investigação', colunas: [n('Causa em investigação', 'quantidade'), n('Bloqueados', 'base'), pctQ('%')] },
    { secao: 'BLOQUEIOS_INICIADOS_SEM_CAUSA', titulo: 'Bloqueios iniciados no período sem causa definida', colunas: [n('Sem causa definida', 'quantidade'), n('Iniciados', 'base'), pctQ('%')] },
    { secao: 'SETOR_NAO_ATRIBUIDO', soComFiltroDeSetor: true, titulo: 'Fatos sem setor determinável (fora do filtro de setor)', colunas: [grupo('Fatos', (l) => ({ REGISTROS: 'Registros', BLOQUEIOS_INICIADOS: 'Bloqueios iniciados' }[l.chave] || l.chave)), n('Sem setor da época', 'quantidade'), n('Todos do período', 'base')] },
    { secao: 'DESTINO_EM_TRANSFERENCIA', titulo: 'Destino registrado nos casos em transferência (campo opcional)', colunas: [n('Com destino', 'quantidade'), n('Em transferência', 'base'), pctQ('%')] },
    { secao: 'PROTOCOLO_EM_TRANSFERENCIA', titulo: 'Protocolo registrado nos casos aceitos/em transporte (opcional fora das etapas que o exigem)', colunas: [n('Com protocolo', 'quantidade'), n('Base', 'base'), pctQ('%')] },
    { secao: 'LINHA_DO_TEMPO', titulo: 'Cobertura da linha do tempo', colunas: [n('Com linha do tempo', 'quantidade'), n('Episódios no escopo', 'base'), pctQ('%')] },
  ],
};

/**
 * Métricas da evolução: [título, seção, função(linha) → número ou nulo, formato]. Unidades: 'n' =
 * contagem, 'min' = minutos, 'pct' = percentual (variação absoluta em PONTOS PERCENTUAIS; variação
 * relativa não se aplica).
 */
export const METRICAS_EVOLUCAO = [
  ['Entradas', 'ENTRADAS', (l) => l.quantidade, 'n'],
  ['Encerramentos', 'ENCERRAMENTOS', (l) => l.quantidade, 'n'],
  ['Transferências', 'ENCERRAMENTOS', (l) => l.parte, 'n'],
  ['Permanência mediana (encerrados, sem encerramento administrativo)', 'PERMANENCIA', (l) => l.mediana, 'min'],
  ['Encerrados na permanência (n)', 'PERMANENCIA', (l) => l.quantidade, 'n'],
  ['Pendências criadas', 'PENDENCIAS_CRIADAS', (l) => l.quantidade, 'n'],
  ['Pendências encerradas', 'PENDENCIAS_ENCERRADAS', (l) => l.quantidade, 'n'],
  ['% encerradas até o (último) prazo', 'PENDENCIAS_ENCERRADAS', (l) => percentual(l.parte, l.base), 'pct'],
  ['Tempo bloqueado no período', 'BLOQUEIO_MINUTOS', (l) => l.minutos, 'min'],
  ['Bloqueios iniciados', 'BLOQUEIO_MINUTOS', (l) => l.parte, 'n'],
];

export const UNIDADE_VALOR = { n: 'contagem', min: 'minutos', pct: 'percentual (%)' };
export const UNIDADE_VARIACAO = { n: 'contagem', min: 'minutos', pct: 'pontos percentuais (p.p.)' };

const fmtPor = { n: fmtN, min: fmtMin, pct: fmtPct };

/** Variação absoluta e relativa (relativa ausente com base anterior zero ou ausente). */
export function variacao(anterior, atual) {
  if (anterior === null || anterior === undefined || atual === null || atual === undefined) return { abs: null, pct: null };
  return { abs: atual - anterior, pct: anterior === 0 ? null : (100 * (atual - anterior)) / anterior };
}

function linhasDa(resultado, secao, periodo) {
  return resultado.linhas.filter((l) => l.secao === secao && (periodo === undefined || l.periodo === periodo));
}

/** Linhas da comparação entre períodos (mesma função na tela e no CSV). */
export function comparacao(resultado) {
  const uma = (secao, periodo) => linhasDa(resultado, secao, periodo).find((l) => l.chave === null) || null;
  const comUnidades = (l) => ({ ...l, unidade: UNIDADE_VALOR[l.formato], unidadeVariacao: UNIDADE_VARIACAO[l.formato],
    // métrica que já é percentual: a variação relativa (% de %) não se aplica
    ...(l.formato === 'pct' ? { pct: null, relativaNaoSeAplica: true } : {}) });
  const linhas = METRICAS_EVOLUCAO.map(([titulo, secao, f, formato]) => {
    const a = uma(secao, 'ANTERIOR');
    const b = uma(secao, 'ATUAL');
    const va = a ? f(a) : null;
    const vb = b ? f(b) : null;
    return comUnidades({ titulo, secao, formato, anterior: va, atual: vb, ...variacao(va, vb) });
  });
  const cats = [...new Set(linhasDa(resultado, 'BLOQUEIO_MINUTOS_CATEGORIA').map((l) => l.chave))].sort();
  for (const c of cats) {
    const a = linhasDa(resultado, 'BLOQUEIO_MINUTOS_CATEGORIA', 'ANTERIOR').find((l) => l.chave === c);
    const b = linhasDa(resultado, 'BLOQUEIO_MINUTOS_CATEGORIA', 'ATUAL').find((l) => l.chave === c);
    const va = a ? a.minutos : 0;
    const vb = b ? b.minutos : 0;
    linhas.push(comUnidades({ titulo: `Tempo bloqueado — ${rotulos.categoria(c)}`, secao: 'BLOQUEIO_MINUTOS_CATEGORIA', formato: 'min',
      anterior: va, atual: vb, ...variacao(va, vb) }));
  }
  return linhas;
}

export function textoComparacao(l) {
  const f = fmtPor[l.formato];
  const sinal = (v) => (v > 0 ? '+' : '');
  return {
    anterior: f(l.anterior),
    atual: f(l.atual),
    abs: l.abs === null ? SEM_DADOS : (l.formato === 'min' ? `${l.abs < 0 ? '−' : '+'}${formatarDuracao(Math.abs(l.abs) * 60000)}`
      : `${sinal(l.abs)}${l.formato === 'pct' ? `${l.abs.toFixed(1).replace('.', ',')} p.p.` : l.abs}`),
    pct: l.relativaNaoSeAplica ? 'não se aplica' : (l.pct === null ? SEM_DADOS : `${sinal(l.pct)}${l.pct.toFixed(1).replace('.', ',')}%`),
    unidade: l.unidade,
    unidadeVariacao: l.unidadeVariacao,
  };
}

/** Definições usadas pelo relatório (do PRÓPRIO resultado), na ordem em que aparecem. */
export function definicoes(resultado) {
  return Array.isArray(resultado.definicoes) ? resultado.definicoes : [];
}

/** Definição que explica uma seção, a partir do próprio resultado (sem consultar o dicionário). */
export function definicaoDaSecao(resultado, secao) {
  const codigo = (resultado.verbetes || {})[secao] || secao;
  return definicoes(resultado).find((d) => d.codigo === codigo) || null;
}

/** Campos de uma definição exibidos/exportados (mesma ordem na tela, na impressão e no CSV). */
export const CAMPOS_DEFINICAO = [
  ['Fórmula', 'formula'], ['Unidade de medida', 'unidadeMedida'], ['População', 'populacao'], ['Exclusões', 'exclusoes'],
  ['Denominador', 'denominador'], ['Marco inicial', 'marcoInicial'], ['Marco final', 'marcoFinal'],
  ['Fonte e campo temporal', 'campoTemporal'], ['Abertos e encerrados', 'abertosEEncerrados'], ['Dados ausentes', 'dadosAusentes'],
  ['Repetições', 'repeticoes'], ['Período e fronteiras', 'periodoEFronteiras'], ['Situação', 'situacao'],
];

/** Texto da comparação de períodos (datas e duração real de cada um). */
export function textoComparado(r) {
  const c = r.comparacao;
  if (!c) return null;
  const h = (v) => (v === null || v === undefined ? '?' : String(v).replace('.', ','));
  const dur = c.horasAtual === c.horasAnterior ? `mesma duração: ${h(c.horasAtual)} h`
    : `duração diferente por horário de verão: anterior ${h(c.horasAnterior)} h, atual ${h(c.horasAtual)} h`;
  return `${c.inicio} a ${c.fim} (período anterior com o mesmo número de dias locais; ${dur}; só períodos encerrados)`;
}

/** Tabelas de um resultado (tela e impressão). Seções sem linhas aparecem como "sem dados". */
export function tabelas(resultado) {
  const comSetor = Boolean(resultado.filtros && resultado.filtros.setor);
  const defs = (SECOES[resultado.tipo] || []).filter((d) => !d.soComFiltroDeSetor || comSetor);
  return defs.map((d) => {
    let linhas = linhasDa(resultado, d.secao);
    if (d.total) linhas = linhas.concat(linhasDa(resultado, d.total).map((l) => ({ ...l, rotulo: 'Total' })));
    const somaMinutos = linhas.reduce((s, l) => s + (l.minutos || 0), 0);
    const ctx = { somaMinutos };
    return {
      secao: d.secao,
      titulo: d.titulo,
      verbete: (resultado.verbetes || {})[d.secao] || d.secao,
      cabecalho: d.colunas.map((c) => c[0]),
      linhas: linhas.map((l) => d.colunas.map((c) => (d.total && l.rotulo === 'Total' && c === d.colunas[0] ? 'Total' : c[1](l, ctx)))),
      vazia: linhas.length === 0,
      limitacoes: (resultado.limitacoes || []).filter((x) => x.secao === d.secao),
    };
  });
}

// ------------------------------------------------------------------ CSV
// Separador ';', decimal ',', UTF-8 com BOM e CRLF (abre corretamente em planilhas em português).
// Texto SEMPRE entre aspas (aspas internas duplicadas). Proteção contra fórmula (CSV injection):
// texto cujo primeiro caractere não branco seja = + - @ (inclusive as variantes de largura total),
// tabulação ou retorno recebe um apóstrofo à frente. Números são escritos como números (sem
// apóstrofo, inclusive negativos), para continuarem calculáveis.

const PERIGOSO = /^[\s ]*[=+\-@＝＋－＠\t\r]/;

export function celulaTexto(v) {
  if (v === null || v === undefined) return '""';
  let s = String(v);
  if (PERIGOSO.test(s)) s = `'${s}`;
  return `"${s.replace(/"/g, '""')}"`;
}

export function celulaNumero(v) {
  if (v === null || v === undefined || Number.isNaN(v)) return '';
  if (typeof v !== 'number') return celulaTexto(v);
  const t = Number.isInteger(v) ? String(v) : (Math.round(v * 100) / 100).toString();
  return t.replace('.', ',');
}

const linhaCsv = (celulas) => celulas.join(';');

/** CSV completo do resultado exibido (mesmo conjunto: metadados, dados, lista, limitações). */
export function gerarCsv(r) {
  const out = [];
  const meta = (k, v) => out.push(linhaCsv([celulaTexto(k), celulaTexto(v)]));
  meta('Relatório', r.titulo);
  meta('Unidade', r.unidadeNome);
  meta('Período (datas locais)', `${r.inicio} a ${r.fim}`);
  meta('Intervalo', `[${r.inicioEm}, ${r.fimEm})`);
  meta('Fuso horário', r.fuso);
  if (r.comparacao) meta('Comparado com', textoComparado(r));
  meta('Filtros', filtrosTexto(r));
  meta('Instante de referência', r.referencia);
  meta('Gerado em', r.geradoEm);
  meta('Versão dos cálculos', `${r.versaoCalculo} (fórmulas propostas, V-09; definições no bloco "Definições" abaixo)`);
  meta('Assinatura do conjunto (SHA-256)', r.assinatura);
  meta('Cobertura', r.cobertura);
  meta('Formato', 'CSV com ";" e decimal ","; minutos como número; textos iniciados por = + - @ recebem apóstrofo');
  out.push('');
  out.push(linhaCsv(['Período', 'Seção', 'Título da seção', 'Chave', 'Rótulo', 'Grupo', 'Quantidade', 'Parte', 'Base',
    'Percentual (parte÷base)', 'Episódios', 'Minutos', 'Média (min)', 'Mediana (min)', 'P90 (min)', 'Máximo (min)',
    'Definição (código)'].map(celulaTexto)));
  const titulos = Object.fromEntries((SECOES[r.tipo] || []).map((d) => [d.secao, d.titulo]));
  for (const l of r.linhas) {
    out.push(linhaCsv([celulaTexto(l.periodo), celulaTexto(l.secao), celulaTexto(titulos[l.secao] || l.secao),
      celulaTexto(l.chave), celulaTexto(l.rotulo), celulaTexto(l.grupo), celulaNumero(l.quantidade), celulaNumero(l.parte),
      celulaNumero(l.base), celulaNumero(percentual(l.parte, l.base)), celulaNumero(l.episodios), celulaNumero(l.minutos),
      celulaNumero(l.media), celulaNumero(l.mediana), celulaNumero(l.p90), celulaNumero(l.maximo),
      celulaTexto((r.verbetes || {})[l.secao] || l.secao)]));
  }
  if (r.tipo === 'EVOLUCAO') {
    out.push('');
    out.push(linhaCsv(['Comparação', 'Período anterior', 'Período atual', 'Variação absoluta', 'Variação relativa (%)',
      'Unidade do valor', 'Unidade da variação absoluta', 'Definição (código)'].map(celulaTexto)));
    for (const c of comparacao(r)) {
      out.push(linhaCsv([celulaTexto(c.titulo), celulaNumero(c.anterior), celulaNumero(c.atual), celulaNumero(c.abs),
        c.relativaNaoSeAplica ? celulaTexto('não se aplica') : celulaNumero(c.pct), celulaTexto(c.unidade),
        celulaTexto(c.unidadeVariacao), celulaTexto((r.verbetes || {})[c.secao] || c.secao)]));
    }
  }
  const lista = r.listaPendencias;
  if (lista && lista.itens && lista.itens.length) {
    out.push('');
    out.push(linhaCsv(['Paciente', 'Setor atual', 'Etapa atual', 'Motivo atual', 'Ação registrada', 'Categoria', 'Criticidade',
      'Tipo de responsável', 'Responsável', 'Prazo', 'Vencida'].map(celulaTexto)));
    for (const p of lista.itens) {
      out.push(linhaCsv([p.pacienteNome, p.setorNome, p.etapaNome, p.motivoDescricao, p.descricao, rotulos.categoria(p.categoria),
        rotulos.criticidade(p.criticidade), tipoResponsavel(p.responsavelTipo), p.responsavelNome,
        formatarDataHora(p.prazo, r.fuso), p.vencida ? 'sim' : 'não'].map(celulaTexto)));
    }
  } else if (lista && lista.motivo) {
    out.push('');
    meta('Lista nominal de pendências', lista.motivo);
  }
  out.push('');
  out.push(linhaCsv(['Limitação', 'Seção', 'Descrição'].map(celulaTexto)));
  for (const x of r.limitacoes || []) out.push(linhaCsv([celulaTexto(x.codigo), celulaTexto(x.secao), celulaTexto(x.texto)]));
  // Definições dos cálculos usadas por ESTE resultado (mesmas da tela e da impressão).
  out.push('');
  out.push(linhaCsv([`Definições (${r.versaoCalculo})`, 'Nome', ...CAMPOS_DEFINICAO.map(([t]) => t)].map(celulaTexto)));
  for (const d of definicoes(r)) {
    out.push(linhaCsv([d.codigo, d.nome, ...CAMPOS_DEFINICAO.map(([, k]) => d[k])].map(celulaTexto)));
  }
  return `﻿${out.join('\r\n')}\r\n`;
}

export function filtrosTexto(r) {
  const f = r.filtros || {};
  const partes = [];
  if (f.setor) partes.push(`setor: ${f.setorNome || f.setor}`);
  if (f.etapa) partes.push(`etapa: ${f.etapaNome || f.etapa}`);
  if (f.categoria) partes.push(`categoria: ${rotulos.categoria(f.categoria)}`);
  return partes.length ? partes.join('; ') : 'nenhum (toda a unidade)';
}

export function nomeArquivo(r) {
  const limpo = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  return `relatorio-${limpo(r.tipo)}-${limpo(r.unidadeNome)}-${r.inicio}_${r.fim}-${String(r.assinatura).slice(0, 8)}.csv`;
}
