// Detalhe do episódio: dados do caso, alertas (com ciência na versão exibida), operações que a
// API oferece (etapa/desfecho, motivo, protocolo, destino, setor, observação), pendências e
// linha do tempo. Toda escrita envia a "versao" lida; 409 => avisa e oferece recarregar
// (nunca sobrescreve nem reenvia sozinha). A atualização automática pausa durante a edição.

import { h, substituir, campo, opcoes, mensagem, carregando, etiqueta, idUnico } from '../nucleo/dom.js';
import { mensagemDeErro, ErroApi } from '../nucleo/api.js';
import { criarAtualizador } from '../nucleo/atualizador.js';
import { criarFormulario, textoOuNulo } from '../nucleo/formulario.js';
import { localDaUnidadeParaIso, formatarDataHora } from '../nucleo/tempo.js';
import { descreverEvento } from '../nucleo/eventos.js';
import * as rotulos from '../nucleo/rotulos.js';
import { cronometro, dataHora, limite, bloqueio, avisoOperacional, situacaoRegras, atualizadoEm }
  from '../nucleo/componentes.js';

const INTERVALO_MS = 30000;

export function montar(raiz, ctx, { id }) {
  const cat = ctx.catalogo();
  const fuso = ctx.fuso();
  let ativo = true;
  let caso = null;
  let regrasAtivas = null;
  let assinaturaFormularios = null;
  let pausado = false;
  let formularios = [];

  const titulo = h('h1', {}, 'Episódio');
  const situacao = h('div');
  const secCaso = h('section', { class: 'cartao', 'aria-labelledby': 'tit-caso' });
  const avisoAlertas = h('div', { 'aria-live': 'polite' });
  const listaAlertas = h('div');
  const secOperacoes = h('div');
  const secPendencias = h('section', { class: 'cartao', 'aria-labelledby': 'tit-pend' });
  const secObservacoes = h('section', { class: 'cartao', 'aria-labelledby': 'tit-obs' });
  const secTempo = h('section', { class: 'cartao', 'aria-labelledby': 'tit-tempo' });
  const corpo = h('div', { hidden: true },
    secCaso,
    h('section', { class: 'cartao', 'aria-labelledby': 'tit-alertas' },
      h('h2', { id: 'tit-alertas' }, 'Alertas operacionais'), avisoOperacional(), avisoAlertas, listaAlertas),
    secOperacoes, secPendencias, secObservacoes, secTempo);

  substituir(raiz,
    h('p', {}, h('a', { href: '#/torre' }, '← Voltar à Torre de Controle')),
    titulo, situacao, h('div', { 'aria-live': 'off' }, carregando('Carregando episódio…')), corpo);
  const indicadorCarga = raiz.querySelector('.carregando');

  // ------------------------------------------------------------ leitura
  async function carregar() {
    try {
      const [novo, regras] = await Promise.all([
        ctx.api.obter(`/api/episodios/${id}`),
        ctx.api.obter('/api/config/regras-alerta'),
      ]);
      if (!ativo) return;
      ctx.sincronizar(novo.agora);
      caso = novo;
      regrasAtivas = regras.filter((r) => r.ativa).length;
      desenhar();
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) throw e;
      if (ctx.tratarErroGlobal(e)) return;
      if (indicadorCarga) indicadorCarga.remove();
      if (e instanceof ErroApi && e.status === 404) {
        atualizador.parar();
        corpo.hidden = true;
        substituir(situacao, mensagem('erro', 'Episódio não encontrado na unidade ativa.'));
        return;
      }
      substituir(situacao, mensagem('erro', `Falha ao atualizar: ${mensagemDeErro(e)}`));
      throw e;
    }
  }

  /** Recarrega já (após escrita própria ou a pedido). Não reenvia nada. */
  async function recarregar() {
    assinaturaFormularios = null; // força reconstruir os formulários com as versões novas
    await atualizador.atualizarAgora();
  }

  function desenhar() {
    if (indicadorCarga) indicadorCarga.remove();
    corpo.hidden = false;
    const r = caso.resumo;
    titulo.textContent = `Episódio — ${r.pacienteNome}`;
    substituir(situacao, atualizadoEm(caso.agora, fuso, pausado));
    desenharCaso();
    desenharAlertas();
    desenharTempo();
    desenharObservacoes();
    const assinatura = [r.versao, ...caso.pendencias.map((p) => `${p.id}:${p.versao}:${p.status}`),
      caso.encerradoEm || ''].join('|');
    if (assinatura !== assinaturaFormularios) {
      if (assinaturaFormularios !== null) {
        ctx.anunciar('O episódio foi atualizado.');
      }
      assinaturaFormularios = assinatura;
      formularios = [];
      desenharOperacoes();
      desenharPendencias();
    } else {
      desenharPendencias({ somenteLeitura: true });
    }
  }

  function desenharCaso() {
    const r = caso.resumo;
    const agora = ctx.agora();
    const encerrado = !!caso.encerradoEm;
    substituir(secCaso,
      h('h2', { id: 'tit-caso' }, 'Dados do caso'),
      encerrado ? mensagem('info', `Episódio encerrado: ${rotulos.desfecho(caso.desfecho)} em `
        + `${formatarDataHora(caso.encerradoEm, fuso)}. Somente leitura.`) : null,
      h('div', { class: 'grade' },
        h('dl', { class: 'dados' },
          h('dt', {}, 'Paciente'), h('dd', {}, r.pacienteNome),
          h('dt', {}, 'CNS'), h('dd', {}, caso.pacienteCns || '—'),
          h('dt', {}, 'Identificador'), h('dd', {}, caso.pacienteIdentificador || '—'),
          h('dt', {}, 'Nascimento'), h('dd', {}, dataSimples(caso.pacienteNascimento)),
          caso.justificativaDuplicidade ? [h('dt', {}, 'Duplicidade justificada'),
            h('dd', {}, caso.justificativaDuplicidade)] : null),
        h('dl', { class: 'dados' },
          h('dt', {}, 'Setor'), h('dd', {}, r.setorNome),
          h('dt', {}, 'Etapa'), h('dd', {}, r.etapaNome, ' (', rotulos.natureza(r.natureza), ')',
            encerrado ? null : h('div', { class: 'discreto' }, 'há ', cronometro(r.etapaDesde, agora))),
          h('dt', {}, 'Entrada'), h('dd', {}, dataHora(r.entradaEm, fuso),
            encerrado ? null : h('div', { class: 'discreto' }, 'tempo total ', cronometro(r.entradaEm, agora))),
          h('dt', {}, 'Bloqueio'), h('dd', {}, bloqueio(r, agora),
            caso.motivoDetalhe ? h('div', { class: 'discreto' }, caso.motivoDetalhe) : null)),
        h('dl', { class: 'dados' },
          h('dt', {}, 'Protocolo'), h('dd', {}, r.protocoloNumero ? `${r.protocoloSistema || ''} ${r.protocoloNumero}` : '—'),
          h('dt', {}, 'Destino'), h('dd', {}, r.especialidadeNome || '—',
            caso.destinoDescricao ? h('div', { class: 'discreto' }, caso.destinoDescricao) : null),
          h('dt', {}, 'Pendências'), h('dd', {}, `${r.pendenciasAbertas} aberta(s)`,
            r.pendenciasVencidas > 0 ? [' ', etiqueta('alerta', `${r.pendenciasVencidas} vencida(s)`)] : null),
          h('dt', {}, 'Criticidade operacional'), h('dd', {}, r.maiorCriticidade ? rotulos.criticidade(r.maiorCriticidade) : '—'),
          encerrado && caso.justificativaEncerramento ? [h('dt', {}, 'Justificativa do desfecho'),
            h('dd', {}, caso.justificativaEncerramento)] : null,
          h('dt', {}, 'Versão do registro'), h('dd', { class: 'numero' }, String(r.versao)))));
  }

  // ------------------------------------------------------------ alertas e ciência
  function desenharAlertas() {
    const alertas = caso.alertas || [];
    const podeCiencia = ctx.pode('EPISODIO_ALTERAR') && !caso.encerradoEm;
    if (alertas.length === 0) {
      substituir(listaAlertas, regrasAtivas === 0 ? situacaoRegras(0, 0)
        : h('p', { class: 'vazio', role: 'status' }, `Nenhum alerta ativo para este episódio `
          + `(${regrasAtivas} regra(s) de alerta ativa(s) na unidade).`));
      return;
    }
    substituir(listaAlertas, h('ul', { class: 'lista-alertas' }, alertas.map((a) => {
      const pend = a.pendenciaId ? caso.pendencias.find((p) => p.id === a.pendenciaId) : null;
      return h('li', { class: 'cartao' },
        h('p', {}, etiqueta('alerta', a.regraNome), ' ',
          a.ciente ? etiqueta('ciente', 'Ciência registrada') : etiqueta('neutro', 'Sem ciência')),
        h('dl', { class: 'dados' },
          h('dt', {}, 'Tipo'), h('dd', {}, rotulos.tipoRegra(a.tipo)),
          h('dt', {}, 'Limite'), h('dd', {}, limite(a.limiteMinutos)),
          h('dt', {}, 'Atingido em'), h('dd', {}, dataHora(a.atingidoEm, fuso)),
          pend ? [h('dt', {}, 'Pendência'), h('dd', {}, pend.descricao)] : null,
          h('dt', {}, 'Ação esperada'), h('dd', {}, a.acaoEsperada || 'Não definida na regra'),
          h('dt', {}, 'Versão da regra'), h('dd', { class: 'numero' }, String(a.regraVersao))),
        podeCiencia && !a.ciente ? botaoCiencia(a) : null);
    })));
  }

  function botaoCiencia(a) {
    let enviando = false;
    const botao = h('button', { type: 'button', aoClicar: async () => {
      if (enviando) return;
      enviando = true;
      botao.disabled = true;
      substituir(avisoAlertas, h('p', { class: 'carregando', role: 'status' }, 'Registrando ciência…'));
      try {
        // Exatamente a versão da regra EXIBIDA: se mudou, o servidor recusa (409) e nada é gravado.
        await ctx.api.criar(`/api/episodios/${id}/alertas/ciencia`, {
          regraId: a.regraId, regraVersao: a.regraVersao, referenciaEm: a.referenciaEm, pendenciaId: a.pendenciaId,
        });
        substituir(avisoAlertas, mensagem('sucesso', `Ciência registrada: ${a.regraNome}.`));
      } catch (e) {
        if (e && e.name === 'RespostaDescartada') return;
        substituir(avisoAlertas, mensagem('erro', mensagemCiencia(e)));
      } finally {
        enviando = false;
        if (ativo) await recarregar(); // só leitura; nova ciência exige novo clique
      }
    } }, 'Registrar ciência');
    botao.setAttribute('aria-label', `Registrar ciência do alerta ${a.regraNome}`);
    return h('div', { class: 'acoes' }, botao);
  }

  // ------------------------------------------------------------ operações do episódio
  function desenharOperacoes() {
    if (caso.encerradoEm || !(ctx.pode('EPISODIO_ALTERAR') || ctx.pode('OBSERVACAO_REGISTRAR'))) {
      substituir(secOperacoes);
      return;
    }
    const blocos = [];
    if (ctx.pode('EPISODIO_ALTERAR')) {
      blocos.push(bloco('Mudar etapa ou registrar desfecho', formEtapa()));
      blocos.push(bloco('Motivo do bloqueio', formMotivo()));
      blocos.push(bloco('Protocolo no sistema oficial', formProtocolo()));
      blocos.push(bloco('Destino', formDestino()));
      blocos.push(bloco('Setor', formSetor()));
    }
    if (ctx.pode('OBSERVACAO_REGISTRAR')) blocos.push(bloco('Registrar observação', formObservacao()));
    substituir(secOperacoes, h('section', { class: 'cartao', 'aria-labelledby': 'tit-ops' },
      h('h2', { id: 'tit-ops' }, 'Atualizar o episódio'),
      h('p', { class: 'discreto' }, `As alterações usam a versão ${caso.resumo.versao} do registro. `
        + 'Se outra pessoa alterar o caso antes, nada é sobrescrito: você será avisado para recarregar.'),
      h('div', { class: 'grade' }, blocos)));
  }

  function bloco(rotulo, form) {
    formularios.push(form);
    return h('div', { class: 'bloco-operacao' }, h('h3', {}, rotulo), form.el);
  }

  function registrar(f) { formularios.push(f); return f; }

  function campoMomento() {
    if (!ctx.pode('HORARIO_AJUSTAR')) return { el: null, valor: () => null };
    const quando = h('input', { type: 'datetime-local' });
    const justificativa = h('input', { type: 'text', maxlength: '120' });
    return {
      el: h('details', {}, h('summary', {}, 'Informar horário do fato (registro retroativo)'),
        campo(`Quando ocorreu (horário da unidade: ${fuso})`, quando),
        campo('Justificativa do ajuste de horário', justificativa, 'Obrigatória para registro retroativo.')),
      valor: () => (quando.value
        ? { ocorridoEm: localDaUnidadeParaIso(quando.value, fuso), justificativaAjuste: textoOuNulo(justificativa) }
        : null),
    };
  }

  const motivosAtivos = () => cat.motivos.filter((m) => m.ativo);
  const opcoesMotivo = () => motivosAtivos().map((m) => ({ valor: m.id, rotulo: `${m.descricao} (${rotulos.categoria(m.categoria)})` }));

  function formEtapa() {
    const r = caso.resumo;
    const etapa = (eid) => cat.etapas.find((e) => e.id === eid);
    const destinos = cat.transicoes.filter((t) => t.origemId === r.etapaId).map((t) => etapa(t.destinoId))
      .filter((e) => e && e.ativa && (!e.desfecho || ctx.pode('EPISODIO_ENCERRAR')))
      .sort((a, b) => a.ordem - b.ordem);
    if (destinos.length === 0) {
      return { el: h('p', { class: 'discreto' }, 'Nenhuma transição disponível a partir desta etapa.'), sujo: () => false, contemFoco: () => false };
    }
    const selEtapa = h('select', { required: true }, opcoes(destinos.map((e) => ({
      valor: e.id, rotulo: e.desfecho ? `Desfecho: ${e.nome}` : e.nome })), { vazio: 'Selecione…' }));
    const selMotivo = h('select', {}, opcoes(opcoesMotivo(), { vazio: 'Sem bloqueio' }));
    const detalhe = h('input', { type: 'text', maxlength: '500' });
    const sistema = h('input', { type: 'text', maxlength: '32' });
    const numero = h('input', { type: 'text', maxlength: '60' });
    const justificativa = h('textarea', { maxlength: '1000' });
    const grupoMotivo = h('div', {}, campo('Motivo do bloqueio', selMotivo), campo('Detalhe do motivo', detalhe));
    const grupoProtocolo = h('div', {}, campo('Sistema do protocolo', sistema), campo('Número do protocolo', numero));
    const grupoJustificativa = h('div', {}, campo('Justificativa do desfecho', justificativa));
    const momento = campoMomento();

    function ajustar() {
      const e = etapa(selEtapa.value);
      const desfecho = !!(e && e.desfecho);
      mostrar(grupoMotivo, e && !desfecho);
      selMotivo.required = !!(e && e.exigeMotivo);
      const m = cat.motivos.find((x) => x.id === selMotivo.value);
      detalhe.required = !!(m && m.exigeDetalhe);
      const exigeProtocolo = !!(e && e.exigeProtocolo);
      mostrar(grupoProtocolo, exigeProtocolo);
      sistema.required = exigeProtocolo && !r.protocoloNumero;
      numero.required = exigeProtocolo && !r.protocoloNumero;
      mostrar(grupoJustificativa, desfecho);
      justificativa.required = !!(e && e.exigeJustificativa);
    }
    selEtapa.addEventListener('change', ajustar);
    selMotivo.addEventListener('change', ajustar);
    ajustar();

    return registrar(criarFormulario({
      rotulo: 'Confirmar etapa',
      rotuloAcessivel: 'Mudar etapa',
      campos: [campo('Nova etapa', selEtapa), grupoMotivo, grupoProtocolo, grupoJustificativa, momento.el],
      recarregar,
      enviar: async () => {
        const e = etapa(selEtapa.value);
        if (e.desfecho && !window.confirm(`Registrar o desfecho "${e.nome}" encerra o episódio. Confirmar?`)) {
          throw new ErroApi(0, { detail: 'Operação cancelada.' });
        }
        await ctx.api.substituir(`/api/episodios/${id}/etapa`, {
          versao: r.versao, etapaId: e.id,
          motivoId: !e.desfecho && selMotivo.value ? selMotivo.value : null,
          motivoDetalhe: !e.desfecho && selMotivo.value ? textoOuNulo(detalhe) : null,
          protocoloSistema: e.exigeProtocolo ? textoOuNulo(sistema) : null,
          protocoloNumero: e.exigeProtocolo ? textoOuNulo(numero) : null,
          justificativa: e.desfecho ? textoOuNulo(justificativa) : null,
          momento: momento.valor(),
        });
        await recarregar();
        ctx.anunciar(`Etapa alterada para ${e.nome}.`);
      },
    }));
  }

  function formMotivo() {
    const r = caso.resumo;
    const selMotivo = h('select', {}, opcoes(opcoesMotivo(), { vazio: 'Sem bloqueio (remover motivo atual)' }));
    if (r.motivoId) selMotivo.value = r.motivoId;
    const detalhe = h('input', { type: 'text', maxlength: '500' });
    const ajustar = () => {
      const m = cat.motivos.find((x) => x.id === selMotivo.value);
      detalhe.required = !!(m && m.exigeDetalhe);
    };
    selMotivo.addEventListener('change', ajustar);
    ajustar();
    const momento = campoMomento();
    return registrar(criarFormulario({
      rotulo: 'Salvar motivo',
      rotuloAcessivel: 'Motivo do bloqueio',
      campos: [campo('Motivo', selMotivo), campo('Detalhe', detalhe), momento.el],
      recarregar,
      enviar: async () => {
        await ctx.api.substituir(`/api/episodios/${id}/motivo`, {
          versao: r.versao, motivoId: selMotivo.value || null, detalhe: textoOuNulo(detalhe), momento: momento.valor(),
        });
        await recarregar();
        ctx.anunciar('Motivo do bloqueio atualizado.');
      },
    }));
  }

  function formProtocolo() {
    const r = caso.resumo;
    const sistema = h('input', { type: 'text', maxlength: '32', required: true, value: r.protocoloSistema || '' });
    const numero = h('input', { type: 'text', maxlength: '60', required: true, value: r.protocoloNumero || '' });
    const momento = campoMomento();
    return registrar(criarFormulario({
      rotulo: 'Salvar protocolo',
      rotuloAcessivel: 'Protocolo',
      campos: [campo('Sistema', sistema, 'Ex.: sistema estadual de regulação'), campo('Número', numero), momento.el],
      recarregar,
      enviar: async () => {
        await ctx.api.substituir(`/api/episodios/${id}/protocolo`, {
          versao: r.versao, sistema: sistema.value.trim(), numero: numero.value.trim(), momento: momento.valor(),
        });
        await recarregar();
        ctx.anunciar('Protocolo registrado.');
      },
    }));
  }

  function formDestino() {
    const r = caso.resumo;
    const esp = h('select', {}, opcoes(cat.especialidades.map((e) => ({ valor: e.id, rotulo: e.nome })),
      { vazio: 'Não informada' }));
    const atual = cat.especialidades.find((e) => e.nome === r.especialidadeNome);
    if (atual) esp.value = atual.id;
    const descricao = h('input', { type: 'text', maxlength: '200', value: caso.destinoDescricao || '' });
    const momento = campoMomento();
    return registrar(criarFormulario({
      rotulo: 'Salvar destino',
      rotuloAcessivel: 'Destino',
      campos: [campo('Especialidade', esp), campo('Descrição do destino', descricao), momento.el],
      recarregar,
      enviar: async () => {
        await ctx.api.substituir(`/api/episodios/${id}/destino`, {
          versao: r.versao, especialidadeId: esp.value || null, descricao: textoOuNulo(descricao), momento: momento.valor(),
        });
        await recarregar();
        ctx.anunciar('Destino atualizado.');
      },
    }));
  }

  function formSetor() {
    const r = caso.resumo;
    const setores = cat.setores.filter((s) => s.ativo && s.id !== r.setorId);
    if (setores.length === 0) {
      return { el: h('p', { class: 'discreto' }, 'Não há outro setor ativo na unidade.'), sujo: () => false, contemFoco: () => false };
    }
    const sel = h('select', { required: true }, opcoes(setores.map((s) => ({ valor: s.id, rotulo: s.nome })),
      { vazio: 'Selecione…' }));
    const momento = campoMomento();
    return registrar(criarFormulario({
      rotulo: 'Transferir de setor',
      rotuloAcessivel: 'Setor',
      campos: [campo('Novo setor', sel), momento.el],
      recarregar,
      enviar: async () => {
        await ctx.api.substituir(`/api/episodios/${id}/setor`, { versao: r.versao, setorId: sel.value, momento: momento.valor() });
        await recarregar();
        ctx.anunciar('Setor alterado.');
      },
    }));
  }

  function formObservacao() {
    const texto = h('textarea', { required: true, maxlength: '1000' });
    return registrar(criarFormulario({
      rotulo: 'Registrar observação',
      rotuloAcessivel: 'Observação',
      campos: [campo('Observação operacional', texto, 'Até 1000 caracteres. Fica registrada com seu nome e horário.')],
      enviar: async () => {
        await ctx.api.criar(`/api/episodios/${id}/observacoes`, { texto: texto.value.trim() });
        texto.value = '';
        await recarregar();
        ctx.anunciar('Observação registrada.');
      },
    }));
  }

  // ------------------------------------------------------------ pendências
  function desenharPendencias({ somenteLeitura = false } = {}) {
    const podeGerenciar = ctx.pode('PENDENCIA_GERENCIAR') && !caso.encerradoEm;
    const agora = ctx.agora();
    const itens = caso.pendencias.map((p) => {
      const vencida = p.status === 'ABERTA' && p.prazo && Date.parse(p.prazo) < agora;
      return h('li', { class: 'cartao', 'data-pendencia': p.id },
        h('p', {}, h('strong', {}, p.descricao), ' ',
          etiqueta(p.status === 'ABERTA' ? 'neutro' : 'ok', rotulos.statusPendencia(p.status)),
          vencida ? etiqueta('alerta', 'Prazo vencido') : null),
        h('dl', { class: 'dados' },
          h('dt', {}, 'Categoria'), h('dd', {}, rotulos.categoria(p.categoria)),
          h('dt', {}, 'Responsável'), h('dd', {}, rotulos.responsavel(p)),
          h('dt', {}, 'Prazo'), h('dd', {}, dataHora(p.prazo, fuso)),
          h('dt', {}, 'Criticidade operacional'), h('dd', {}, rotulos.criticidade(p.criticidade)),
          h('dt', {}, 'Criada em'), h('dd', {}, dataHora(p.criadaEm, fuso)),
          p.encerradaEm ? [h('dt', {}, 'Encerrada em'), h('dd', {}, dataHora(p.encerradaEm, fuso))] : null,
          p.resolucao ? [h('dt', {}, 'Texto de encerramento'), h('dd', {}, p.resolucao)] : null),
        podeGerenciar && p.status === 'ABERTA' && !somenteLeitura ? acoesPendencia(p) : null);
    });
    if (somenteLeitura) {
      // Atualiza só os dados de leitura, preservando os formulários existentes de cada pendência.
      for (const li of itens) {
        const existente = secPendencias.querySelector(`[data-pendencia="${CSS.escape(li.getAttribute('data-pendencia'))}"]`);
        if (existente) {
          const acoes = existente.querySelector('.acoes-pendencia');
          if (acoes) li.append(acoes);
          existente.replaceWith(li);
        }
      }
      return;
    }
    const abertas = caso.pendencias.filter((p) => p.status === 'ABERTA').length;
    substituir(secPendencias,
      h('h2', { id: 'tit-pend' }, `Pendências (${abertas} aberta(s))`),
      itens.length === 0 ? h('p', { class: 'vazio' }, 'Nenhuma pendência registrada.') : h('ul', { class: 'lista-pendencias' }, itens),
      podeGerenciar ? h('details', {}, h('summary', {}, 'Nova pendência'), formNovaPendencia().el) : null);
  }

  function seletorResponsavel(prefixo, obrigatorio) {
    const nome = idUnico(prefixo);
    const tipos = [
      { valor: 'usuario', rotulo: 'Profissional' }, { valor: 'setor', rotulo: 'Setor' }, { valor: 'papel', rotulo: 'Perfil' },
    ];
    const radios = tipos.map((t) => h('input', { type: 'radio', name: nome, value: t.valor }));
    const selUsuario = h('select', {}, opcoes(cat.profissionais.map((p) => ({ valor: p.id, rotulo: p.nome })), { vazio: 'Selecione…' }));
    const selSetor = h('select', {}, opcoes(cat.setores.filter((s) => s.ativo).map((s) => ({ valor: s.id, rotulo: s.nome })), { vazio: 'Selecione…' }));
    const selPapel = h('select', {}, opcoes(rotulos.PAPEIS.map((p) => ({ valor: p, rotulo: rotulos.papel(p) })), { vazio: 'Selecione…' }));
    const grupos = {
      usuario: campo('Profissional responsável', selUsuario),
      setor: campo('Setor responsável', selSetor),
      papel: campo('Perfil responsável', selPapel),
    };
    const selecionado = () => (radios.find((r) => r.checked) || {}).value || null;
    function ajustar() {
      const t = selecionado();
      for (const [k, g] of Object.entries(grupos)) {
        mostrar(g, k === t);
        g.querySelector('select').required = k === t;
      }
    }
    radios.forEach((r) => r.addEventListener('change', ajustar));
    if (obrigatorio) radios[0].required = true;
    ajustar();
    return {
      el: h('fieldset', {}, h('legend', {}, obrigatorio ? 'Responsável' : 'Novo responsável (opcional)'),
        h('div', { class: 'opcoes-inline' }, tipos.map((t, i) => h('label', {}, radios[i], t.rotulo))),
        Object.values(grupos)),
      valor() {
        const t = selecionado();
        if (t === 'usuario' && selUsuario.value) return { usuarioId: selUsuario.value };
        if (t === 'setor' && selSetor.value) return { setorId: selSetor.value };
        if (t === 'papel' && selPapel.value) return { papel: selPapel.value };
        return null;
      },
    };
  }

  function formNovaPendencia() {
    const categoria = h('select', { required: true }, opcoes(rotulos.CATEGORIAS.map((c) => ({ valor: c, rotulo: rotulos.categoria(c) })), { vazio: 'Selecione…' }));
    const descricao = h('textarea', { required: true, maxlength: '500' });
    const resp = seletorResponsavel('resp-nova', true);
    const prazo = h('input', { type: 'datetime-local', required: true });
    const criticidade = h('select', { required: true }, opcoes(rotulos.CRITICIDADES.map((c) => ({ valor: c, rotulo: rotulos.criticidade(c) })), { vazio: 'Selecione…' }));
    return registrar(criarFormulario({
      rotulo: 'Criar pendência',
      rotuloAcessivel: 'Nova pendência',
      campos: [campo('Categoria', categoria), campo('Descrição / próxima ação', descricao), resp.el,
        campo(`Prazo (horário da unidade: ${fuso})`, prazo, 'Até 30 dias.'),
        campo('Criticidade operacional', criticidade, 'Urgência de ação operacional — não é risco clínico.')],
      enviar: async () => {
        await ctx.api.criar(`/api/episodios/${id}/pendencias`, {
          categoria: categoria.value, descricao: descricao.value.trim(), responsavel: resp.valor(),
          prazo: localDaUnidadeParaIso(prazo.value, fuso), criticidade: criticidade.value,
        });
        await recarregar();
        ctx.anunciar('Pendência criada.');
      },
    }));
  }

  function acoesPendencia(p) {
    const resp = seletorResponsavel(`resp-${p.id}`, false);
    const prazo = h('input', { type: 'datetime-local' });
    const alterar = registrar(criarFormulario({
      rotulo: 'Salvar alteração',
      rotuloAcessivel: 'Alterar responsável ou prazo',
      classeBotao: 'botao-secundario',
      campos: [resp.el, campo(`Novo prazo (horário da unidade: ${fuso})`, prazo, 'Deixe em branco para manter.')],
      recarregar,
      enviar: async () => {
        const corpo = { versao: p.versao };
        const r = resp.valor();
        if (r) corpo.responsavel = r;
        if (prazo.value) corpo.prazo = localDaUnidadeParaIso(prazo.value, fuso);
        if (!corpo.responsavel && !corpo.prazo) throw new ErroApi(0, { detail: 'Informe um novo responsável ou um novo prazo.' });
        await ctx.api.alterar(`/api/pendencias/${p.id}`, corpo);
        await recarregar();
        ctx.anunciar('Pendência atualizada.');
      },
    }));
    const encerrar = (acao, rotulo, rotuloTexto) => {
      const texto = h('textarea', { required: true, maxlength: '1000' });
      return registrar(criarFormulario({
        rotulo,
        rotuloAcessivel: rotulo,
        classeBotao: acao === 'cancelamento' ? 'botao-perigo' : undefined,
        campos: [campo(rotuloTexto, texto)],
        recarregar,
        enviar: async () => {
          await ctx.api.criar(`/api/pendencias/${p.id}/${acao}`, { versao: p.versao, texto: texto.value.trim() });
          await recarregar();
          ctx.anunciar(acao === 'resolucao' ? 'Pendência resolvida.' : 'Pendência cancelada.');
        },
      }));
    };
    const formEnc = {
      resolucao: encerrar('resolucao', 'Resolver pendência', 'Como foi resolvida'),
      cancelamento: encerrar('cancelamento', 'Cancelar pendência', 'Motivo do cancelamento'),
    };
    return h('div', { class: 'acoes-pendencia' },
      h('details', {}, h('summary', {}, 'Alterar responsável ou prazo'), alterar.el),
      h('details', {}, h('summary', {}, 'Resolver'), formEnc.resolucao.el),
      h('details', {}, h('summary', {}, 'Cancelar'), formEnc.cancelamento.el));
  }

  // ------------------------------------------------------------ linha do tempo e observações
  function desenharTempo() {
    const eventos = caso.linhaDoTempo || [];
    substituir(secTempo,
      h('h2', { id: 'tit-tempo' }, 'Linha do tempo'),
      caso.historicoTruncado ? mensagem('aviso', 'Histórico muito longo: parte dos registros não é exibida aqui.') : null,
      eventos.length === 0 ? h('p', { class: 'vazio' }, 'Sem registros.') : h('ol', { class: 'linha-do-tempo' },
        eventos.map((ev) => {
          const desc = descreverEvento(ev, cat);
          const retroativo = ev.registradoEm && ev.ocorridoEm
            && Math.abs(Date.parse(ev.registradoEm) - Date.parse(ev.ocorridoEm)) > 60000;
          return h('li', {},
            h('div', { class: 'quando' }, dataHora(ev.ocorridoEm, fuso),
              retroativo ? [' · registrado em ', dataHora(ev.registradoEm, fuso)] : null),
            h('div', {}, h('strong', {}, rotulos.evento(ev.tipo)), desc ? ` — ${desc}` : '',
              ev.corrigeEventoId ? ' (corrige registro anterior)' : ''),
            h('div', { class: 'discreto' }, ev.autorNome || 'Sistema'));
        })));
  }

  function desenharObservacoes() {
    const obs = caso.observacoes || [];
    substituir(secObservacoes,
      h('h2', { id: 'tit-obs' }, 'Observações'),
      obs.length === 0 ? h('p', { class: 'vazio' }, 'Nenhuma observação.') : h('ul', { class: 'lista-observacoes' },
        obs.map((o) => h('li', {}, h('p', {}, o.texto),
          h('p', { class: 'discreto' }, o.autorNome || '—', ' · ', dataHora(o.registradaEm, fuso))))));
  }

  // ------------------------------------------------------------ ciclo de vida
  const emEdicao = () => formularios.some((f) => f.sujo() || f.contemFoco());
  const atualizador = criarAtualizador({
    carregar, intervaloMs: INTERVALO_MS, emEdicao,
    aoMudarSituacao: (s) => {
      if (!ativo) return;
      ctx.situacaoAtualizacao(s);
      if (s.pausado !== pausado && caso) {
        pausado = s.pausado;
        substituir(situacao, atualizadoEm(caso.agora, fuso, pausado));
      }
    },
  });
  atualizador.atualizarAgora();
  atualizador.iniciar();

  return {
    desmontar() { ativo = false; atualizador.parar(); },
    emEdicao: () => formularios.some((f) => f.sujo()),
  };
}

function mostrar(el, visivel) {
  el.hidden = !visivel;
  // Campos ocultos não participam da validação nem do envio.
  for (const c of el.querySelectorAll('input, select, textarea')) c.disabled = !visivel;
}

function dataSimples(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
  return m ? `${m[3]}/${m[2]}/${m[1]}` : '—';
}

function mensagemCiencia(e) {
  if (e instanceof ErroApi) {
    if (e.status === 409) {
      return 'A regra deste alerta foi alterada desde que você a visualizou. Os alertas foram recarregados: '
        + 'confira a versão atual e, se for o caso, registre a ciência novamente.';
    }
    if (e.status === 422) return 'Este alerta não está mais ativo (a situação do caso mudou). Os dados foram recarregados.';
    if (e.status === 404) return 'Alerta ou regra não encontrado na unidade ativa. Os dados foram recarregados.';
  }
  return mensagemDeErro(e);
}
