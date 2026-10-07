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
