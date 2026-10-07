// Tela de passagem de plantão com o módulo REAL e um DOM mínimo (revisão do PR #10, ponto 2):
// o recebimento assina a situação atual, então a tela precisa mostrar os valores NOVOS de cada
// mudança (antes × agora), vinculando a pendência ao caso, e separar o conteúdo entregue.
import test from 'node:test';
import assert from 'node:assert/strict';

import { No } from './dom-minimo.mjs';

const { montar } = await import('../../main/resources/static/app/js/telas/plantao.js');
const rotulos = await import('../../main/resources/static/app/js/nucleo/rotulos.js');

const ID = '00000000-0000-0000-0000-0000000000aa';
const EP = '00000000-0000-0000-0000-0000000000e1';
const SETOR = '00000000-0000-0000-0000-000000000051';
const ENTREGUE_EM = '2026-10-07T10:00:00Z';

const pendEntregue = { id: 'p1', descricao: 'Confirmar vaga de retaguarda', versao: 0, categoria: 'LOGISTICA', criticidade: 'MEDIA',
  prazo: '2026-10-07T14:00:00Z', vencida: false, responsavelUsuarioId: null, responsavelSetorId: SETOR, responsavelPapel: null,
  responsavelNome: 'Observação Norte' };
const pendAtual = { ...pendEntregue, versao: 1, prazo: '2026-10-07T18:00:00Z', responsavelSetorId: null, responsavelPapel: 'MEDICO',
  responsavelNome: null };
const casoBase = { episodioId: EP, pacienteNome: 'Paciente Ficticio Tela', versao: 0, etapaId: 'et1', etapaNome: 'Em atendimento',
  setorId: SETOR, setorNome: 'Observação Norte', motivoId: null, motivoDescricao: null, categoria: null, bloqueioDesde: null,
  entradaEm: '2026-10-07T08:00:00Z', etapaDesde: '2026-10-07T08:00:00Z', critico: false, transferencia: false, alertas: [] };
const casoAtual = { ...casoBase, versao: 2, etapaId: 'et2', etapaNome: 'Aguardando recurso/leito', etapaDesde: '2026-10-07T11:00:00Z',
  motivoId: 'm1', motivoDescricao: 'Sem leito na especialidade', categoria: 'LEITO_CAPACIDADE', bloqueioDesde: '2026-10-07T11:00:00Z',
  pendencias: [] };

const detalhe = {
  agora: '2026-10-07T12:00:00Z',
  passagem: { id: ID, status: 'ENTREGUE', periodoInicio: null, entreguePor: 'outro', entreguePorNome: 'Enfermagem', entregueEm: ENTREGUE_EM,
    totalCasos: 1, totalCriticos: 0, totalTransferencias: 0, totalPendencias: 1, totalVencidas: 0, observacao: null, versao: 0 },
  integra: true,
  totais: { casos: 1, criticos: 0, transferencias: 0, pendencias: 1, vencidas: 0 },
  casos: [{ ...casoBase, pendencias: [pendEntregue] }],
  totaisAtuais: { casos: 1, criticos: 0, transferencias: 0, pendencias: 1, vencidas: 0 },
  diferencas: {
    casosEncerrados: [], casosNovos: [], pendenciasEncerradas: [], pendenciasNovas: [],
    casosAlterados: [{ tipo: 'ALTERADO', episodioId: EP, pacienteNome: 'Paciente Ficticio Tela', campos: ['ETAPA', 'MOTIVO_BLOQUEIO'],
      entregue: { ...casoBase, pendencias: [] }, atual: casoAtual }],
    pendenciasAlteradas: [{ tipo: 'ALTERADO', id: 'p1', episodioId: EP, pacienteNome: 'Paciente Ficticio Tela',
      descricao: 'Confirmar vaga de retaguarda', campos: ['RESPONSAVEL', 'PRAZO'], entregue: pendEntregue, atual: pendAtual }],
    contagens: { casosEncerrados: 0, casosNovos: 0, casosAlterados: 1, pendenciasEncerradas: 0, pendenciasNovas: 0, pendenciasAlteradas: 1 },
  },
  assinaturaRecebimento: 'a'.repeat(64),
};

