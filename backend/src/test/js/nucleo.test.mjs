// Testes de unidade do núcleo da interface (node --test, sem dependências).
import test from 'node:test';
import assert from 'node:assert/strict';
import { criarApi, consulta, mensagemDeErro, ErroApi, ErroConexao, RespostaDescartada } from '../../main/resources/static/app/js/nucleo/api.js';
import { criarEstado, telasPermitidas } from '../../main/resources/static/app/js/nucleo/estado.js';
import { criarRelogio, formatarDuracao, decorrido, formatarDataHora, localDaUnidadeParaIso, isoParaLocalDaUnidade }
  from '../../main/resources/static/app/js/nucleo/tempo.js';
import { criarAtualizador } from '../../main/resources/static/app/js/nucleo/atualizador.js';
import * as rotulos from '../../main/resources/static/app/js/nucleo/rotulos.js';

function resposta(status, corpo) {
  return { ok: status >= 200 && status < 300, status, text: async () => (corpo === undefined ? '' : JSON.stringify(corpo)) };
}

test('api: GET sem CSRF; escrita busca token antes e envia X-XSRF-TOKEN', async () => {
  let cookie = '';
  const chamadas = [];
  const api = criarApi({
    geracao: () => 1,
    lerCookie: (n) => (n === 'XSRF-TOKEN' ? cookie : ''),
    fetch: async (url, op) => {
      chamadas.push({ url, op });
      if (url === '/api/sessao/csrf') { cookie = 'tok123'; return resposta(200, { cabecalho: 'X-XSRF-TOKEN' }); }
      return resposta(200, { ok: true });
    },
  });
  await api.obter('/api/episodios');
  assert.equal(chamadas.length, 1);
  assert.equal(chamadas[0].op.headers['X-XSRF-TOKEN'], undefined);
  assert.equal(chamadas[0].op.credentials, 'same-origin');
  assert.equal(chamadas[0].op.cache, 'no-store');
  await api.criar('/api/episodios', { a: 1 });
  assert.deepEqual(chamadas.map((c) => c.url), ['/api/episodios', '/api/sessao/csrf', '/api/episodios']);
  assert.equal(chamadas[2].op.headers['X-XSRF-TOKEN'], 'tok123');
  assert.equal(chamadas[2].op.body, '{"a":1}');
});

test('api: só caminhos /api/', async () => {
  const api = criarApi({ geracao: () => 1, lerCookie: () => 'x', fetch: async () => resposta(200, {}) });
  await assert.rejects(() => api.obter('https://evil.example/api/x'), /inválido/);
});

test('api: resposta de geração anterior é descartada (troca de unidade, saída)', async () => {
  let geracao = 1;
  let liberar;
  const api = criarApi({
    geracao: () => geracao, lerCookie: () => 'x',
    fetch: () => new Promise((ok) => { liberar = () => ok(resposta(200, { dado: 'unidade antiga' })); }),
  });
  const pendente = api.obter('/api/episodios');
  geracao = 2; // usuário trocou de unidade enquanto a resposta não chegava
  liberar();
  await assert.rejects(pendente, RespostaDescartada);
});

test('api: 401 aciona encerramento da sessão; problem+json vira ErroApi; rede vira ErroConexao', async () => {
  let encerrou = null;
  let modo = '401';
  const api = criarApi({
    geracao: () => 1, lerCookie: () => 'x', aoEncerrarSessao: (e) => { encerrou = e; },
    fetch: async () => {
      if (modo === 'rede') throw new TypeError('Failed to fetch');
      if (modo === '409') return resposta(409, { codigo: 'CONFLITO_DE_VERSAO', detail: 'mudou' });
      return resposta(401, { codigo: 'SESSAO_REVOGADA', detail: 'Seu acesso foi alterado.' });
    },
  });
  await assert.rejects(api.obter('/api/sessao'), (e) => e instanceof ErroApi && e.codigo === 'SESSAO_REVOGADA');
  assert.equal(encerrou.codigo, 'SESSAO_REVOGADA');
  modo = '409';
  await assert.rejects(api.substituir('/api/x', {}), (e) => e.conflito && /Atualize a tela/.test(mensagemDeErro(e)));
  modo = 'rede';
  await assert.rejects(api.obter('/api/x'), (e) => e instanceof ErroConexao && /desatualizados/.test(mensagemDeErro(e)));
});

test('api: falha de login (401) não dispara "sessão encerrada"', async () => {
  let encerrou = false;
  const api = criarApi({ geracao: () => 1, lerCookie: () => 'x', aoEncerrarSessao: () => { encerrou = true; },
    fetch: async () => resposta(401, { codigo: 'CREDENCIAIS_INVALIDAS' }) });
  await assert.rejects(api.executar('POST', '/api/sessao', {}, { login: true }));
  assert.equal(encerrou, false);
});

