// DOM mínimo para testar telas reais com node --test (só o que nucleo/dom.js e as telas usam).
// Não é um navegador: serve para conferir O QUE a tela desenha a partir de uma resposta da API.
export class No {
  constructor(tag) { this.tagName = tag; this.filhos = []; this.atributos = {}; this.ouvintes = {}; this.className = ''; this.value = ''; }
  append(...nos) { for (const n of nos) { n.pai = this; this.filhos.push(n); } }
  replaceChildren(...nos) { this.filhos = []; this.append(...nos); }
  setAttribute(n, v) { this.atributos[n] = String(v); if (n === 'id') this.id = String(v); if (n === 'value') this.value = String(v); }
  getAttribute(n) { return this.atributos[n] ?? null; }
  removeAttribute(n) { delete this.atributos[n]; }
  addEventListener(tipo, f) { (this.ouvintes[tipo] ||= []).push(f); }
  contains(n) { for (let x = n; x; x = x.pai) if (x === this) return true; return false; }
  focus() {}
  checkValidity() { return true; }
  reportValidity() {}
  /** Só seletores "tag" e "tag[atributo=\"valor\"]". */
  querySelector(sel) {
    const m = /^(\w+)(?:\[(\w+)="([^"]*)"\])?$/.exec(sel);
    if (!m) throw new Error(`seletor não suportado: ${sel}`);
    return this.todos((n) => n !== this && n.tagName === m[1] && (!m[2] || n.getAttribute(m[2]) === m[3]))[0] || null;
  }
  get textContent() { return this.texto !== undefined ? this.texto : this.filhos.map((f) => f.textContent).join(''); }
  set textContent(t) { this.filhos = []; this.texto = String(t); }
  todos(pred, r = []) { if (pred(this)) r.push(this); this.filhos.forEach((f) => f.todos && f.todos(pred, r)); return r; }
}
class Texto extends No { constructor(t) { super('#text'); this.texto = t; } }
globalThis.Node = No;
globalThis.document = { createElement: (t) => new No(t), createTextNode: (t) => new Texto(t), activeElement: null };

export const esperar = () => new Promise((ok) => setTimeout(ok, 0));
