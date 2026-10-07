// Pacientes travados (RF-018, CA-06): casos com alerta operacional ativo, calculado no servidor,
// com tempo, motivo, pendências (próxima ação, responsável, prazo), ação esperada e ciência.
// Distingue "nenhuma regra configurada" de "nenhum caso em alerta".

import { h, substituir, mensagem, carregando, etiqueta } from '../nucleo/dom.js';
import { mensagemDeErro, ErroApi } from '../nucleo/api.js';
import { criarAtualizador } from '../nucleo/atualizador.js';
import * as rotulos from '../nucleo/rotulos.js';
import { cronometro, dataHora, limite, avisoOperacional, situacaoRegras, atualizadoEm, preservandoFoco }
  from '../nucleo/componentes.js';

const INTERVALO_MS = 30000;

export function montar(raiz, ctx) {
  const fuso = ctx.fuso();
  let ativo = true;
  let cienciaEmAndamento = false;
  const situacao = h('div');
  const avisoCiencia = h('div', { 'aria-live': 'polite' });
  const lista = h('div', {}, carregando());

  substituir(raiz,
    h('div', { class: 'cabecalho-tela' }, h('h1', {}, 'Pacientes travados'),
      h('button', { type: 'button', class: 'botao-secundario', aoClicar: () => atualizador.atualizarAgora() }, 'Atualizar agora')),
    avisoOperacional(), situacao, avisoCiencia, lista);

  async function carregar() {
    try {
      const [t, regras] = await Promise.all([ctx.api.obter('/api/travados'), ctx.api.obter('/api/config/regras-alerta')]);
      if (!ativo) return;
      ctx.sincronizar(t.agora);
      desenhar(t, regras.filter((r) => r.ativa).length);
    } catch (e) {
      if (!ativo || (e && e.name === 'RespostaDescartada')) throw e;
      if (ctx.tratarErroGlobal(e)) return;
      substituir(situacao, mensagem('erro', `Falha ao atualizar: ${mensagemDeErro(e)}`));
      if (lista.querySelector('.carregando')) substituir(lista);
      throw e;
    }
  }

  function desenhar(t, regrasAtivas) {
    const agora = ctx.agora();
    substituir(situacao, atualizadoEm(t.agora, fuso, false), situacaoRegras(regrasAtivas, t.itens.length),
      t.truncado ? mensagem('aviso', 'A unidade tem mais episódios abertos do que o limite analisado; '
        + 'a lista pode estar incompleta.') : null);
    if (regrasAtivas === 0) { substituir(lista); return; }
    if (t.itens.length === 0) {
      substituir(lista, h('p', { class: 'vazio', role: 'status' }, 'Nenhum paciente travado pelas regras ativas.'));
      return;
    }
    const podeCiencia = ctx.pode('EPISODIO_ALTERAR');
    preservandoFoco(lista, () => substituir(lista, h('ul', { class: 'lista-travados' }, t.itens.map((c) => h('li', { class: 'cartao' },
      h('h2', {}, h('a', { href: `#/episodio/${c.episodioId}`, 'data-foco': `ep-${c.episodioId}` }, c.pacienteNome)),
      h('dl', { class: 'dados' },
        h('dt', {}, 'Setor / etapa'), h('dd', {}, `${c.setorNome} · ${c.etapaNome}`),
        h('dt', {}, 'Tempo total'), h('dd', {}, cronometro(c.entradaEm, agora)),
        h('dt', {}, 'Na etapa há'), h('dd', {}, cronometro(c.etapaDesde, agora)),
        h('dt', {}, 'Bloqueio'), h('dd', {}, c.bloqueioDesde
          ? [etiqueta('bloqueio', rotulos.categoria(c.categoriaBloqueio)), ' ', c.motivoBloqueio || '', ' há ',
            cronometro(c.bloqueioDesde, agora)] : 'Sem bloqueio'),
        h('dt', {}, 'Último registro'), h('dd', {}, dataHora(c.ultimoRegistroEm, fuso))),
      h('h3', {}, 'Pendências abertas'),
      c.pendencias.length === 0 ? h('p', { class: 'discreto' }, 'Nenhuma.') : h('ul', {}, c.pendencias.map((p) => h('li', {},
        h('strong', {}, p.descricao), ` — responsável: ${p.responsavel || '—'} — prazo: `, dataHora(p.prazo, fuso)))),
      h('h3', {}, 'Alertas'),
      h('ul', {}, c.alertas.map((a) => h('li', {},
        etiqueta('alerta', a.regraNome), ` ${rotulos.tipoRegra(a.tipo)} · limite ${limite(a.limiteMinutos)} · atingido em `,
        dataHora(a.atingidoEm, fuso),
        h('div', {}, 'Ação esperada: ', a.acaoEsperada || 'não definida na regra'),
        a.ciencia ? h('div', {}, etiqueta('ciente', `Ciência de ${a.ciencia.autorNome || '—'}`), ' em ',
          dataHora(a.ciencia.registradaEm, fuso))
          : (podeCiencia ? botaoCiencia(c, a) : etiqueta('neutro', 'Sem ciência'))))))))));
  }

  function botaoCiencia(c, a) {
    const botao = h('button', { type: 'button', class: 'botao-secundario', aoClicar: async () => {
      if (cienciaEmAndamento) return;
      cienciaEmAndamento = true;
      botao.disabled = true;
      substituir(avisoCiencia, h('p', { class: 'carregando', role: 'status' }, 'Registrando ciência…'));
      try {
        // Envia exatamente a versão da regra exibida; regra alterada => 409, nada gravado.
        await ctx.api.criar(`/api/episodios/${c.episodioId}/alertas/ciencia`, {
          regraId: a.regraId, regraVersao: a.regraVersao, referenciaEm: a.referenciaEm, pendenciaId: a.pendenciaId,
        });
        substituir(avisoCiencia, mensagem('sucesso', `Ciência registrada: ${a.regraNome} (${c.pacienteNome}).`));
      } catch (e) {
        if (e && e.name === 'RespostaDescartada') return;
        let texto = mensagemDeErro(e);
        if (e instanceof ErroApi && e.status === 409) {
          texto = 'A regra deste alerta foi alterada desde que você a visualizou. A lista foi recarregada: '
            + 'confira a versão atual e, se for o caso, registre a ciência novamente.';
        } else if (e instanceof ErroApi && e.status === 422) {
          texto = 'Este alerta não está mais ativo. A lista foi recarregada.';
        }
        substituir(avisoCiencia, mensagem('erro', texto));
      } finally {
        cienciaEmAndamento = false;
        if (ativo) await atualizador.atualizarAgora();
      }
    } }, 'Registrar ciência');
    botao.setAttribute('aria-label', `Registrar ciência: ${a.regraNome}, ${c.pacienteNome}`);
    botao.setAttribute('data-foco', `ciencia-${c.episodioId}-${a.regraId}-${a.referenciaEm}-${a.pendenciaId || ''}`);
    return h('div', { class: 'acoes' }, botao);
  }

  const atualizador = criarAtualizador({
    carregar, intervaloMs: INTERVALO_MS,
    emEdicao: () => cienciaEmAndamento,
    aoMudarSituacao: (s) => { if (ativo) ctx.situacaoAtualizacao(s); },
  });
  atualizador.atualizarAgora();
  atualizador.iniciar();
  return { desmontar() { ativo = false; atualizador.parar(); }, emEdicao: () => false };
}