function contexto(resposta) {
  return {
    catalogo: () => ({ etapas: [], setores: [], motivos: [], profissionais: [] }),
    fuso: () => 'America/Fortaleza',
    estado: { sessao: () => ({ usuarioId: 'eu' }) },
    api: { obter: async (url) => (url === '/api/config/regras-alerta' ? [] : resposta) },
    sincronizar() {}, agora: () => Date.parse('2026-10-07T12:00:00Z'), tratarErroGlobal: () => false,
    notificar() {}, irPara() {}, anunciar() {},
  };
}

const esperar = () => new Promise((ok) => setTimeout(ok, 0));
const regiao = (raiz, nome) => raiz.todos((n) => n.tagName === 'section' && n.getAttribute('aria-label') === nome)[0];

test('recebimento: situação atual com antes × agora de responsável, prazo, etapa e motivo; pendência ligada ao caso', async () => {
  const raiz = new No('main');
  montar(raiz, contexto(detalhe), { id: ID });
  await esperar(); await esperar();
  const atual = regiao(raiz, 'Recebimento');
  assert.ok(atual, 'seção de recebimento');
  const t = atual.textContent;
  assert.match(t, /Situação atual/);
  assert.match(t, /Na entrega/);
  assert.match(t, /Agora/);
  // Pendência: responsável anterior E novo; prazo anterior E novo; vinculada ao caso (link).
  assert.match(t, /Setor: Observação Norte/, 'responsável na entrega');
  assert.ok(t.includes(`Perfil: ${rotulos.papel('MEDICO')}`), 'NOVO responsável exibido');
  assert.match(t, /07\/10\/2026 11:00/, 'prazo na entrega (14:00Z no fuso da unidade)');
  assert.match(t, /07\/10\/2026 15:00/, 'NOVO prazo exibido');
  const pend = atual.todos((n) => n.getAttribute('data-pendencia') === 'p1')[0];
  assert.ok(pend.todos((n) => n.tagName === 'a' && n.getAttribute('href') === `#/episodio/${EP}`).length === 1, 'pendência → caso');
  // Caso: etapa e motivo antes × agora.
  assert.match(t, /Em atendimento/);
  assert.match(t, /Aguardando recurso\/leito/, 'NOVA etapa exibida');
  assert.match(t, /Sem leito na especialidade/, 'NOVO motivo exibido');
  assert.match(t, /Confirmar recebimento/);
  // Conteúdo entregue separado e marcado como mudado.
  const entregue = regiao(raiz, 'Conteúdo entregue');
  assert.ok(entregue, 'seção do conteúdo entregue');
  assert.match(entregue.textContent, /Como estava na entrega/);
  assert.match(entregue.textContent, /Mudou depois da entrega/);
  assert.doesNotMatch(entregue.textContent, /Aguardando recurso\/leito/, 'conteúdo entregue não mistura a situação atual');
});

test('recebimento sem diferenças: informa e mantém o conteúdo entregue', async () => {
  const semDif = { ...detalhe, diferencas: { casosEncerrados: [], casosNovos: [], casosAlterados: [], pendenciasEncerradas: [],
    pendenciasNovas: [], pendenciasAlteradas: [], contagens: { casosEncerrados: 0, casosNovos: 0, casosAlterados: 0,
      pendenciasEncerradas: 0, pendenciasNovas: 0, pendenciasAlteradas: 0 } } };
  const raiz = new No('main');
  montar(raiz, contexto(semDif), { id: ID });
  await esperar(); await esperar();
  assert.match(regiao(raiz, 'Recebimento').textContent, /Nenhuma diferença entre o conteúdo entregue e a situação atual/);
  assert.doesNotMatch(regiao(raiz, 'Conteúdo entregue').textContent, /Mudou depois da entrega/);
});

