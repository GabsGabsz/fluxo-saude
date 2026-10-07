// Corridas entre uma operação e a mudança de contexto (troca de unidade, saída, sessão encerrada),
// com controle SEPARADO e determinístico de cada etapa: espera do token CSRF, chegada dos
// cabeçalhos e conclusão do corpo. Usa o módulo real (api.js) e o estado real (estado.js).
import testeBase from 'node:test';
import assert from 'node:assert/strict';
import { criarApi, ErroApi, RespostaDescartada } from '../../main/resources/static/app/js/nucleo/api.js';
import { criarEstado } from '../../main/resources/static/app/js/nucleo/estado.js';

// Cada teste tem prazo curto: uma regressão que deixe a operação pendurada FALHA (não trava o CI).
const test = (nome, corpo) => testeBase(nome, { timeout: 2000 }, corpo);

function adiado() {
  let ok;
  let falha;
  const promessa = new Promise((a, b) => { ok = a; falha = b; });
  return { promessa, ok, falha };
}

/** Servidor falso em que cada etapa de cada requisição é liberada pelo teste. */
function servidorControlado() {
  const etapas = { csrf: [], cabecalhos: [], corpos: [] };
  const enviados = [];
  const corposLidos = [];
  let cookie = '';
  async function fetch(url, op) {
    if (url === '/api/sessao/csrf') {
      const d = adiado();
      etapas.csrf.push(d);
      await d.promessa;
      cookie = 'token-novo';
      return { ok: true, status: 200, text: async () => '{}' };
    }
    enviados.push({ url, metodo: op.method, cabecalhos: { ...op.headers }, corpo: op.body });
    const cab = adiado();
    etapas.cabecalhos.push(cab);
    const { status, corpo } = await cab.promessa;
    const fimCorpo = adiado();
    etapas.corpos.push({ liberar: () => fimCorpo.ok(corpo === undefined ? '' : JSON.stringify(corpo)) });
    return { ok: status >= 200 && status < 300, status, text: () => { corposLidos.push(url); return fimCorpo.promessa; } };
  }
  return { fetch, etapas, enviados, corposLidos, lerCookie: (n) => (n === 'XSRF-TOKEN' ? cookie : ''), limparCookie: () => { cookie = ''; } };
}

/** Sessão real (estado.js) na unidade A, como a interface mantém. */
function sessaoEm(estado, unidade) {
  estado.definirSessao({ usuarioId: 'u1', login: 'coord', nome: 'Coord', unidadeAtiva: unidade, lotacoes: [],
    permissoes: ['EPISODIO_VER'], deveTrocarSenha: false });
}

function montar() {
  const srv = servidorControlado();
  const estado = criarEstado();
  sessaoEm(estado, 'unidade-A');
  const chamadas = { encerrar: 0, mudarUnidade: 0 };
  const api = criarApi({
    fetch: srv.fetch, lerCookie: srv.lerCookie, geracao: () => estado.geracao(),
    unidadeEsperada: () => (estado.sessao() ? estado.sessao().unidadeAtiva : null),
    aoEncerrarSessao: () => { chamadas.encerrar += 1; },
    aoMudarUnidade: () => { chamadas.mudarUnidade += 1; },
  });
  return { srv, estado, api, chamadas };
}

const tick = () => new Promise((r) => setImmediate(r));

/**
 * Libera tudo o que tiver sido enviado (para a operação terminar mesmo numa implementação com
 * defeito). Assim o teste falha pela asserção certa ("foi enviado"), não por ficar pendente.
 */
async function liberarPendentes(srv) {
  for (let i = 0; i < 3; i += 1) {
    await tick();
    srv.etapas.cabecalhos.forEach((d) => d.ok({ status: 200, corpo: { gravado: true } }));
    await tick();
    srv.etapas.corpos.forEach((c) => c.liberar());
  }
}

test('gravação preparada em A, CSRF pendente, troca para B: o pedido NÃO é enviado', async () => {
  const { srv, estado, api } = montar();
  const op = api.criar('/api/episodios/e1/observacoes', { texto: 'escrito na unidade A' }).catch((e) => e);
  await tick();
  assert.equal(srv.etapas.csrf.length, 1, 'aguardando o token CSRF');
  // Troca de unidade como o main.js faz: invalida e depois define a sessão nova.
  estado.invalidar();
  sessaoEm(estado, 'unidade-B');
  srv.etapas.csrf[0].ok();
  await liberarPendentes(srv);
  const resultado = await op;
  assert.ok(resultado instanceof RespostaDescartada && resultado.enviada === false, 'deveria ser "não enviada"');
  assert.deepEqual(srv.enviados, [], 'nada foi enviado: nem com a unidade A nem com a B');
});

