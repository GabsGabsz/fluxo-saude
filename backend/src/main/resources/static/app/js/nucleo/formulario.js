// Formulário de ESCRITA padronizado:
//  - um envio por vez (botão desabilitado e aria-busy durante o envio; sem duplo clique);
//  - nunca reenvia sozinho: em 409 informa que o registro mudou e oferece "Recarregar";
//  - marca "em edição" (sujo) ao digitar, para a atualização automática não apagar o que foi digitado;
//  - mensagens em região anunciada a leitores de tela.

import { h, substituir, mensagem } from './dom.js';
import { mensagemDeErro, ErroApi } from './api.js';

/**
 * @param {object} o
 * @param {string} o.rotulo texto do botão de envio
 * @param {Array<Node>} o.campos conteúdo do formulário
 * @param {() => Promise<string|void>} o.enviar executa a escrita; retorna a mensagem de sucesso
 * @param {() => void} [o.recarregar] oferecido ao usuário em conflito (409) ou alerta alterado
 * @param {(e: Error) => string|null} [o.traduzirErro] mensagem específica para um erro (ou null)
 * @param {string} [o.classeBotao]
 */
export function criarFormulario({ rotulo, campos, enviar, recarregar, traduzirErro, classeBotao, rotuloAcessivel }) {
  let enviando = false;
  let sujo = false;
  const situacao = h('div', { class: 'situacao-formulario', 'aria-live': 'polite' });
  const botao = h('button', { type: 'submit', class: classeBotao }, rotulo);
  const form = h('form', { novalidate: true, 'aria-label': rotuloAcessivel, aoEnviar: aoEnviar }, campos,
    h('div', { class: 'acoes' }, botao), situacao);
  form.addEventListener('input', () => { sujo = true; });
  form.addEventListener('change', () => { sujo = true; });

  async function aoEnviar(evento) {
    evento.preventDefault();
    if (enviando) return;
    if (!form.checkValidity()) {
      form.reportValidity();
      return;
    }
    enviando = true;
    botao.disabled = true;
    form.setAttribute('aria-busy', 'true');
    substituir(situacao, h('p', { class: 'carregando', role: 'status' }, 'Enviando…'));
    try {
      const sucesso = await enviar();
      sujo = false;
      substituir(situacao, sucesso ? mensagem('sucesso', sucesso) : null);
    } catch (e) {
      if (e && e.name === 'RespostaDescartada') { substituir(situacao); return; }
      const especifica = traduzirErro ? traduzirErro(e) : null;
      const texto = especifica || mensagemDeErro(e);
      const conflito = e instanceof ErroApi && e.status === 409;
      substituir(situacao, mensagem('erro', texto),
        conflito && recarregar ? h('button', { type: 'button', class: 'botao-secundario', aoClicar: recarregar },
          'Recarregar dados') : null);
    } finally {
      enviando = false;
      botao.disabled = false;
      form.removeAttribute('aria-busy');
    }
  }

  return {
    el: form,
    sujo: () => sujo || enviando,
    enviando: () => enviando,
    contemFoco: () => form.contains(document.activeElement),
    mostrar(tipo, texto) { substituir(situacao, mensagem(tipo, texto)); },
  };
}

/** Valor de texto aparado ou null (campos opcionais). */
export function textoOuNulo(el) {
  const v = (el.value || '').trim();
  return v === '' ? null : v;
}
