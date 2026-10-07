// Tela de entrada. A senha só existe no campo e na requisição: nunca é guardada.

import { h, substituir, campo, mensagem } from '../nucleo/dom.js';
import { criarFormulario } from '../nucleo/formulario.js';
import { ErroApi } from '../nucleo/api.js';

export function montar(raiz, ctx, { aviso, aoEntrar }) {
  const login = h('input', { type: 'text', name: 'login', autocomplete: 'username', required: true, maxlength: '64',
    autocapitalize: 'none', spellcheck: 'false' });
  const senha = h('input', { type: 'password', name: 'senha', autocomplete: 'current-password', required: true,
    maxlength: '128' });
  const form = criarFormulario({
    rotulo: 'Entrar',
    rotuloAcessivel: 'Entrar no sistema',
    campos: [campo('Usuário', login), campo('Senha', senha)],
    enviar: async () => {
      const sessao = await ctx.api.criar('/api/sessao', { login: login.value.trim(), senha: senha.value },
        { login: true });
      senha.value = '';
      await aoEntrar(sessao);
    },
    traduzirErro: (e) => {
      senha.value = '';
      if (e instanceof ErroApi && e.status === 401) return 'Usuário ou senha inválidos.';
      if (e instanceof ErroApi && e.status === 429) return 'Muitas tentativas. Aguarde alguns minutos antes de tentar de novo.';
      return null;
    },
  });
  substituir(raiz, h('section', { class: 'cartao estreito' },
    h('h1', {}, 'Fluxo Saúde — entrar'),
    aviso ? mensagem('info', aviso) : null,
    form.el,
    h('p', { class: 'discreto' }, 'Uso restrito a profissionais autorizados. Os acessos são registrados.')));
  setTimeout(() => login.focus(), 0);
  return { desmontar() { senha.value = ''; }, emEdicao: () => false };
}