test('só a unidade exibida muda (mesma geração): também não envia nem troca o cabeçalho', async () => {
  const srv = servidorControlado();
  let unidade = 'unidade-A';
  const api = criarApi({ fetch: srv.fetch, lerCookie: srv.lerCookie, geracao: () => 7, unidadeEsperada: () => unidade });
  const op = api.substituir('/api/episodios/e1/setor', { versao: 0, setorId: 's1' }).catch((e) => e);
  await tick();
  unidade = 'unidade-B';
  srv.etapas.csrf[0].ok();
  await liberarPendentes(srv);
  const resultado = await op;
  assert.ok(resultado instanceof RespostaDescartada && resultado.enviada === false, 'deveria ser "não enviada"');
  assert.equal(srv.enviados.length, 0);
});

test('sessão encerrada (saída) com CSRF pendente: a gravação não é enviada', async () => {
  const { srv, estado, api } = montar();
  const op = api.criar('/api/episodios/e1/pendencias', { descricao: 'x' }).catch((e) => e);
  await tick();
  estado.limpar();
  srv.etapas.csrf[0].ok();
  await liberarPendentes(srv);
  const resultado = await op;
  assert.ok(resultado instanceof RespostaDescartada && resultado.enviada === false, 'deveria ser "não enviada"');
  assert.equal(srv.enviados.length, 0);
});

test('contexto inalterado: envia com a unidade de ORIGEM e o corpo preparado nela', async () => {
  const { srv, api } = montar();
  const op = api.criar('/api/episodios/e1/observacoes', { texto: 'ok' });
  await tick();
  srv.etapas.csrf[0].ok();
  await tick();
  assert.equal(srv.enviados.length, 1);
  assert.equal(srv.enviados[0].cabecalhos['X-Fluxo-Unidade'], 'unidade-A');
  assert.equal(srv.enviados[0].cabecalhos['X-XSRF-TOKEN'], 'token-novo');
  assert.equal(srv.enviados[0].corpo, '{"texto":"ok"}');
  srv.etapas.cabecalhos[0].ok({ status: 201, corpo: { id: 'o1', versao: 0 } });
  await tick();
  srv.etapas.corpos[0].liberar();
  assert.deepEqual(await op, { id: 'o1', versao: 0 });
});

test('cabeçalhos chegam depois da troca de unidade: resposta descartada', async () => {
  const { srv, estado, api } = montar();
  const op = api.obter('/api/episodios').then((v) => ({ v }), (e) => ({ e }));
  await tick();
  assert.equal(srv.enviados[0].cabecalhos['X-Fluxo-Unidade'], 'unidade-A');
  estado.invalidar();
  sessaoEm(estado, 'unidade-B');
  srv.etapas.cabecalhos[0].ok({ status: 200, corpo: { itens: ['paciente da A'] } });
  await liberarPendentes(srv);
  const r = await op;
  assert.ok(r.e instanceof RespostaDescartada, 'deveria ser descartada');
  assert.deepEqual(srv.corposLidos, [], 'descartada já nos cabeçalhos: o corpo nem é lido');
});

test('cabeçalhos chegam em A, corpo termina depois da troca: dados antigos NÃO são devolvidos', async () => {
  const { srv, estado, api } = montar();
  const op = api.obter('/api/episodios');
  await tick();
  srv.etapas.cabecalhos[0].ok({ status: 200, corpo: { itens: ['paciente da A'] } });
  await tick();
  assert.equal(srv.etapas.corpos.length, 1, 'cabeçalhos recebidos, corpo pendente');
  estado.invalidar();
  sessaoEm(estado, 'unidade-B');
  srv.etapas.corpos[0].liberar();
  await assert.rejects(op, RespostaDescartada);
});

test('401 antigo (corpo concluído após a troca) não encerra a sessão nova', async () => {
  const { srv, estado, api, chamadas } = montar();
  const op = api.obter('/api/travados');
  await tick();
  srv.etapas.cabecalhos[0].ok({ status: 401, corpo: { codigo: 'SESSAO_REVOGADA' } });
  await tick();
  estado.invalidar();
  sessaoEm(estado, 'unidade-B');
  srv.etapas.corpos[0].liberar();
  await assert.rejects(op, RespostaDescartada);
  assert.equal(chamadas.encerrar, 0);
  assert.equal(estado.sessao().unidadeAtiva, 'unidade-B', 'contexto novo intacto');
});