test('consulta: ignora vazios, codifica valores', () => {
  assert.equal(consulta({ a: '', b: null, c: 'x y', d: false, e: true, f: 0 }), '?c=x+y&e=true&f=0');
  assert.equal(consulta({}), '');
});

test('estado: geração muda ao trocar de unidade, sair ou trocar de usuário; não muda em atualização da mesma sessão', () => {
  const e = criarEstado();
  const s1 = { usuarioId: 'u1', unidadeAtiva: 'A', permissoes: ['EPISODIO_VER'], deveTrocarSenha: false };
  e.definirSessao(s1);
  const g1 = e.geracao();
  e.definirCatalogo({ unidade: { nome: 'A' } });
  e.definirSessao({ ...s1 });
  assert.equal(e.geracao(), g1, 'mesma sessão/unidade');
  assert.ok(e.catalogo(), 'catálogo mantido');
  e.definirSessao({ ...s1, unidadeAtiva: 'B' });
  assert.ok(e.geracao() > g1);
  assert.equal(e.catalogo(), null, 'catálogo da unidade anterior descartado');
  const g2 = e.geracao();
  e.limpar();
  assert.ok(e.geracao() > g2);
  assert.equal(e.sessao(), null);
});

test('estado: permissões e troca de senha pendente', () => {
  const e = criarEstado();
  e.definirSessao({ usuarioId: 'u', unidadeAtiva: 'A', permissoes: ['PAINEL_COLETIVO_VER'], deveTrocarSenha: false });
  assert.deepEqual(telasPermitidas(e.pode).map((t) => t.rota), ['painel']);
  e.definirSessao({ usuarioId: 'u', unidadeAtiva: 'A', permissoes: ['EPISODIO_VER'], deveTrocarSenha: true });
  assert.equal(e.pode('EPISODIO_VER'), false);
  e.definirSessao({ usuarioId: 'u', unidadeAtiva: 'A', permissoes: ['USUARIO_GERENCIAR', 'CONFIGURACAO_GERENCIAR'], deveTrocarSenha: false });
  assert.deepEqual(telasPermitidas(e.pode).map((t) => t.rota), ['usuarios', 'regras']);
});

test('relógio: usa o "agora" do servidor como referência', () => {
  let local = 1_000_000;
  const r = criarRelogio(() => local);
  r.sincronizar('2026-10-06T12:00:00Z');
  assert.equal(new Date(r.agora()).toISOString(), '2026-10-06T12:00:00.000Z');
  local += 90_000;
  assert.equal(new Date(r.agora()).toISOString(), '2026-10-06T12:01:30.000Z');
  assert.equal(formatarDuracao(decorrido('2026-10-06T09:55:00Z', r.agora())), '2 h 06 min');
});

test('duração: formatos e limites', () => {
  assert.equal(formatarDuracao(59_999), '0 min');
  assert.equal(formatarDuracao(60_000), '1 min');
  assert.equal(formatarDuracao(3_600_000 + 5 * 60_000), '1 h 05 min');
  assert.equal(formatarDuracao(25 * 3_600_000 + 6 * 60_000), '1 d 01 h');
  assert.equal(formatarDuracao(-5), '0 min');
  assert.equal(formatarDuracao(null), '—');
});

test('fuso da unidade: exibição e conversão do campo de data/hora', () => {
  assert.equal(formatarDataHora('2026-10-06T12:00:00Z', 'America/Fortaleza'), '06/10/2026 09:00');
  assert.equal(formatarDataHora('2026-10-06T12:00:00Z', 'America/Manaus'), '06/10/2026 08:00');
  assert.equal(localDaUnidadeParaIso('2026-10-06T09:00', 'America/Fortaleza'), '2026-10-06T12:00:00.000Z');
  assert.equal(localDaUnidadeParaIso('2026-10-06T08:00', 'America/Manaus'), '2026-10-06T12:00:00.000Z');
  assert.equal(localDaUnidadeParaIso('invalido', 'America/Fortaleza'), null);
  assert.equal(isoParaLocalDaUnidade(Date.parse('2026-10-06T12:00:00Z'), 'America/Fortaleza'), '2026-10-06T09:00');
  // Fuso com horário de verão (ida e volta)
  assert.equal(localDaUnidadeParaIso('2026-07-01T09:00', 'Europe/Lisbon'), '2026-07-01T08:00:00.000Z');
});

