// Construção de DOM SEGURA: todo dado vira texto (textContent) — nunca HTML. Atributos de
// evento ("on...") e "style" não são aceitos por aqui; links só para rotas internas (#/...).

const PROIBIDOS = /^(on|style$|srcdoc$)/i;

export function h(tag, atributos = {}, ...filhos) {
  const el = document.createElement(tag);
  for (const [nome, valor] of Object.entries(atributos || {})) {
    if (valor === undefined || valor === null || valor === false) continue;
    if (PROIBIDOS.test(nome)) throw new Error(`atributo não permitido: ${nome}`);
    if (nome === 'href' && !String(valor).startsWith('#')) throw new Error('link externo não permitido');
    if (nome === 'class') el.className = valor;
    else if (nome === 'aoClicar') el.addEventListener('click', valor);
    else if (nome === 'aoEnviar') el.addEventListener('submit', valor);
    else if (nome === 'aoMudar') el.addEventListener('change', valor);
    else if (nome === 'aoDigitar') el.addEventListener('input', valor);
    else if (valor === true) el.setAttribute(nome, '');
    else el.setAttribute(nome, String(valor));
  }
  anexar(el, filhos);
  return el;
}

function anexar(el, filhos) {
  for (const f of filhos.flat(Infinity)) {
    if (f === null || f === undefined || f === false) continue;
    el.append(f instanceof Node ? f : document.createTextNode(String(f)));
  }
}

export function substituir(alvo, ...filhos) {
  alvo.replaceChildren();
  anexar(alvo, filhos);
}

let contador = 0;
export const idUnico = (prefixo) => `${prefixo}-${++contador}`;

/** Campo com rótulo associado (acessível). */
export function campo(rotulo, controle, ajuda) {
  const id = controle.id || idUnico('campo');
  controle.id = id;
  const ajudaEl = ajuda ? h('small', { id: `${id}-ajuda`, class: 'ajuda' }, ajuda) : null;
  if (ajudaEl) controle.setAttribute('aria-describedby', ajudaEl.id);
  return h('div', { class: 'campo' }, h('label', { for: id }, rotulo), controle, ajudaEl);
}

export function opcoes(lista, { vazio } = {}) {
  const r = [];
  if (vazio !== undefined) r.push(h('option', { value: '' }, vazio));
  for (const o of lista) r.push(h('option', { value: o.valor }, o.rotulo));
  return r;
}

/** Mensagens em região "status"/"alert" (leitores de tela anunciam). */
export function mensagem(tipo, texto) {
  const icone = { erro: '✖', aviso: '⚠', sucesso: '✔', info: 'ℹ' }[tipo] || '';
  return h('p', { class: `mensagem mensagem-${tipo}`, role: tipo === 'erro' ? 'alert' : 'status' },
    h('span', { 'aria-hidden': 'true' }, `${icone} `), texto);
}

export function carregando(texto = 'Carregando…') {
  return h('p', { class: 'carregando', role: 'status', 'aria-live': 'polite' }, texto);
}

/** Etiqueta de estado: sempre texto + ícone (não depende só de cor). */
export function etiqueta(tipo, texto) {
  const icone = { alerta: '▲', ok: '✓', bloqueio: '■', ciente: '◉', neutro: '•' }[tipo] || '•'; // glifos presentes nas fontes comuns (sem emoji)
  return h('span', { class: `etiqueta etiqueta-${tipo}` }, h('span', { 'aria-hidden': 'true' }, `${icone} `), texto);
}