test('401 antigo (cabeçalhos após o encerramento local) não aciona nada', async () => {
  const { srv, estado, api, chamadas } = montar();
  const op = api.obter('/api/episodios').then((v) => ({ v }), (e) => ({ e }));
  await tick();
  estado.limpar();
  srv.etapas.cabecalhos[0].ok({ status: 401, corpo: { codigo: 'SESSAO_EXPIRADA' } });
  await liberarPendentes(srv);
  const r = await op;
  assert.ok(r.e instanceof RespostaDescartada, 'deveria ser descartada');
  assert.equal(chamadas.encerrar, 0);
  assert.deepEqual(srv.corposLidos, [], 'descartada já nos cabeçalhos: o corpo nem é lido');
});

test('409 UNIDADE_ATIVA_ALTERADA antigo não reinicia o contexto novo; o vigente reinicia', async () => {
  const { srv, estado, api, chamadas } = montar();
  const antiga = api.obter('/api/episodios');
  await tick();
  srv.etapas.cabecalhos[0].ok({ status: 409, corpo: { codigo: 'UNIDADE_ATIVA_ALTERADA' } });
  await tick();
  estado.invalidar();
  sessaoEm(estado, 'unidade-B');
  srv.etapas.corpos[0].liberar();
  await assert.rejects(antiga, RespostaDescartada);
  assert.equal(chamadas.mudarUnidade, 0);

  const vigente = api.obter('/api/episodios');
  await tick();
  srv.etapas.cabecalhos[1].ok({ status: 409, corpo: { codigo: 'UNIDADE_ATIVA_ALTERADA' } });
  await tick();
  srv.etapas.corpos[1].liberar();
  await assert.rejects(vigente, (e) => e instanceof ErroApi && e.unidadeAlterada);
  assert.equal(chamadas.mudarUnidade, 1);
});

test('401 do contexto vigente encerra a sessão (comportamento preservado)', async () => {
  const { srv, api, chamadas } = montar();
  const op = api.obter('/api/episodios');
  await tick();
  srv.etapas.cabecalhos[0].ok({ status: 401, corpo: { codigo: 'SESSAO_REVOGADA' } });
  await tick();
  srv.etapas.corpos[0].liberar();
  await assert.rejects(op, (e) => e instanceof ErroApi && e.status === 401);
  assert.equal(chamadas.encerrar, 1);
});

test('troca de unidade pelo fluxo do main.js: o próprio PUT da troca é enviado e aceito', async () => {
  const { srv, estado, api } = montar();
  estado.invalidar(); // main.js invalida ANTES de pedir a troca
  const put = api.substituir('/api/sessao/unidade', { unidadeId: 'unidade-B' });
  await tick();
  srv.etapas.csrf[0].ok();
  await tick();
  assert.equal(srv.enviados.length, 1);
  srv.etapas.cabecalhos[0].ok({ status: 200, corpo: { unidadeAtiva: 'unidade-B' } });
  await tick();
  srv.etapas.corpos[0].liberar();
  assert.equal((await put).unidadeAtiva, 'unidade-B');
});

test('falha ao obter o token depois da mudança de contexto: "não enviada", não erro de rede', async () => {
  const srv = servidorControlado();
  const estado = criarEstado();
  sessaoEm(estado, 'unidade-A');
  let falhar;
  const api = criarApi({
    lerCookie: () => '', geracao: () => estado.geracao(),
    unidadeEsperada: () => (estado.sessao() ? estado.sessao().unidadeAtiva : null),
    fetch: (url) => { srv.enviados.push(url); return new Promise((_, f) => { falhar = f; }); },
  });
  const op = api.criar('/api/episodios/e1/observacoes', { texto: 'x' });
  await tick();
  estado.limpar();
  falhar(new TypeError('Failed to fetch'));
  await assert.rejects(op, (e) => e instanceof RespostaDescartada && e.enviada === false);
  assert.deepEqual(srv.enviados, ['/api/sessao/csrf'], 'só a busca do token; a gravação não');
});

test('sair: DELETE /api/sessao é enviado em qualquer contexto, sem unidade e sem tratadores globais', async () => {
  const { srv, estado, api, chamadas } = montar();
  const saida = api.encerrarSessao();
  await tick();
  estado.invalidar(); // contexto muda durante a espera do token: a saída vale mesmo assim
  srv.etapas.csrf[0].ok();
  await tick();
  assert.equal(srv.enviados.length, 1);
  assert.equal(srv.enviados[0].metodo, 'DELETE');
  assert.equal(srv.enviados[0].url, '/api/sessao');
  assert.equal(srv.enviados[0].cabecalhos['X-Fluxo-Unidade'], undefined);
  srv.etapas.cabecalhos[0].ok({ status: 401 }); // já encerrada: não é erro
  await saida;
  assert.equal(chamadas.encerrar, 0);
});
