// Administração de usuários e lotações da UNIDADE ATIVA (ADR-0006). Sem atualização automática
// (tela de edição). Toda escrita envia a versão lida; 409 => recarregar, nunca sobrescrever.
// A senha provisória aparece UMA vez, só na memória desta tela, e some ao ocultar ou sair.

import { h, substituir, campo, mensagem, carregando, etiqueta } from '../nucleo/dom.js';
import { mensagemDeErro, ErroApi } from '../nucleo/api.js';
import { criarFormulario, textoOuNulo } from '../nucleo/formulario.js';
import * as rotulos from '../nucleo/rotulos.js';

export function montar(raiz, ctx) {
  let ativo = true;
  let formularios = [];      // do editor aberto
  const fixos = [];          // novo usuário e vínculo
  const eu = ctx.estado.sessao().usuarioId;

  const avisoSenha = h('div', { 'aria-live': 'assertive' });
  const situacaoLista = h('div');
  const lista = h('div', {}, carregando());
  const editor = h('div');
  const secNovo = h('section', { class: 'cartao', 'aria-labelledby': 'tit-novo' });
  const secVincular = h('section', { class: 'cartao', 'aria-labelledby': 'tit-vinc' });

  substituir(raiz,
    h('div', { class: 'cabecalho-tela' }, h('h1', {}, 'Usuários da unidade'),
      h('button', { type: 'button', class: 'botao-secundario', aoClicar: () => carregarLista() }, 'Atualizar lista')),
    h('p', { class: 'discreto' }, 'Papéis valem só na unidade ativa. Dados da conta, situação e senha valem em todas as '
      + 'unidades e só podem ser alterados pela unidade gestora da conta. Ninguém altera o próprio acesso por aqui.'),
    avisoSenha, situacaoLista, lista, editor, secNovo, secVincular);

  function mostrarSenha(login, senha) {
    const valor = h('span', { class: 'senha-provisoria' }, senha);
    substituir(avisoSenha, h('div', { class: 'mensagem mensagem-aviso', role: 'alert' },
      h('p', {}, h('strong', {}, `Senha provisória de ${login}: `), valor),
      h('p', {}, 'Ela não será exibida novamente. Entregue-a pessoalmente ou por canal seguro; '
        + 'no primeiro acesso a troca será obrigatória.'),
      h('button', { type: 'button', aoClicar: () => substituir(avisoSenha) }, 'Ocultar senha')));
    avisoSenha.querySelector('button').focus();
  }

  // ------------------------------------------------------------ lista
  async function carregarLista() {
    substituir(situacaoLista);
    try {
      const r = await ctx.api.obter('/api/admin/usuarios');
      if (!ativo) return;
      desenharLista(r);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      if (ctx.tratarErroGlobal(e)) return;
      substituir(situacaoLista, mensagem('erro', mensagemDeErro(e)));
      if (lista.querySelector('.carregando')) substituir(lista);
    }
  }

  function desenharLista(r) {
    if (r.truncado) substituir(situacaoLista, mensagem('aviso', 'Lista truncada: a unidade tem mais usuários do que o limite exibido.'));
    if (r.itens.length === 0) { substituir(lista, h('p', { class: 'vazio' }, 'Nenhum usuário lotado nesta unidade.')); return; }
    substituir(lista, h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
      h('caption', {}, `${r.itens.length} usuário(s)`),
      h('thead', {}, h('tr', {}, ['Usuário', 'Nome', 'Papéis na unidade', 'Situação', 'Ações'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, r.itens.map((u) => h('tr', {},
        h('td', { 'data-rotulo': 'Usuário' }, u.login),
        h('td', { 'data-rotulo': 'Nome' }, u.nome, u.id === eu ? h('span', { class: 'discreto' }, ' (você)') : null),
        h('td', { 'data-rotulo': 'Papéis na unidade' }, u.papeis.map(rotulos.papel).join(', ') || '—'),
        h('td', { 'data-rotulo': 'Situação' },
          u.ativo ? etiqueta('ok', 'Ativa') : etiqueta('neutro', 'Desativada'),
          u.deveTrocarSenha ? etiqueta('neutro', 'Troca de senha pendente') : null,
          u.possuiOutrasUnidades ? etiqueta('neutro', 'Atua em outras unidades') : null),
        h('td', { 'data-rotulo': 'Ações' }, h('button', { type: 'button', class: 'botao-secundario',
          'aria-label': `Gerenciar ${u.login}`, aoClicar: () => abrirEditor(u.id) }, 'Gerenciar'))))))));
  }

  // ------------------------------------------------------------ editor de um usuário
  function checkboxesPapeis(marcados) {
    const caixas = rotulos.PAPEIS.map((p) => h('input', { type: 'checkbox', value: p, checked: marcados.includes(p) }));
    return {
      el: h('fieldset', {}, h('legend', {}, 'Papéis nesta unidade'), h('div', { class: 'opcoes-inline' },
        caixas.map((c) => h('label', {}, c, rotulos.papel(c.value))))),
      valor: () => caixas.filter((c) => c.checked).map((c) => c.value),
    };
  }

  async function abrirEditor(id, aviso) {
    formularios = [];
    substituir(editor, h('section', { class: 'cartao' }, carregando('Carregando usuário…')));
    let u;
    try {
      u = await ctx.api.obter(`/api/admin/usuarios/${id}`);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      substituir(editor, mensagem('erro', mensagemDeErro(e)));
      return;
    }
    if (!ativo) return;
    const titulo = h('h2', { id: 'tit-editor', tabindex: '-1' }, `Gerenciar ${u.login}`);
    const reabrir = (msg) => { abrirEditor(id, typeof msg === 'string' ? msg : null); carregarLista(); };
    if (u.id === eu) {
      substituir(editor, h('section', { class: 'cartao', 'aria-labelledby': 'tit-editor' }, titulo,
        mensagem('info', 'Você não pode alterar o próprio acesso, situação ou dados por aqui. '
          + 'Peça a outro administrador. Sua senha é trocada em "Trocar senha".')));
      titulo.focus();
      return;
    }
    const papeis = checkboxesPapeis(u.papeis);
    const formPapeis = criarFormulario({
      rotulo: 'Salvar papéis', rotuloAcessivel: 'Papéis', recarregar: reabrir,
      campos: [papeis.el, h('p', { class: 'discreto' }, 'Desmarcar todos revoga o acesso nesta unidade.')],
      enviar: async () => {
        const v = papeis.valor();
        if (v.length === 0 && !window.confirm(`Revogar todo o acesso de ${u.login} nesta unidade?`)) {
          throw new ErroApi(0, { detail: 'Operação cancelada.' });
        }
        await ctx.api.substituir(`/api/admin/usuarios/${u.id}/papeis`, { versao: u.versao, papeis: v });
        reabrir(v.length === 0 ? 'Acesso revogado nesta unidade.' : 'Papéis atualizados.');
      },
    });
    formularios.push(formPapeis);
    const blocos = [h('div', {}, h('h3', {}, 'Acesso nesta unidade'), formPapeis.el)];

    if (u.contaGerenciavel) {
      const nome = h('input', { type: 'text', required: true, maxlength: '200', value: u.nome || '' });
      const email = h('input', { type: 'email', maxlength: '254', value: u.email || '' });
      const registro = h('input', { type: 'text', maxlength: '40', value: u.registroProfissional || '' });
      const formConta = criarFormulario({
        rotulo: 'Salvar dados', rotuloAcessivel: 'Dados da conta', recarregar: reabrir,
        campos: [campo('Nome', nome), campo('E-mail', email), campo('Registro profissional', registro)],
        enviar: async () => {
          await ctx.api.substituir(`/api/admin/usuarios/${u.id}/conta`, {
            versao: u.versao, nome: nome.value.trim(), email: textoOuNulo(email), registroProfissional: textoOuNulo(registro) });
          reabrir('Dados atualizados.');
        },
      });
      const formSituacao = criarFormulario({
        rotulo: u.ativo ? 'Desativar conta' : 'Reativar conta', rotuloAcessivel: 'Situação da conta',
        classeBotao: u.ativo ? 'botao-perigo' : undefined, recarregar: reabrir,
        campos: [h('p', {}, u.ativo ? 'Desativar encerra as sessões e bloqueia o acesso em todas as unidades.'
          : 'A conta está desativada.')],
        enviar: async () => {
          if (!window.confirm(`${u.ativo ? 'Desativar' : 'Reativar'} a conta de ${u.login}?`)) {
            throw new ErroApi(0, { detail: 'Operação cancelada.' });
          }
          await ctx.api.substituir(`/api/admin/usuarios/${u.id}/situacao`, { versao: u.versao, ativo: !u.ativo });
          reabrir(u.ativo ? 'Conta desativada.' : 'Conta reativada.');
        },
      });
      const formSenha = criarFormulario({
        rotulo: 'Gerar senha provisória', rotuloAcessivel: 'Senha provisória', classeBotao: 'botao-secundario',
        recarregar: reabrir,
        campos: [h('p', {}, 'Gera nova senha provisória, encerra as sessões do usuário e exige troca no próximo acesso.')],
        enviar: async () => {
          if (!window.confirm(`Gerar nova senha provisória para ${u.login}?`)) throw new ErroApi(0, { detail: 'Operação cancelada.' });
          const r = await ctx.api.criar(`/api/admin/usuarios/${u.id}/senha-provisoria`, { versao: u.versao });
          mostrarSenha(u.login, r.senhaProvisoria);
          reabrir();
        },
      });
      formularios.push(formConta, formSituacao, formSenha);
      blocos.push(h('div', {}, h('h3', {}, 'Dados da conta'), formConta.el),
        h('div', {}, h('h3', {}, 'Situação'), formSituacao.el, h('h3', {}, 'Senha'), formSenha.el));
    } else {
      blocos.push(mensagem('info', 'Conta gerida por outra unidade ou lotada em unidades fora do seu alcance: '
        + 'aqui só é possível alterar os papéis nesta unidade.'));
    }
    substituir(editor, h('section', { class: 'cartao', 'aria-labelledby': 'tit-editor' }, titulo,
      aviso ? mensagem('sucesso', aviso) : null,
      h('p', { class: 'discreto' }, `${u.nome} · versão ${u.versao}`), h('div', { class: 'grade' }, blocos),
      h('div', { class: 'acoes' }, h('button', { type: 'button', class: 'botao-secundario',
        aoClicar: () => { formularios = []; substituir(editor); } }, 'Fechar'))));
    titulo.focus();
  }

  // ------------------------------------------------------------ novo usuário
  const nLogin = h('input', { type: 'text', required: true, maxlength: '64', autocomplete: 'off', autocapitalize: 'none' });
  const nNome = h('input', { type: 'text', required: true, maxlength: '200' });
  const nEmail = h('input', { type: 'email', maxlength: '254' });
  const nRegistro = h('input', { type: 'text', maxlength: '40' });
  const nPapeis = checkboxesPapeis([]);
  const formNovo = criarFormulario({
    rotulo: 'Criar usuário', rotuloAcessivel: 'Novo usuário',
    campos: [h('div', { class: 'linha' }, campo('Login', nLogin), campo('Nome completo', nNome),
      campo('E-mail', nEmail), campo('Registro profissional', nRegistro)), nPapeis.el],
    enviar: async () => {
      const papeis = nPapeis.valor();
      if (papeis.length === 0) throw new ErroApi(0, { detail: 'Marque ao menos um papel.' });
      const login = nLogin.value.trim();
      const r = await ctx.api.criar('/api/admin/usuarios', { login, nome: nNome.value.trim(),
        email: textoOuNulo(nEmail), registroProfissional: textoOuNulo(nRegistro), papeis });
      if (!ativo) return;
      for (const c of [nLogin, nNome, nEmail, nRegistro]) c.value = '';
      mostrarSenha(login, r.senhaProvisoria);
      carregarLista();
    },
  });
  fixos.push(formNovo);
  substituir(secNovo, h('h2', { id: 'tit-novo' }, 'Novo usuário'), formNovo.el);


  // ------------------------------------------------------------ vincular conta existente
  const vLogin = h('input', { type: 'text', required: true, maxlength: '64', autocomplete: 'off', autocapitalize: 'none' });
  const resultadoVinculo = h('div', { 'aria-live': 'polite' });
  const formBusca = criarFormulario({
    rotulo: 'Localizar', rotuloAcessivel: 'Localizar conta por login', classeBotao: 'botao-secundario',
    campos: [campo('Login exato', vLogin, 'Para dar acesso a quem já atua em outra unidade. A busca é auditada.')],
    traduzirErro: (e) => (e instanceof ErroApi && e.status === 404 ? 'Nenhuma conta com esse login.' : null),
    enviar: async () => {
      substituir(resultadoVinculo);
      const l = await ctx.api.obter(`/api/admin/usuarios/busca?login=${encodeURIComponent(vLogin.value.trim())}`);
      if (!ativo) return;
      if (l.lotadoNaUnidade) {
        substituir(resultadoVinculo, mensagem('info', 'Esta conta já tem acesso nesta unidade: use "Gerenciar" na lista.'));
        return;
      }
      if (!l.vinculavel) {
        substituir(resultadoVinculo, mensagem('aviso', 'Esta conta não pode ser vinculada (inativa ou sem lotação ativa).'));
        return;
      }
      const papeis = checkboxesPapeis([]);
      const formVinc = criarFormulario({
        rotulo: 'Conceder acesso', rotuloAcessivel: 'Conceder acesso à conta localizada',
        campos: [papeis.el],
        enviar: async () => {
          const v = papeis.valor();
          if (v.length === 0) throw new ErroApi(0, { detail: 'Marque ao menos um papel.' });
          await ctx.api.substituir(`/api/admin/usuarios/${l.id}/papeis`, { versao: l.versao, papeis: v });
          substituir(resultadoVinculo, mensagem('sucesso', 'Acesso concedido nesta unidade.'));
          carregarLista();
        },
      });
      fixos.push(formVinc);
      substituir(resultadoVinculo, mensagem('info', 'Conta localizada e vinculável. Escolha os papéis nesta unidade.'), formVinc.el);
    },
  });
  fixos.push(formBusca);
  substituir(secVincular, h('h2', { id: 'tit-vinc' }, 'Dar acesso a conta de outra unidade'), formBusca.el, resultadoVinculo);

  carregarLista();
  return {
    desmontar() { ativo = false; substituir(avisoSenha); },
    emEdicao: () => [...formularios, ...fixos].some((f) => f.sujo()),
  };
}
