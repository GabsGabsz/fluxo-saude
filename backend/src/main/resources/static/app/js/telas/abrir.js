// Abertura de episódio (RF-002/003): localizar o paciente por CNS ou identificador institucional
// (busca exata, auditada no servidor) ou cadastrar um novo; escolher o setor; horário retroativo
// opcional (com permissão e justificativa). Possível duplicidade exige justificativa explícita.

import { h, substituir, campo, opcoes, mensagem, etiqueta, idUnico } from '../nucleo/dom.js';
import { ErroApi } from '../nucleo/api.js';
import { criarFormulario, textoOuNulo } from '../nucleo/formulario.js';
import { localDaUnidadeParaIso } from '../nucleo/tempo.js';

export function montar(raiz, ctx) {
  const cat = ctx.catalogo();
  const fuso = ctx.fuso();
  let ativo = true;
  let pacienteEscolhido = null; // { id, nome, temEpisodioAtivo } ou null (= novo paciente)
  let concluido = false;
  let novoSujo = false;

  // ------------------------------------------------------------ busca
  const nomeGrupo = idUnico('tipo-busca');
  const rCns = h('input', { type: 'radio', name: nomeGrupo, value: 'cns', checked: true });
  const rIdent = h('input', { type: 'radio', name: nomeGrupo, value: 'identificador' });
  const termo = h('input', { type: 'search', required: true, maxlength: '40', autocomplete: 'off', inputmode: 'text' });
  const resultados = h('div', { 'aria-live': 'polite' });
  const busca = criarFormulario({
    rotulo: 'Buscar',
    rotuloAcessivel: 'Buscar paciente',
    classeBotao: 'botao-secundario',
    campos: [
      h('fieldset', {}, h('legend', {}, 'Buscar por'), h('div', { class: 'opcoes-inline' },
        h('label', {}, rCns, 'CNS'), h('label', {}, rIdent, 'Identificador institucional'))),
      campo('Valor exato', termo, 'A busca é exata e fica registrada na auditoria.'),
    ],
    enviar: async () => {
      const param = rCns.checked ? 'cns' : 'identificador';
      const lista = await ctx.api.obter(`/api/pacientes?${param}=${encodeURIComponent(termo.value.trim())}`);
      if (!ativo) return;
      desenharResultados(lista);
    },
  });

  function desenharResultados(lista) {
    pacienteEscolhido = null;
    atualizarEscolha();
    if (lista.length === 0) {
      substituir(resultados, h('p', { class: 'vazio' }, 'Nenhum paciente encontrado com esse valor nesta unidade. '
        + 'Confira o número ou cadastre um novo paciente abaixo.'));
      return;
    }
    const grupo = idUnico('paciente');
    substituir(resultados, h('fieldset', {}, h('legend', {}, 'Selecione o paciente'),
      lista.map((p) => {
        const radio = h('input', { type: 'radio', name: grupo, value: p.id, disabled: p.reconciliado,
          aoMudar: () => { pacienteEscolhido = p; atualizarEscolha(); } });
        return h('div', {}, h('label', {}, radio, ' ', p.nome, ' — nascimento ', dataSimples(p.dataNascimento)),
          p.temEpisodioAtivo ? etiqueta('alerta', 'Já tem episódio aberto') : null,
          p.reconciliado ? etiqueta('neutro', 'Registro unificado a outro: use o registro principal') : null);
      })));
  }

  // ------------------------------------------------------------ novo paciente
  const usarNovo = h('input', { type: 'checkbox', id: idUnico('novo') });
  const nNome = h('input', { type: 'text', maxlength: '200' });
  const nNascimento = h('input', { type: 'date' });
  const nCns = h('input', { type: 'text', maxlength: '20', inputmode: 'numeric' });
  const nIdent = h('input', { type: 'text', maxlength: '40' });
  const grupoNovo = h('fieldset', {}, h('legend', {}, 'Dados do novo paciente'), campo('Nome completo', nNome), campo('Data de nascimento', nNascimento),
    campo('CNS', nCns, 'Opcional. Validado pelo servidor.'), campo('Identificador institucional', nIdent, 'Opcional.'));

  // ------------------------------------------------------------ episódio
  const setor = h('select', { required: true }, opcoes(cat.setores.filter((s) => s.ativo)
    .map((s) => ({ valor: s.id, rotulo: s.nome })), { vazio: 'Selecione…' }));
  const justDuplicidade = h('textarea', { maxlength: '300' });
  const grupoDuplicidade = h('div', { hidden: true },
    mensagem('aviso', 'Este paciente já tem episódio aberto nesta unidade. Para abrir outro, justifique.'),
    campo('Justificativa da duplicidade', justDuplicidade));
  const quando = h('input', { type: 'datetime-local' });
  const justHorario = h('input', { type: 'text', maxlength: '120' });
  const grupoMomento = ctx.pode('HORARIO_AJUSTAR') ? h('details', {},
    h('summary', {}, 'Informar horário real de entrada (registro retroativo)'),
    campo(`Entrada às (horário da unidade: ${fuso})`, quando),
    campo('Justificativa do ajuste de horário', justHorario)) : null;
  const escolha = h('p', { class: 'discreto', role: 'status' });

  function atualizarEscolha() {
    const novo = usarNovo.checked;
    grupoNovo.hidden = !novo;
    for (const c of grupoNovo.querySelectorAll('input')) c.disabled = !novo;
    nNome.required = novo;
    const exigeJustificativa = !novo && pacienteEscolhido && pacienteEscolhido.temEpisodioAtivo;
    mostrarDuplicidade(!!exigeJustificativa);
    escolha.textContent = novo ? 'Será cadastrado um novo paciente.'
      : (pacienteEscolhido ? `Paciente selecionado: ${pacienteEscolhido.nome}.` : 'Nenhum paciente selecionado.');
  }
  function mostrarDuplicidade(sim) {
    grupoDuplicidade.hidden = !sim;
    justDuplicidade.required = sim;
    justDuplicidade.disabled = !sim;
  }
  usarNovo.addEventListener('change', atualizarEscolha);
  grupoNovo.addEventListener('input', () => { novoSujo = true; });

  const abrir = criarFormulario({
    rotulo: 'Abrir episódio',
    rotuloAcessivel: 'Abrir episódio',
    campos: [escolha, campo('Setor de entrada', setor), grupoDuplicidade, grupoMomento],
    traduzirErro: (e) => {
      if (e instanceof ErroApi && e.codigo === 'POSSIVEL_DUPLICIDADE') {
        mostrarDuplicidade(true);
        return 'Possível duplicidade: o paciente já tem episódio aberto. Informe a justificativa para continuar.';
      }
      return null;
    },
    enviar: async () => {
      const corpo = { setorId: setor.value };
      if (usarNovo.checked) {
        corpo.novoPaciente = { nome: nNome.value.trim(), dataNascimento: nNascimento.value || null,
          cns: textoOuNulo(nCns), identificadorInstitucional: textoOuNulo(nIdent) };
      } else if (pacienteEscolhido) {
        corpo.pacienteId = pacienteEscolhido.id;
      } else {
        throw new ErroApi(0, { detail: 'Selecione um paciente encontrado na busca ou cadastre um novo.' });
      }
      if (!justDuplicidade.disabled && justDuplicidade.value.trim()) corpo.justificativaDuplicidade = justDuplicidade.value.trim();
      if (quando.value) {
        corpo.momento = { ocorridoEm: localDaUnidadeParaIso(quando.value, fuso), justificativaAjuste: textoOuNulo(justHorario) };
      }
      const r = await ctx.api.criar('/api/episodios', corpo);
      if (!ativo) return;
      concluido = true;
      ctx.anunciar('Episódio aberto.');
      ctx.irPara(`episodio/${r.id}`);
    },
  });

  atualizarEscolha();
  substituir(raiz,
    h('h1', {}, 'Abrir episódio'),
    h('section', { class: 'cartao', 'aria-labelledby': 'tit-busca' }, h('h2', { id: 'tit-busca' }, '1. Paciente'),
      busca.el, resultados,
      h('p', {}, h('label', { for: usarNovo.id }, usarNovo, ' Paciente não encontrado: cadastrar novo paciente')),
      grupoNovo),
    h('section', { class: 'cartao', 'aria-labelledby': 'tit-ep' }, h('h2', { id: 'tit-ep' }, '2. Episódio'), abrir.el));

  return {
    desmontar() { ativo = false; },
    emEdicao: () => !concluido && (abrir.sujo() || novoSujo),
  };
}

function dataSimples(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
  return m ? `${m[3]}/${m[2]}/${m[1]}` : 'não informado';
}