// ------------------------------------------------------------------ alertas (revisão do PR #10)
// O recebimento assina os alertas do conteúdo atual; a tela precisa identificar QUAL alerta saiu e
// QUAL entrou, com a regra NA VERSÃO do alerta — não só "1 → 1", nem a configuração atual.
const RA = '00000000-0000-0000-0000-0000000000a1';
const RB = '00000000-0000-0000-0000-0000000000b2';
const RC = '00000000-0000-0000-0000-0000000000c3';
const alertaA = { regraId: RA, regraVersao: 0, tipo: 'TEMPO_TOTAL', referenciaEm: '2026-10-07T08:00:00Z', atingidoEm: '2026-10-07T09:00:00Z',
  pendenciaId: null, pendenciaDescricao: null, regra: { nome: 'Permanencia longa (A)', limiteMinutos: 60, acaoEsperada: 'Avisar coordenacao', ativa: true },
  regraAtual: { versao: 1, ativa: false } };
const alertaB = { regraId: RB, regraVersao: 0, tipo: 'TEMPO_BLOQUEADO', referenciaEm: '2026-10-07T09:30:00Z', atingidoEm: '2026-10-07T11:30:00Z',
  pendenciaId: null, pendenciaDescricao: null, regra: { nome: 'Bloqueio prolongado (B)', limiteMinutos: 120, acaoEsperada: 'Acionar NIR', ativa: true },
  regraAtual: { versao: 0, ativa: true } };
const alertaC = { regraId: RC, regraVersao: 3, tipo: 'PENDENCIA_VENCIDA', referenciaEm: '2026-10-07T10:00:00Z', atingidoEm: '2026-10-07T10:00:00Z',
  pendenciaId: 'p1', pendenciaDescricao: 'Confirmar vaga de retaguarda', regra: { nome: 'Pendencia vencida (C)', limiteMinutos: null,
    acaoEsperada: null, ativa: true }, regraAtual: { versao: 3, ativa: true } };

function detalheComAlertas(entregues, atuais, mudancas) {
  const ent = { ...casoBase, critico: true, alertas: entregues, pendencias: [] };
  const atu = { ...casoBase, critico: true, alertas: atuais, pendencias: [] };
  return { ...detalhe, casos: [{ ...ent, pendencias: [pendEntregue] }],
    diferencas: { ...detalhe.diferencas, pendenciasAlteradas: [],
      casosAlterados: [{ tipo: 'ALTERADO', episodioId: EP, pacienteNome: 'Paciente Ficticio Tela', campos: ['ALERTAS'],
        entregue: ent, atual: atu, alertas: mudancas }],
      contagens: { ...detalhe.diferencas.contagens, pendenciasAlteradas: 0 } } };
}

const celula = (raiz, regraId, mudanca, coluna) => {
  const linha = regiao(raiz, 'Recebimento').todos((n) => n.tagName === 'tr' && n.getAttribute('data-alerta') === regraId
    && n.getAttribute('data-mudanca') === mudanca)[0];
  assert.ok(linha, `linha do alerta ${regraId} (${mudanca})`);
  return linha.todos((n) => n.getAttribute('data-rotulo') === coluna)[0].textContent;
};

