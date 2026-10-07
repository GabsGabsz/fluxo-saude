// Tela de indicadores com o módulo REAL e um DOM mínimo (revisão do PR #10, apresentação): as
// limitações de interpretação aparecem JUNTO dos resultados a que se referem (limite vigente
// aplicado ao histórico; setor atual/final), e as fórmulas continuam marcadas como proposta (V-09).
import test from 'node:test';
import assert from 'node:assert/strict';
import { No, esperar } from './dom-minimo.mjs';

const { montar } = await import('../../main/resources/static/app/js/telas/indicadores.js');

const SETOR = '00000000-0000-0000-0000-0000000000a1';
const duracoes = (n, media) => ({ incluidos: n, naoIncluidos: 0, mediaMin: media, medianaMin: media, minimoMin: media, maximoMin: media });

function resposta(setor) {
  return {
    agora: '2026-10-07T12:00:00Z', fuso: 'America/Fortaleza', inicio: '2026-10-01', fim: '2026-10-07',
    inicioEm: '2026-10-01T03:00:00Z', fimEm: '2026-10-08T03:00:00Z', setor,
    retrato: { agora: '2026-10-07T12:00:00Z', itens: [{ dimensao: 'ABERTOS', chave: null, nome: null, quantidade: 3 }],
      regrasConfiguradas: true, acimaDosLimitesDisponivel: true, acimaDosLimites: [] },
    historico: {
      permanencia: duracoes(2, 300), desfechos: [{ desfecho: 'ALTA', quantidade: 2 }], transferencias: 0,
      acimaDosLimites: [{ regra: { regraId: 'r1', regraNome: 'Permanencia total', regraVersao: 3, limiteMin: 240, populacao: 2, acima: 1 },
        percentual: { valor: 50 } }],
      solicitacaoAceite: duracoes(0, null), aceiteSaida: duracoes(0, null),
      motivos: [{ motivo: { motivoId: 'm1', codigo: 'SEM_VAGA', descricao: 'Sem vaga', categoria: 'REGULACAO', minutos: 60, inicios: 1,
        episodios: 1 }, percentualDoTempo: { valor: 100 } }],
      minutosBloqueadosTotal: 60, volumeDiario: [{ dia: '2026-10-07', entradas: 1, saidas: 2 }],
    },
  };
}

function contexto(r) {
  return {
    catalogo: () => ({ setores: [{ id: SETOR, nome: 'Observação' }] }),
    fuso: () => 'America/Fortaleza',
    agora: () => Date.parse('2026-10-07T12:00:00Z'),
    api: { obter: async (url) => (url.startsWith('/api/indicadores/dicionario') ? [] : r) },
    sincronizar() {}, tratarErroGlobal: () => false,
  };
}

/** Texto entre o título h3 informado e o próximo h3 (o "bloco" do resultado). */
function bloco(secao, titulo) {
  const filhos = secao.filhos;
  const i = filhos.findIndex((n) => n.tagName === 'h3' && n.textContent.startsWith(titulo));
  assert.ok(i >= 0, `título ${titulo}`);
  const fim = filhos.findIndex((n, j) => j > i && n.tagName === 'h3');
  return filhos.slice(i, fim < 0 ? undefined : fim).map((n) => n.textContent).join(' ');
}

const historico = (raiz) => raiz.todos((n) => n.tagName === 'section' && n.getAttribute('aria-labelledby') === 'tit-hist')[0];

test('limite vigente: limitação junto dos "encerrados acima dos limites"', async () => {
  const raiz = new No('main');
  montar(raiz, contexto(resposta(null)));
  await esperar(); await esperar();
  const hist = historico(raiz);
  assert.match(bloco(hist, 'Encerrados acima dos limites'), /Limitação: Usa o limite VIGENTE/);
  assert.match(bloco(hist, 'Encerrados acima dos limites'), /Proposta \(V-09\)/);
  assert.doesNotMatch(hist.textContent, /setor ATUAL/, 'sem filtro de setor, sem a limitação de setor');
  for (const t of ['Permanência', 'Saídas por desfecho', 'Tempos de transferência', 'Motivos', 'Volume diário']) {
    assert.match(bloco(hist, t), /Proposta \(V-09\)/, `${t}: fórmula marcada como proposta`);
  }
});

test('filtro de setor: limitação de setor atual/final junto do histórico e dos motivos', async () => {
  const raiz = new No('main');
  montar(raiz, contexto(resposta(SETOR)));
  await esperar(); await esperar();
  const hist = historico(raiz);
  assert.match(hist.textContent, /Limitação: Filtro de setor: cada episódio conta no setor ATUAL \(abertos\) ou FINAL/);
  assert.match(bloco(hist, 'Motivos'), /Limitação: O tempo bloqueado é atribuído ao setor atual\/final/);
});
