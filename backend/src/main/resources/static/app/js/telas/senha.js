// Troca da própria senha (obrigatória no primeiro acesso ou voluntária). O servidor encerra
// as outras sessões e rotaciona o token CSRF; aqui só relemos a sessão.

import { h, substituir, campo, mensagem } from '../nucleo/dom.js';
import { criarFormulario } from '../nucleo/formulario.js';
import { ErroApi } from '../nucleo/api.js';

export function montar(raiz, ctx, { obrigatoria = false } = {}) {
  const atual = h('input', { type: 'password', autocomplete: 'current-password', required: true, maxlength: '128' });
  const nova = h('input', { type: 'password', autocomplete: 'new-password', required: true, minlength: '12',
    maxlength: '128' });
  const confirmacao = h('input', { type: 'password', autocomplete: 'new-password', required: true, maxlength: '128' });
  const limpar = () => { atual.value = ''; nova.value = ''; confirmacao.value = ''; };
  const form = criarFormulario({
    rotulo: 'Trocar senha',
    rotuloAcessivel: 'Trocar senha',
    campos: [
      campo('Senha atual', atual),
      campo('Nova senha', nova, 'Mínimo de 12 caracteres. Não use seu nome, seu usuário nem senhas comuns. '
        + 'Uma frase longa é uma boa escolha.'),
      campo('Confirme a nova senha', confirmacao),
    ],
    enviar: async () => {
      if (nova.value !== confirmacao.value) {
        throw new ErroApi(422, { detail: 'A confirmação não confere com a nova senha.' });
      }
      await ctx.api.executar('PUT', '/api/sessao/senha', { senhaAtual: atual.value, novaSenha: nova.value });
      limpar();
      ctx.anunciar('Senha alterada.');
      ctx.notificar('Senha alterada. As outras sessões abertas com o seu usuário foram encerradas.');
      await ctx.recarregarSessao();
    },
    traduzirErro: () => {
      atual.value = '';
      return null; // 422 traz a mensagem da política de senha (ex.: "A senha atual não confere")
    },
  });
  substituir(raiz, h('section', { class: 'cartao estreito' },
    h('h1', {}, obrigatoria ? 'Troca de senha obrigatória' : 'Trocar senha'),
    obrigatoria ? mensagem('aviso', 'Antes de continuar, defina uma nova senha pessoal.') : null,
    form.el,
    obrigatoria ? h('div', { class: 'acoes' }, h('button', { type: 'button', class: 'botao-secundario',
      aoClicar: async () => {
        try { await ctx.api.remover('/api/sessao', undefined, { semGeracao: true }); } catch { /* segue */ }
        ctx.estado.limpar();
        ctx.recarregarSessao();
      } }, 'Sair sem trocar')) : null));
  return { desmontar: limpar, emEdicao: () => form.sujo() };
}
