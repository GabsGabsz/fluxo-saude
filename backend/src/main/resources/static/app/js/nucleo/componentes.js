// Componentes visuais reutilizados pelas telas (sempre via h(): texto, nunca HTML).

import { h, etiqueta } from './dom.js';
import { formatarDuracao, decorrido, formatarDataHora } from './tempo.js';
import * as rotulos from './rotulos.js';

/**
 * Cronômetro: tempo desde um instante registrado no servidor, contado a partir do "agora" do
 * servidor. O main.js atualiza todos os [data-desde] periodicamente (sem nova requisição).
 */
export function cronometro(desdeIso, agoraMs) {
  if (!desdeIso) return h('span', { class: 'numero' }, '—');
  return h('span', { class: 'numero', 'data-desde': desdeIso }, formatarDuracao(decorrido(desdeIso, agoraMs)));
}

export function dataHora(iso, fuso) {
  if (!iso) return h('span', {}, '—');
  return h('time', { datetime: iso, class: 'numero' }, formatarDataHora(iso, fuso));
}

/** Limite de regra em minutos → texto ("2 h 00 min"). */
export function limite(minutos) {
  return minutos === null || minutos === undefined ? 'prazo da própria pendência' : formatarDuracao(minutos * 60000);
}

/** Resumo dos alertas OPERACIONAIS de um caso (texto + ícone; nunca só cor). */
export function resumoAlertas(alertas) {
  if (!alertas || alertas.length === 0) return h('span', { class: 'discreto' }, 'Nenhum');
  const pendentes = alertas.filter((a) => !a.ciente && !a.ciencia).length;
  return h('span', {},
    etiqueta('alerta', `${alertas.length} ${alertas.length === 1 ? 'alerta' : 'alertas'}`),
    pendentes < alertas.length ? etiqueta('ciente', `${alertas.length - pendentes} com ciência`) : null,
    h('span', { class: 'so-leitor' }, `: ${alertas.map((a) => a.regraNome).join('; ')}`),
    h('ul', { class: 'lista-compacta discreto' }, alertas.map((a) => h('li', {}, a.regraNome))));
}

export function bloqueio(linha, agoraMs) {
  if (!linha.bloqueioDesde) return h('span', { class: 'discreto' }, 'Sem bloqueio');
  return h('span', {},
    etiqueta('bloqueio', rotulos.categoria(linha.categoriaBloqueio)),
    h('span', {}, ` ${linha.motivoDescricao || linha.motivoBloqueio || ''} `),
    h('span', { class: 'discreto' }, 'há '), cronometro(linha.bloqueioDesde, agoraMs));
}

/** Aviso fixo: alerta operacional ≠ risco clínico (RN-007, RN-013). */
export function avisoOperacional() {
  return h('p', { class: 'discreto' },
    'Alertas e criticidade aqui são OPERACIONAIS (tempo, pendência, falta de atualização). ',
    'Não indicam risco clínico nem substituem a classificação de risco.');
}

/**
 * Atualização periódica sem perder o foco do teclado: elementos com data-foco são reencontrados
 * pela mesma chave depois que o conteúdo é redesenhado.
 */
export function preservandoFoco(container, redesenhar) {
  const ativo = document.activeElement;
  const chave = ativo && container.contains(ativo) ? ativo.getAttribute('data-foco') : null;
  redesenhar();
  if (chave) {
    const el = container.querySelector(`[data-foco="${CSS.escape(chave)}"]`);
    if (el) el.focus();
  }
}

/** Situação das regras: distingue "nenhuma regra configurada" de "nenhum alerta". */
export function situacaoRegras(regrasAtivas, totalAlertas) {
  if (regrasAtivas === null) return null;
  if (regrasAtivas === 0) {
    return h('p', { class: 'mensagem mensagem-info' }, h('span', { 'aria-hidden': 'true' }, 'ℹ '),
      'Nenhuma regra de alerta ativa nesta unidade: alertas não são calculados. ',
      'A ausência de alertas NÃO significa que não há atraso.');
  }
  return h('p', { class: 'discreto' },
    `${regrasAtivas} ${regrasAtivas === 1 ? 'regra de alerta ativa' : 'regras de alerta ativas'}. `,
    totalAlertas === 0 ? 'Nenhum caso atingiu os limites configurados.' : '');
}

/** Sem região "status": não é reanunciado a cada atualização periódica. */
export function atualizadoEm(agoraIso, fuso, pausado) {
  return h('p', { class: 'discreto' },
    agoraIso ? `Atualizado em ${formatarDataHora(agoraIso, fuso)} (horário do servidor, fuso da unidade). ` : '',
    pausado ? 'Atualização automática pausada enquanto você edita.' : '');
}