test('atualizador: não sobrepõe leituras, pausa durante edição e sinaliza falha', async () => {
  const agendados = [];
  let leituras = 0;
  let simultaneas = 0;
  let maxSimultaneas = 0;
  let liberar;
  let edicao = false;
  const situacoes = [];
  const a = criarAtualizador({
    intervaloMs: 1000,
    emEdicao: () => edicao,
    aoMudarSituacao: (s) => situacoes.push(s),
    agendar: (f) => { agendados.push(f); return agendados.length; },
    cancelar: () => {},
    carregar: () => {
      leituras += 1;
      simultaneas += 1;
      maxSimultaneas = Math.max(maxSimultaneas, simultaneas);
      return new Promise((ok, falha) => {
        liberar = { ok: () => { simultaneas -= 1; ok(); }, falha: (e) => { simultaneas -= 1; falha(e); } };
      });
    },
  });
  a.iniciar();
  const primeiro = agendados.shift();
  const ciclo = primeiro();
  assert.equal(leituras, 1);
  const manual = a.atualizarAgora();
  assert.equal(leituras, 1, 'manual durante leitura em andamento não sobrepõe');
  liberar.falha(new Error('rede'));
  await new Promise((r) => setImmediate(r));
  assert.equal(leituras, 2, 'o pedido manual roda UMA vez, depois da leitura em andamento');
  liberar.falha(new Error('rede'));
  await ciclo;
  await manual;
  assert.equal(maxSimultaneas, 1, 'nunca duas leituras ao mesmo tempo');
  assert.equal(agendados.length, 1, 'só um próximo ciclo agendado');
  assert.deepEqual(situacoes.at(-1), { pausado: false, falhou: true });
  edicao = true;
  await agendados.shift()();
  assert.equal(leituras, 2, 'pausado durante edição');
  assert.deepEqual(situacoes.at(-1), { pausado: true, falhou: true });
  edicao = false;
  const outro = agendados.shift()();
  liberar.ok();
  await outro;
  assert.equal(leituras, 3);
  assert.deepEqual(situacoes.at(-1), { pausado: false, falhou: false });
  a.parar();
});

test('atualizador: resposta descartada (troca de unidade) não marca dados como desatualizados', async () => {
  const situacoes = [];
  const a = criarAtualizador({
    intervaloMs: 1000, agendar: () => 1, cancelar: () => {},
    aoMudarSituacao: (s) => situacoes.push(s),
    carregar: async () => { throw new RespostaDescartada(); },
  });
  assert.equal(await a.atualizarAgora(), false);
  assert.deepEqual(situacoes.at(-1), { pausado: false, falhou: false });
});

test('rótulos: criticidade é operacional; valor desconhecido aparece como veio', () => {
  assert.equal(rotulos.criticidade('ALTA'), 'Alta');
  assert.equal(rotulos.papel('NOVO_PAPEL'), 'NOVO_PAPEL');
  assert.equal(rotulos.responsavel({ responsavelSetorNome: 'NIR' }), 'Setor: NIR');
  assert.equal(rotulos.responsavel({ responsavelPapel: 'TRANSPORTE' }), 'Perfil: Transporte');
});

test('eventos: descrição traduz códigos pelo catálogo da unidade e não inventa', async () => {
  const { descreverEvento } = await import('../../main/resources/static/app/js/nucleo/eventos.js');
  const cat = {
    unidade: { fusoHorario: 'America/Manaus' },
    etapas: [{ codigo: 'EM_ATENDIMENTO', nome: 'Em atendimento' }, { codigo: 'ACEITO', nome: 'Aceito' }],
    setores: [{ id: 's1', nome: 'Observação' }],
    motivos: [{ codigo: 'SEM_VAGA', descricao: 'Sem vaga' }],
    especialidades: [],
  };
  assert.equal(descreverEvento({ tipo: 'ETAPA_ALTERADA', dados: { de: 'EM_ATENDIMENTO', para: 'ACEITO' } }, cat), 'Em atendimento → Aceito');
  assert.equal(descreverEvento({ tipo: 'BLOQUEIO_DEFINIDO', dados: { motivo: 'SEM_VAGA', categoria: 'REGULACAO' } }, cat), 'Sem vaga (Regulação)');
  assert.equal(descreverEvento({ tipo: 'PENDENCIA_ATUALIZADA', dados: { campo: 'prazo', de: '2026-10-06T12:00:00Z', para: '2026-10-06T14:00:00Z' } }, cat),
    'Prazo: 06/10/2026 08:00 → 06/10/2026 10:00', 'prazos no fuso da unidade');
  assert.equal(descreverEvento({ tipo: 'SETOR_ALTERADO', dados: { de: 's1', para: 'outro' } }, cat), 'Observação → setor não listado');
  assert.equal(descreverEvento({ tipo: 'DESCONHECIDO', dados: { x: 1 } }, cat), '');
  assert.equal(descreverEvento({ tipo: 'ETAPA_ALTERADA', dados: null }, cat), '');
});
