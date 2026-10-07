// Passagem de plantão (M06: RF-016, RF-017; ERS §10.4). Quem gerencia plantão PREPARA a passagem a
// partir do estado atual (todos os episódios abertos, sem paginação) e a ENTREGA; OUTRO profissional
// abre a passagem, vê o conteúdo entregue e as diferenças desde a entrega e CONFIRMA o recebimento.
// Cada confirmação envia a assinatura do conteúdo exibido: se o servidor encontrar outro conteúdo,
// responde 409 e nada é gravado (o usuário recarrega e decide de novo). Sem atualização automática:
// o conteúdo exibido só muda por ação explícita ("Atualizar conteúdo").

import { h, substituir, campo, mensagem, carregando, etiqueta } from '../nucleo/dom.js';
import { mensagemDeErro, ErroApi } from '../nucleo/api.js';
import { criarFormulario, textoOuNulo } from '../nucleo/formulario.js';
import { formatarDataHora } from '../nucleo/tempo.js';
import * as rotulos from '../nucleo/rotulos.js';
import { cronometro, dataHora, avisoOperacional } from '../nucleo/componentes.js';

export function montar(raiz, ctx, { id }) {
  let ativo = true;
  let concluido = false; // entrega feita: a navegação seguinte não é "sair com alterações"
  const formularios = [];
  const cat = ctx.catalogo();
  const fuso = ctx.fuso();
  const eu = ctx.estado.sessao().usuarioId;
  const titulo = h('h1', {}, id ? 'Passagem de plantão — detalhe' : 'Passagem de plantão');
  const situacao = h('div', { 'aria-live': 'polite' });
  const corpo = h('div', {}, carregando());
  substituir(raiz, titulo, h('p', {}, id ? h('a', { href: '#/plantao' }, '← Voltar à passagem de plantão') : null),
    avisoOperacional(), situacao, corpo);

  const nomes = {
    etapa: (x) => (cat.etapas.find((e) => e.id === x) || {}).nome || '—',
    setor: (x) => (cat.setores.find((e) => e.id === x) || {}).nome || '—',
    motivo: (x) => (cat.motivos.find((e) => e.id === x) || {}).descricao || null,
    usuario: (x) => (cat.profissionais.find((e) => e.id === x) || {}).nome || 'profissional',
  };
  let regras = new Map();
  let observacaoDigitada = ''; // sobrevive a "Recarregar dados": o texto digitado não se perde

  // Rótulos vêm do servidor (estado atual do cadastro, mesma leitura do conteúdo); o catálogo da
  // tela é só reserva (pode não ter itens criados ou inativados depois do login).
  function responsavel(p) {
    if (p.responsavelUsuarioId) return p.responsavelNome || nomes.usuario(p.responsavelUsuarioId);
    if (p.responsavelSetorId) return `Setor: ${p.responsavelNome || nomes.setor(p.responsavelSetorId)}`;
    if (p.responsavelPapel) return `Perfil: ${rotulos.papel(p.responsavelPapel)}`;
    return '—';
  }
  const etapaDe = (c) => c.etapaNome || nomes.etapa(c.etapaId);
  const setorDe = (c) => c.setorNome || nomes.setor(c.setorId);
  const motivoDe = (c) => (c.motivoId ? c.motivoDescricao || nomes.motivo(c.motivoId) : null);
  function bloqueioTexto(c) {
    if (!c.bloqueioDesde) return 'Sem bloqueio';
    return [rotulos.categoria(c.categoria), motivoDe(c) ? ` — ${motivoDe(c)}` : '', ' (desde ', dataHora(c.bloqueioDesde, fuso), ')'];
  }

  async function carregar() {
    substituir(situacao);
    substituir(corpo, carregando());
    try {
      const [listaRegras, dados] = await Promise.all([
        ctx.api.obter('/api/config/regras-alerta'),
        id ? ctx.api.obter(`/api/plantao/passagens/${id}`) : ctx.api.obter('/api/plantao/previa'),
      ]);
      const historico = id ? null : await ctx.api.obter('/api/plantao/passagens');
      if (!ativo) return;
      regras = new Map(listaRegras.map((r) => [r.id, r]));
      ctx.sincronizar(dados.agora);
      formularios.length = 0;
      if (id) desenharDetalhe(dados);
      else desenharPrevia(dados, historico);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) return;
      if (ctx.tratarErroGlobal(e)) return;
      substituir(corpo);
      substituir(situacao, mensagem('erro', e instanceof ErroApi && e.codigo === 'PASSAGEM_GRANDE_DEMAIS'
        ? `${e.message}` : mensagemDeErro(e)));
    }
  }

  // ------------------------------------------------------------ conteúdo (seções da ERS §10.4)
  function linhaCaso(c, agora, mudados = new Set()) {
    const mudou = mudados.has(c.episodioId);
    const motivo = motivoDe(c);
    return h('li', { class: 'cartao', 'data-caso': c.episodioId },
      h('p', {}, h('a', { href: `#/episodio/${c.episodioId}` }, c.pacienteNome || 'Paciente'), ' ',
        c.critico ? etiqueta('alerta', 'Crítico (operacional)') : null,
        c.transferencia ? etiqueta('neutro', 'Transferência') : null,
        mudou ? [' ', etiqueta('ciente', 'Mudou depois da entrega — veja a situação atual')] : null),
      h('dl', { class: 'dados' },
        h('dt', {}, 'Setor / etapa'), h('dd', {}, `${setorDe(c)} · ${etapaDe(c)}`),
        h('dt', {}, 'Na unidade há'), h('dd', {}, cronometro(c.entradaEm, agora)),
        h('dt', {}, 'Bloqueio'), h('dd', {}, c.bloqueioDesde
          ? [etiqueta('bloqueio', rotulos.categoria(c.categoria)), ' ', motivo || '', ' há ', cronometro(c.bloqueioDesde, agora)]
          : 'Sem bloqueio'),
        c.alertas.length ? [h('dt', {}, 'Alertas operacionais'), h('dd', {}, h('ul', { class: 'lista-compacta' },
          c.alertas.map((a) => {
            const r = regras.get(a.regraId);
            return h('li', {}, r ? r.nome : rotulos.tipoRegra(a.tipo),
              r && r.acaoEsperada ? ` — ação esperada: ${r.acaoEsperada}` : '');
          })))] : null),
      c.pendencias.length ? h('ul', { class: 'lista-compacta' }, c.pendencias.map((p) => linhaPendencia(p, mudados.has(p.id)))) : null);
  }

  function linhaPendencia(p, mudou = false) {
    return h('li', { 'data-pendencia': p.id },
      p.vencida ? etiqueta('alerta', 'Vencida') : etiqueta('neutro', 'No prazo'), ' ',
      h('strong', {}, p.descricao || 'Pendência'), ` — responsável: ${responsavel(p)} — prazo: `, dataHora(p.prazo, fuso),
      ` — criticidade operacional ${rotulos.criticidade(p.criticidade).toLowerCase()}`,
      mudou ? [' ', etiqueta('ciente', 'Mudou depois da entrega — veja a situação atual')] : null);
  }

  /**
   * Seções da ERS §10.4. {@code mudados}: ids de casos/pendências que mudaram desde a entrega (no
   * detalhe, o conteúdo exibido aqui é o ENTREGUE; a situação atual fica na seção de recebimento).
   */
  function secoes(casos, agora, { nivel = 'h2', mudados = new Set() } = {}) {
    const pendencias = casos.flatMap((c) => c.pendencias.map((p) => ({ ...p, paciente: c.pacienteNome })));
    const vencidas = pendencias.filter((p) => p.vencida);
    const acoes = pendencias.filter((p) => !p.vencida);
    const criticos = casos.filter((c) => c.critico);
    const transferencias = casos.filter((c) => c.transferencia);
    const demais = casos.filter((c) => !c.critico && !c.transferencia);
    const secao = (tituloSecao, itens, vazio, desenhar) => h('section', { class: 'cartao', 'aria-label': tituloSecao },
      h(nivel, {}, `${tituloSecao} (${itens.length})`),
      itens.length ? h('ul', { class: 'lista-travados' }, itens.map(desenhar)) : h('p', { class: 'vazio' }, vazio));
    const caso = (c) => linhaCaso(c, agora, mudados);
    const pend = (p) => h('li', {}, h('span', { class: 'discreto' }, `${p.paciente || 'Paciente'}: `),
      linhaPendencia(p, mudados.has(p.id)));
    return [
      secao('Casos críticos', criticos, 'Nenhum caso crítico (alerta operacional ou pendência crítica).', caso),
      secao('Transferências', transferencias, 'Nenhuma transferência em andamento.', caso),
      secao('Pendências vencidas', vencidas, 'Nenhuma pendência vencida.', pend),
      secao('Ações esperadas (pendências no prazo)', acoes, 'Nenhuma pendência aberta no prazo.', pend),
      secao('Demais casos ativos', demais, 'Nenhum outro caso ativo.', caso),
    ];
  }

  function totais(t) {
    return h('p', { role: 'status' }, h('strong', {}, `${t.casos} caso(s) ativo(s)`),
      ` · ${t.criticos} crítico(s) · ${t.transferencias} transferência(s) · ${t.pendencias} pendência(s) aberta(s), `
      + `${t.vencidas} vencida(s). Todos os casos estão listados abaixo (sem paginação).`);
  }

  // ------------------------------------------------------------ prévia + entrega
  function desenharPrevia(p, historico) {
    const agora = ctx.agora();
    const blocos = [];
    if (p.pendente) {
      const autor = p.pendente.entreguePor === eu;
      blocos.push(h('section', { class: 'cartao', 'aria-label': 'Passagem aguardando recebimento' },
        h('h2', {}, 'Passagem aguardando recebimento'),
        mensagem('aviso', `Entregue por ${p.pendente.entreguePorNome} em ${formatarDataHora(p.pendente.entregueEm, fuso)}. `
          + (autor ? 'O recebimento deve ser confirmado por outro profissional.' : 'Abra para conferir e confirmar o recebimento.')),
        h('a', { href: `#/plantao/${p.pendente.id}`, class: 'botao' }, 'Abrir passagem pendente')));
    } else {
      const obs = h('textarea', { maxlength: '500' });
      obs.value = observacaoDigitada;
      obs.addEventListener('input', () => { observacaoDigitada = obs.value; });
      const form = criarFormulario({
        rotulo: 'Entregar passagem',
        rotuloAcessivel: 'Entregar passagem',
        campos: [campo('Observação da passagem (opcional)', obs,
          'Informação operacional para o próximo plantão. Não registre dados clínicos.')],
        recarregar: carregar,
        traduzirErro: (e) => (e instanceof ErroApi && e.codigo === 'PASSAGEM_DESATUALIZADA'
          ? 'O conteúdo mudou desde que você o viu (caso alterado, encerrado, pendência resolvida ou prazo vencido). '
            + 'Nada foi entregue. Recarregue, confira e entregue novamente.' : null),
        enviar: async () => {
          // A assinatura é a do conteúdo EXIBIDO nesta tela.
          const r = await ctx.api.criar('/api/plantao/passagens', { assinatura: p.assinatura, observacao: textoOuNulo(obs) });
          concluido = true;
          ctx.notificar('Passagem entregue. Aguarde o recebimento por outro profissional.');
          ctx.irPara(`plantao/${r.id}`);
        },
      });
      formularios.push(form);
      blocos.push(h('section', { class: 'cartao', 'aria-label': 'Preparar passagem' },
        h('h2', {}, 'Preparar passagem'),
        h('p', {}, p.periodoInicio
          ? ['Período: desde a última passagem recebida (', dataHora(p.periodoInicio, fuso), ') até a entrega.']
          : 'Primeira passagem da unidade: o período começa nesta entrega.'),
        h('p', { class: 'discreto' }, `Conteúdo gerado em ${formatarDataHora(p.agora, fuso)} (horário do servidor). `,
          'A entrega registra exatamente o conteúdo abaixo.'),
        h('div', { class: 'acoes' }, h('button', { type: 'button', class: 'botao-secundario', aoClicar: carregar },
          'Atualizar conteúdo')),
        totais(p.totais)));
      blocos.push(...secoes(p.casos, agora));
      blocos.push(h('section', { class: 'cartao', 'aria-label': 'Entrega' }, h('h2', {}, 'Entrega'), form.el));
    }
    if (p.pendente) {
      blocos.push(h('p', { class: 'discreto' }, 'O conteúdo atual não é exibido para nova entrega enquanto houver uma pendente.'));
    }
    blocos.push(historicoSecao(historico));
    substituir(corpo, blocos);
  }

  function historicoSecao(lista) {
    return h('section', { class: 'cartao', 'aria-label': 'Passagens anteriores' }, h('h2', {}, 'Passagens anteriores'),
      lista.length === 0 ? h('p', { class: 'vazio' }, 'Nenhuma passagem registrada nesta unidade.')
        : h('div', { class: 'rolagem' }, h('table', { class: 'responsiva' },
          h('thead', {}, h('tr', {}, ['Entregue em', 'Por', 'Situação', 'Recebida por', 'Casos', ''].map((t) => h('th', { scope: 'col' }, t)))),
          h('tbody', {}, lista.map((x) => h('tr', {},
            h('td', { 'data-rotulo': 'Entregue em' }, dataHora(x.entregueEm, fuso)),
            h('td', { 'data-rotulo': 'Por' }, x.entreguePorNome || '—'),
            h('td', { 'data-rotulo': 'Situação' }, rotulos.statusPassagem(x.status)),
            h('td', { 'data-rotulo': 'Recebida por' }, x.recebidaPorNome ? [x.recebidaPorNome, ' em ', dataHora(x.recebidaEm, fuso)] : '—'),
            h('td', { 'data-rotulo': 'Casos', class: 'numero' }, String(x.totalCasos)),
            h('td', { 'data-rotulo': 'Abrir' }, h('a', { href: `#/plantao/${x.id}` }, 'Abrir'))))))));
  }

  // ------------------------------------------------------------ detalhe + recebimento
  function desenharDetalhe(d) {
    const agora = ctx.agora();
    const p = d.passagem;
    const blocos = [];
    blocos.push(h('section', { class: 'cartao', 'aria-label': 'Registro da passagem' },
      h('h2', {}, `Passagem ${rotulos.statusPassagem(p.status).toLowerCase()}`),
      d.integra ? null : mensagem('erro', 'O conteúdo gravado não confere com a assinatura da entrega. Comunique a administração.'),
      h('dl', { class: 'dados' },
        h('dt', {}, 'Entregue por'), h('dd', {}, p.entreguePorNome || '—', ' em ', dataHora(p.entregueEm, fuso)),
        h('dt', {}, 'Período'), h('dd', {}, p.periodoInicio ? ['de ', dataHora(p.periodoInicio, fuso), ' até a entrega'] : 'primeira passagem da unidade'),
        p.observacao ? [h('dt', {}, 'Observação'), h('dd', {}, p.observacao)] : null,
        p.recebidaPorNome ? [h('dt', {}, 'Recebida por'), h('dd', {}, p.recebidaPorNome, ' em ', dataHora(p.recebidaEm, fuso))] : null,
        p.diferencasRecebimento ? [h('dt', {}, 'Diferenças vistas no recebimento'), h('dd', {}, contagens(p.diferencasRecebimento))] : null,
        p.canceladaPorNome ? [h('dt', {}, 'Cancelada por'), h('dd', {}, p.canceladaPorNome, ' em ', dataHora(p.canceladaEm, fuso),
          ' — ', p.justificativaCancelamento || '')] : null),
      totais(d.totais)));
    const mudados = new Set();
    if (p.status === 'ENTREGUE') {
      if (p.entreguePor === eu) {
        blocos.push(cancelamento(p));
      } else {
        blocos.push(recebimento(d));
        const dif = d.diferencas;
        [...dif.casosAlterados, ...dif.casosEncerrados].forEach((x) => mudados.add(x.episodioId));
        [...dif.pendenciasAlteradas, ...dif.pendenciasEncerradas].forEach((x) => mudados.add(x.id));
      }
    }
    // Conteúdo ENTREGUE, separado e rotulado como tal (não é a situação atual).
    blocos.push(h('section', { class: 'cartao', 'aria-label': 'Conteúdo entregue' },
      h('h2', {}, 'Conteúdo entregue'),
      h('p', {}, 'Como estava na entrega, em ', dataHora(p.entregueEm, fuso),
        '. Registro imutável: só identificadores e marcações; nomes e descrições vêm do cadastro atual.'),
      mudados.size ? mensagem('aviso', `${mudados.size} item(ns) abaixo mudaram depois da entrega: os valores atuais estão em "Situação atual".`)
        : null,
      ...secoes(d.casos, agora, { nivel: 'h3', mudados })));
    substituir(corpo, blocos);
  }

  function contagens(c) {
    const r = [];
    if (c.casosEncerrados) r.push(`${c.casosEncerrados} caso(s) encerrado(s)`);
    if (c.casosNovos) r.push(`${c.casosNovos} caso(s) novo(s)`);
    if (c.casosAlterados) r.push(`${c.casosAlterados} caso(s) alterado(s)`);
    if (c.pendenciasEncerradas) r.push(`${c.pendenciasEncerradas} pendência(s) encerrada(s)`);
    if (c.pendenciasNovas) r.push(`${c.pendenciasNovas} pendência(s) nova(s)`);
    if (c.pendenciasAlteradas) r.push(`${c.pendenciasAlteradas} pendência(s) alterada(s)`);
    return r.length ? r.join(' · ') : 'nenhuma';
  }

  // ------------------------------------------------------------ situação atual (entregue × agora)
  const linkCaso = (x) => h('a', { href: `#/episodio/${x.episodioId}` }, x.pacienteNome || 'Paciente');
  const campos = (lista, rotulo) => h('span', { class: 'discreto' }, ` — mudou: ${lista.map(rotulo).join(', ')}`);

  /** Tabela "Na entrega" × "Agora" só com os campos que mudaram. */
  function antesDepois(linhas) {
    return h('div', { class: 'rolagem' }, h('table', { class: 'responsiva antes-depois' },
      h('thead', {}, h('tr', {}, ['Campo', 'Na entrega', 'Agora'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, linhas.map(([campo, antes, agora]) => h('tr', {},
        h('th', { scope: 'row', 'data-rotulo': 'Campo' }, campo),
        h('td', { 'data-rotulo': 'Na entrega' }, antes),
        h('td', { 'data-rotulo': 'Agora' }, agora))))));
  }

  function casoAlterado(x) {
    const a = x.entregue;
    const b = x.atual;
    const linhas = [];
    for (const campo of x.campos) {
      if (campo === 'ETAPA') linhas.push(['Etapa', [etapaDe(a), ' desde ', dataHora(a.etapaDesde, fuso)], [etapaDe(b), ' desde ', dataHora(b.etapaDesde, fuso)]]);
      else if (campo === 'SETOR') linhas.push(['Setor', setorDe(a), setorDe(b)]);
      else if (campo === 'MOTIVO_BLOQUEIO') linhas.push(['Bloqueio/motivo', bloqueioTexto(a), bloqueioTexto(b)]);
      else if (campo === 'CRITICO') linhas.push(['Crítico (operacional)', a.critico ? 'Sim' : 'Não', b.critico ? 'Sim' : 'Não']);
      else if (campo === 'TRANSFERENCIA') linhas.push(['Transferência', a.transferencia ? 'Sim' : 'Não', b.transferencia ? 'Sim' : 'Não']);
      else if (campo === 'ALERTAS') linhas.push(['Alertas operacionais', String(a.alertas.length), String(b.alertas.length)]);
      else if (campo === 'ENTRADA') linhas.push(['Entrada', dataHora(a.entradaEm, fuso), dataHora(b.entradaEm, fuso)]);
    }
    return h('li', { class: 'cartao', 'data-caso': x.episodioId },
      h('p', {}, linkCaso(x), campos(x.campos, rotulos.campoCaso)),
      linhas.length ? antesDepois(linhas) : null,
      x.campos.includes('OUTRO_REGISTRO')
        ? h('p', { class: 'discreto' }, 'Houve outro registro no caso (ex.: observação ou protocolo). Abra o caso para ver.') : null);
  }

  function pendenciaAlterada(x) {
    const a = x.entregue;
    const b = x.atual;
    const linhas = [];
    for (const campo of x.campos) {
      if (campo === 'RESPONSAVEL') linhas.push(['Responsável', responsavel(a), responsavel(b)]);
      else if (campo === 'PRAZO') linhas.push(['Prazo', dataHora(a.prazo, fuso), dataHora(b.prazo, fuso)]);
      else if (campo === 'VENCIMENTO') linhas.push(['Situação do prazo', a.vencida ? 'Vencida' : 'No prazo', b.vencida ? 'Vencida' : 'No prazo']);
      else if (campo === 'CRITICIDADE') linhas.push(['Criticidade operacional', rotulos.criticidade(a.criticidade), rotulos.criticidade(b.criticidade)]);
      else if (campo === 'CATEGORIA') linhas.push(['Categoria', rotulos.categoria(a.categoria), rotulos.categoria(b.categoria)]);
    }
    return h('li', { class: 'cartao', 'data-pendencia': x.id },
      h('p', {}, h('strong', {}, x.descricao || 'Pendência'), ' — caso: ', linkCaso(x), campos(x.campos, rotulos.campoPendencia)),
      linhas.length ? antesDepois(linhas) : null,
      x.campos.includes('OUTRO_REGISTRO') ? h('p', { class: 'discreto' }, 'Houve outro registro na pendência. Abra o caso para ver.') : null);
  }

  function recebimento(d) {
    const dif = d.diferencas;
    const vazio = Object.values(dif.contagens).every((n) => n === 0);
    const lista = (rotulo, itens, desenhar) => (itens.length
      ? [h('h3', {}, `${rotulo} (${itens.length})`), h('ul', { class: 'lista-travados' }, itens.map(desenhar))] : null);
    const form = criarFormulario({
      rotulo: 'Confirmar recebimento',
      rotuloAcessivel: 'Confirmar recebimento',
      campos: [h('p', {}, 'Ao confirmar, você declara ter recebido o conteúdo entregue e a situação atual exibida nesta seção.')],
      recarregar: carregar,
      traduzirErro: (e) => (e instanceof ErroApi && e.codigo === 'RECEBIMENTO_DESATUALIZADO'
        ? 'A situação mudou desde que você abriu a passagem. Nada foi confirmado. Recarregue, confira as diferenças e confirme novamente.'
        : null),
      enviar: async () => {
        await ctx.api.criar(`/api/plantao/passagens/${d.passagem.id}/recebimento`,
          { versao: d.passagem.versao, assinatura: d.assinaturaRecebimento });
        await carregar();
        ctx.anunciar('Recebimento confirmado.');
      },
    });
    formularios.push(form);
    const agora = ctx.agora();
    return h('section', { class: 'cartao', 'aria-label': 'Recebimento' },
      h('h2', {}, 'Situação atual'),
      h('p', {}, 'Lida em ', dataHora(d.agora, fuso), ' (horário do servidor). ',
        d.totaisAtuais ? `Agora: ${d.totaisAtuais.casos} caso(s) ativo(s), ${d.totaisAtuais.pendencias} pendência(s) aberta(s), `
          + `${d.totaisAtuais.vencidas} vencida(s). ` : '',
        'Tudo o que não aparece abaixo está igual ao conteúdo entregue.'),
      vazio ? mensagem('info', 'Nenhuma diferença entre o conteúdo entregue e a situação atual.') : [
        mensagem('aviso', `Mudanças desde a entrega: ${contagens(dif.contagens)}.`),
        lista('Casos alterados', dif.casosAlterados, casoAlterado),
        lista('Pendências alteradas', dif.pendenciasAlteradas, pendenciaAlterada),
        lista('Casos novos', dif.casosNovos, (x) => linhaCaso(x.atual, agora)),
        lista('Pendências novas', dif.pendenciasNovas, (x) => h('li', { class: 'cartao' },
          h('p', {}, 'Caso: ', linkCaso(x)), h('ul', { class: 'lista-compacta' }, linhaPendencia({ ...x.atual, descricao: x.descricao })))),
        lista('Casos encerrados (não estão mais abertos)', dif.casosEncerrados, (x) => h('li', { class: 'cartao', 'data-caso': x.episodioId },
          h('p', {}, linkCaso(x)), h('p', { class: 'discreto' }, `Na entrega: ${setorDe(x.entregue)} · ${etapaDe(x.entregue)}`))),
        lista('Pendências encerradas (resolvidas, canceladas ou do caso encerrado)', dif.pendenciasEncerradas,
          (x) => h('li', { class: 'cartao', 'data-pendencia': x.id },
            h('p', {}, h('strong', {}, x.descricao || 'Pendência'), ' — caso: ', linkCaso(x)),
            h('p', { class: 'discreto' }, `Na entrega: responsável ${responsavel(x.entregue)}, prazo `, dataHora(x.entregue.prazo, fuso)))),
      ],
      h('p', { class: 'discreto' }, 'A confirmação vale para o conteúdo entregue e para esta situação atual. Se algo mudar antes '
        + 'do clique, nada é confirmado e será preciso recarregar e conferir de novo.'),
      form.el);
  }

  function cancelamento(p) {
    const just = h('textarea', { maxlength: '500' });
    const form = criarFormulario({
      rotulo: 'Cancelar passagem',
      rotuloAcessivel: 'Cancelar passagem',
      classeBotao: 'botao-perigo',
      campos: [mensagem('info', 'Você entregou esta passagem: o recebimento deve ser confirmado por outro profissional.'),
        h('details', {}, h('summary', {}, 'Cancelar esta passagem'), campo('Justificativa do cancelamento', just))],
      recarregar: carregar,
      enviar: async () => {
        if (!just.value.trim()) throw new ErroApi(0, { detail: 'Informe a justificativa do cancelamento.' });
        await ctx.api.criar(`/api/plantao/passagens/${p.id}/cancelamento`, { versao: p.versao, justificativa: just.value.trim() });
        await carregar();
        ctx.anunciar('Passagem cancelada.');
      },
    });
    formularios.push(form);
    return h('section', { class: 'cartao', 'aria-label': 'Aguardando recebimento' }, h('h2', {}, 'Aguardando recebimento'), form.el);
  }

  carregar();
  return {
    desmontar() { ativo = false; },
    emEdicao: () => !concluido && (formularios.some((f) => f.sujo()) || (!id && observacaoDigitada.trim() !== '')),
  };
}
