// Configuração das regras de alerta da unidade ativa (ADR-0007). Nenhuma regra vem cadastrada e
// a interface não sugere limites: os valores são decisão institucional. Tipo não muda depois de
// criado; desativa-se em vez de excluir. Escritas enviam a versão lida (409 => recarregar).

import { h, substituir, campo, opcoes, mensagem, carregando, etiqueta } from '../nucleo/dom.js';
import { mensagemDeErro, ErroApi } from '../nucleo/api.js';
import { criarFormulario, textoOuNulo } from '../nucleo/formulario.js';
import * as rotulos from '../nucleo/rotulos.js';
import { limite } from '../nucleo/componentes.js';

const aceitaEtapa = (tipo) => tipo !== 'PENDENCIA_VENCIDA';
const aceitaCategoria = (tipo) => tipo === 'TEMPO_BLOQUEADO' || tipo === 'PENDENCIA_VENCIDA';
const exigeLimite = (tipo) => tipo !== 'PENDENCIA_VENCIDA';

export function montar(raiz, ctx) {
  const cat = ctx.catalogo();
  let ativo = true;
  const formularios = [];
  const situacao = h('div');
  const lista = h('div', {}, carregando());
  const editor = h('div');
  const secNova = h('section', { class: 'cartao', 'aria-labelledby': 'tit-nova' });
  const nomeEtapa = (id) => (cat.etapas.find((e) => e.id === id) || {}).nome || '—';

  substituir(raiz,
    h('div', { class: 'cabecalho-tela' }, h('h1', {}, 'Regras de alerta'),
      h('button', { type: 'button', class: 'botao-secundario', aoClicar: () => carregar() }, 'Atualizar lista')),
    mensagem('info', 'Regras definem alertas OPERACIONAIS (tempo, pendência, falta de atualização), nunca risco clínico. '
      + 'Os limites devem ser definidos pela instituição; o sistema não sugere valores. '
      + 'Alterar uma regra exige nova ciência dos alertas já vistos.'),
    situacao, lista, editor, secNova);

  async function carregar() {
    substituir(situacao);
    try {
      const regras = await ctx.api.obter('/api/config/regras-alerta');
      if (!ativo) return;
      desenhar(regras);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      if (ctx.tratarErroGlobal(e)) return;
      substituir(situacao, mensagem('erro', mensagemDeErro(e)));
      if (lista.querySelector('.carregando')) substituir(lista);
    }
  }

  function desenhar(regras) {
    if (regras.length === 0) {
      substituir(lista, h('p', { class: 'vazio', role: 'status' },
        'Nenhuma regra configurada nesta unidade: nenhum alerta é calculado.'));
      return;
    }
    substituir(lista, h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
      h('caption', {}, `${regras.length} regra(s)`),
      h('thead', {}, h('tr', {}, ['Nome', 'Tipo', 'Etapa', 'Categoria', 'Limite', 'Ação esperada', 'Situação', 'Ações']
        .map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, regras.map((r) => h('tr', {},
        h('td', { 'data-rotulo': 'Nome' }, r.nome),
        h('td', { 'data-rotulo': 'Tipo' }, rotulos.tipoRegra(r.tipo)),
        h('td', { 'data-rotulo': 'Etapa' }, r.etapaId ? nomeEtapa(r.etapaId) : 'Todas'),
        h('td', { 'data-rotulo': 'Categoria' }, r.categoria ? rotulos.categoria(r.categoria) : 'Todas'),
        h('td', { 'data-rotulo': 'Limite' }, limite(r.limiteMinutos)),
        h('td', { 'data-rotulo': 'Ação esperada' }, r.acaoEsperada || '—'),
        h('td', { 'data-rotulo': 'Situação' }, r.ativa ? etiqueta('ok', 'Ativa') : etiqueta('neutro', 'Inativa'),
          h('div', { class: 'discreto' }, `versão ${r.versao}`)),
        h('td', { 'data-rotulo': 'Ações' }, h('button', { type: 'button', class: 'botao-secundario',
          'aria-label': `Editar regra ${r.nome}`, aoClicar: () => abrirEditor(r) }, 'Editar'))))))));
  }

  function camposRegra(tipo, r = {}) {
    const nome = h('input', { type: 'text', required: true, maxlength: '120', value: r.nome || '' });
    // Inclui etapas inativas: a regra existente não pode perder o filtro por não achar a opção.
    const etapa = h('select', {}, opcoes(cat.etapas.filter((e) => !e.desfecho && (e.ativa || e.id === r.etapaId))
      .map((e) => ({ valor: e.id, rotulo: e.ativa ? e.nome : `${e.nome} (inativa)` })), { vazio: 'Todas as etapas' }));
    if (r.etapaId) etapa.value = r.etapaId;
    const categoria = h('select', {}, opcoes(rotulos.CATEGORIAS.map((c) => ({ valor: c, rotulo: rotulos.categoria(c) })),
      { vazio: 'Todas as categorias' }));
    if (r.categoria) categoria.value = r.categoria;
    const minutos = h('input', { type: 'number', min: '1', max: String(30 * 24 * 60), step: '1', inputmode: 'numeric',
      value: r.limiteMinutos ?? '' });
    const acao = h('input', { type: 'text', maxlength: '200', value: r.acaoEsperada || '' });
    const gEtapa = campo('Etapa (opcional)', etapa);
    const gCategoria = campo('Categoria (opcional)', categoria);
    const gMinutos = campo('Limite em minutos', minutos, 'Entre 1 minuto e 30 dias. Valor definido pela instituição.');
    function ajustar(t) {
      for (const [g, visivel] of [[gEtapa, aceitaEtapa(t)], [gCategoria, aceitaCategoria(t)], [gMinutos, exigeLimite(t)]]) {
        g.hidden = !visivel;
        g.querySelector('input, select').disabled = !visivel;
      }
      minutos.required = exigeLimite(t);
    }
    ajustar(tipo);
    return {
      els: [campo('Nome', nome), gEtapa, gCategoria, gMinutos, campo('Ação esperada (opcional)', acao)],
      ajustar,
      valor: (t) => ({
        nome: nome.value.trim(),
        etapaId: aceitaEtapa(t) && etapa.value ? etapa.value : null,
        categoria: aceitaCategoria(t) && categoria.value ? categoria.value : null,
        limiteMinutos: exigeLimite(t) ? Number(minutos.value) : null,
        acaoEsperada: textoOuNulo(acao),
      }),
    };
  }

  function abrirEditor(r) {
    const c = camposRegra(r.tipo, r);
    const ativa = h('input', { type: 'checkbox', checked: r.ativa });
    const titulo = h('h2', { id: 'tit-ed-regra', tabindex: '-1' }, `Editar regra: ${r.nome}`);
    const form = criarFormulario({
      rotulo: 'Salvar regra', rotuloAcessivel: 'Editar regra',
      recarregar: () => { substituir(editor); carregar(); },
      campos: [h('p', {}, `Tipo: ${rotulos.tipoRegra(r.tipo)} (não pode ser alterado) · versão ${r.versao}`), ...c.els,
        h('p', {}, h('label', {}, ativa, ' Regra ativa'))],
      enviar: async () => {
        await ctx.api.substituir(`/api/config/regras-alerta/${r.id}`, { versao: r.versao, ...c.valor(r.tipo), ativa: ativa.checked });
        if (!ativo) return;
        substituir(editor, mensagem('sucesso', 'Regra atualizada.'));
        formularios.length = 0;
        formularios.push(formNova);
        carregar();
      },
    });
    formularios.length = 0;
    formularios.push(formNova, form);
    substituir(editor, h('section', { class: 'cartao', 'aria-labelledby': 'tit-ed-regra' }, titulo, form.el,
      h('div', { class: 'acoes' }, h('button', { type: 'button', class: 'botao-secundario', aoClicar: () => {
        formularios.length = 0; formularios.push(formNova); substituir(editor);
      } }, 'Fechar'))));
    titulo.focus();
  }

  // ------------------------------------------------------------ nova regra
  const tipo = h('select', { required: true }, opcoes(rotulos.TIPOS_REGRA.map((t) => ({ valor: t, rotulo: rotulos.tipoRegra(t) })),
    { vazio: 'Selecione…' }));
  const novos = camposRegra('');
  tipo.addEventListener('change', () => novos.ajustar(tipo.value));
  const formNova = criarFormulario({
    rotulo: 'Criar regra', rotuloAcessivel: 'Nova regra',
    campos: [campo('Tipo', tipo), ...novos.els],
    enviar: async () => {
      if (!tipo.value) throw new ErroApi(0, { detail: 'Escolha o tipo.' });
      await ctx.api.criar('/api/config/regras-alerta', { tipo: tipo.value, ...novos.valor(tipo.value) });
      if (!ativo) return;
      carregar();
      return 'Regra criada.';
    },
  });
  formularios.push(formNova);
  substituir(secNova, h('h2', { id: 'tit-nova' }, 'Nova regra'), formNova.el);

  carregar();
  return { desmontar() { ativo = false; }, emEdicao: () => formularios.some((f) => f.sujo()) };
}