test('alertas: troca de A por B com a MESMA quantidade identifica o que saiu e o que entrou, com regra, versão e instantes', async () => {
  const urls = [];
  const ctx = contexto(detalheComAlertas([alertaA, alertaC], [alertaB, alertaC], [
    { tipo: 'REMOVIDO', campos: [], entregue: alertaA, atual: null },
    { tipo: 'ADICIONADO', campos: [], entregue: null, atual: alertaB },
    { tipo: 'MANTIDO', campos: [], entregue: alertaC, atual: alertaC }]));
  const obter = ctx.api.obter;
  ctx.api.obter = (u) => { urls.push(u); return obter(u); };
  const raiz = new No('main');
  montar(raiz, ctx, { id: ID });
  await esperar(); await esperar();
  // A saiu: regra e versão entregues, situação atual da regra e "não está mais em alerta".
  assert.match(celula(raiz, RA, 'REMOVIDO', 'Na entrega'), /Permanencia longa \(A\) — versão 0; Tempo total; limite: 1 h 00 min — ação esperada: Avisar coordenacao/);
  assert.match(celula(raiz, RA, 'REMOVIDO', 'Na entrega'), /referência: 07\/10\/2026 05:00; limite atingido em 07\/10\/2026 06:00/);
  assert.match(celula(raiz, RA, 'REMOVIDO', 'Na entrega'), /Regra desativada depois \(versão atual 1\)/);
  assert.match(celula(raiz, RA, 'REMOVIDO', 'Agora'), /Não está mais em alerta/);
  // B entrou: identificado por nome, versão, limite, ação, referência e instante atingido.
  assert.match(celula(raiz, RB, 'ADICIONADO', 'Agora'), /Bloqueio prolongado \(B\) — versão 0; Tempo bloqueado; limite: 2 h 00 min — ação esperada: Acionar NIR/);
  assert.match(celula(raiz, RB, 'ADICIONADO', 'Agora'), /referência: 07\/10\/2026 06:30; limite atingido em 07\/10\/2026 08:30/);
  assert.match(celula(raiz, RB, 'ADICIONADO', 'Na entrega'), /Não havia/);
  // C mantido, com o vínculo à pendência.
  assert.match(celula(raiz, RC, 'MANTIDO', 'Agora'), /pendência: Confirmar vaga de retaguarda/);
  assert.ok(!urls.includes('/api/config/regras-alerta'), 'a tela não busca a configuração atual para rotular alertas históricos');
});

test('alertas: mesma regra em outra versão mostra os dados de CADA versão (não os atuais no lugar dos entregues)', async () => {
  const v0 = { ...alertaA, regra: { nome: 'Permanencia 6h', limiteMinutos: 360, acaoEsperada: 'Avisar coordenacao', ativa: true },
    regraAtual: { versao: 1, ativa: true } };
  const v1 = { ...alertaA, regraVersao: 1, regra: { nome: 'Permanencia 4h', limiteMinutos: 240, acaoEsperada: 'Avisar direcao', ativa: true },
    regraAtual: { versao: 1, ativa: true } };
  const raiz = new No('main');
  montar(raiz, contexto(detalheComAlertas([v0], [v1], [{ tipo: 'ALTERADO', campos: ['VERSAO_REGRA'], entregue: v0, atual: v1 }])), { id: ID });
  await esperar(); await esperar();
  const antes = celula(raiz, RA, 'ALTERADO', 'Na entrega');
  const agora = celula(raiz, RA, 'ALTERADO', 'Agora');
  assert.match(antes, /Permanencia 6h — versão 0; Tempo total; limite: 6 h 00 min — ação esperada: Avisar coordenacao/);
  assert.match(antes, /Regra alterada depois \(versão atual 1\)/);
  assert.match(agora, /Permanencia 4h — versão 1; Tempo total; limite: 4 h 00 min — ação esperada: Avisar direcao/);
  assert.doesNotMatch(antes, /Permanencia 4h|Avisar direcao/, 'versão entregue sem dados da versão atual');
  assert.match(regiao(raiz, 'Recebimento').textContent, /Alerta alterado \(versão da regra\)/);
});

test('alertas: versão anterior ao histórico aparece como indisponível, nunca com o nome atual', async () => {
  const semHistorico = { ...alertaA, regra: null, regraAtual: { versao: 4, ativa: true } };
  const raiz = new No('main');
  const r = { ...detalhe, casos: [{ ...casoBase, critico: true, alertas: [semHistorico], pendencias: [] }] };
  montar(raiz, contexto(r), { id: ID });
  await esperar(); await esperar();
  const entregue = regiao(raiz, 'Conteúdo entregue').todos((n) => n.getAttribute('data-alerta') === RA)[0].textContent;
  assert.match(entregue, /Regra \(Tempo total\) — versão 0; Tempo total — detalhes desta versão da regra indisponíveis/);
  assert.match(entregue, /Regra alterada depois \(versão atual 4\)/);
});
