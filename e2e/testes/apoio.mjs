// Apoio aos testes E2E: dados fictícios, entrada pela interface e chamadas de API (para preparar
// cenários e simular "outra pessoa" agindo em paralelo), sempre com sessão e CSRF reais.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, request as fabricaRequest } from '@playwright/test';

const aqui = path.dirname(fileURLToPath(import.meta.url));
export const FIX = JSON.parse(fs.readFileSync(path.join(aqui, '..', 'dados', 'fixtures.json'), 'utf8'));
export const usuario = (login) => FIX.usuarios.find((u) => u.login === login);
export const unidade = (codigo) => FIX.unidades.find((u) => u.codigo === codigo);
export const NORTE = unidade('E2E_NORTE');
export const SUL = unidade('E2E_SUL');

/** Entra pela tela de login. */
export async function entrar(page, login, senha = usuario(login).senha) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Fluxo Saúde — entrar' })).toBeVisible();
  await page.getByLabel('Usuário').fill(login);
  await page.getByLabel('Senha', { exact: true }).fill(senha);
  await page.getByRole('button', { name: 'Entrar' }).click();
}

/** Escolhe a unidade ativa no topo (só existe seletor para quem tem mais de uma unidade). */
export async function escolherUnidade(page, nome) {
  const sel = page.getByLabel('Unidade ativa:');
  if ((await sel.locator('option:checked').textContent()) !== nome) {
    // Só segue depois que o servidor confirmou a troca e a tela da nova unidade foi montada.
    await Promise.all([
      page.waitForResponse((r) => r.url().endsWith('/api/sessao/unidade') && r.request().method() === 'PUT' && r.ok()),
      sel.selectOption({ label: nome }),
    ]);
  }
  await expect(page.getByLabel('Unidade ativa:').locator('option:checked')).toHaveText(nome);
  await expect(page.getByLabel('Unidade ativa:')).toBeEnabled();
  await expect(page.locator('main h1')).toBeVisible();
}

export async function capturar(page, nome) {
  fs.mkdirSync(path.join(aqui, '..', 'capturas'), { recursive: true });
  await page.screenshot({ path: path.join(aqui, '..', 'capturas', `${nome}.png`), fullPage: true });
}

/** Instante → valor de <input type="datetime-local"> no fuso informado. */
export function localNoFuso(ms, fuso) {
  const p = {};
  for (const { type, value } of new Intl.DateTimeFormat('en-US', { timeZone: fuso, hourCycle: 'h23', year: 'numeric',
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).formatToParts(new Date(ms))) p[type] = value;
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
}

/** Cliente de API autenticado (sessão + CSRF reais) para preparar cenários. */
export async function clienteApi(baseURL, login, senha = usuario(login).senha) {
  const ctx = await fabricaRequest.newContext({ baseURL });
  async function token() {
    const estado = await ctx.storageState();
    let c = estado.cookies.find((k) => k.name === 'XSRF-TOKEN' && k.value);
    if (!c) {
      await ctx.get('/api/sessao/csrf');
      c = (await ctx.storageState()).cookies.find((k) => k.name === 'XSRF-TOKEN' && k.value);
    }
    return c.value;
  }
  async function chamar(metodo, url, data, esperado) {
    const headers = { Accept: 'application/json' };
    if (metodo !== 'GET') headers['X-XSRF-TOKEN'] = await token();
    const r = await ctx.fetch(url, { method: metodo, data, headers });
    if (esperado !== undefined) expect(r.status(), `${metodo} ${url}: ${await r.text()}`).toBe(esperado);
    const t = await r.text();
    return { status: r.status(), dados: t ? JSON.parse(t) : null };
  }
  const login1 = await chamar('POST', '/api/sessao', { login, senha }, 200);
  const api = {
    sessao: login1.dados,
    get: (u, e = 200) => chamar('GET', u, undefined, e).then((r) => r.dados),
    post: (u, d, e) => chamar('POST', u, d, e).then((r) => r.dados),
    put: (u, d, e = 200) => chamar('PUT', u, d, e).then((r) => r.dados),
    status: async (m, u, d) => (await chamar(m, u, d)).status,
    async usarUnidade(codigo) {
      const unidades = await api.get('/api/sessao/unidades');
      const alvo = unidades.find((x) => x.codigo === codigo);
      await api.put('/api/sessao/unidade', { unidadeId: alvo.id });
      return alvo;
    },
    fechar: () => ctx.dispose(),
  };
  return api;
}

/** Garante um episódio ABERTO do paciente (por CNS) na unidade ativa do cliente; idempotente. */
export async function garantirEpisodio(api, { cns, nome, setorNome }) {
  const torre = await api.get('/api/episodios');
  const existente = torre.itens.find((l) => l.pacienteNome === nome);
  return existente ? existente.episodioId : abrirEpisodio(api, { cns, setorNome });
}

/** Abre um episódio pela API (preparação de cenário) e devolve o id. */
export async function abrirEpisodio(api, { cns, novoPaciente, setorNome, momento, justificativaDuplicidade }) {
  const cat = await api.get('/api/catalogo');
  const setorId = cat.setores.find((s) => s.nome === setorNome).id;
  const corpo = { setorId, momento, justificativaDuplicidade };
  if (cns) {
    const [p] = await api.get(`/api/pacientes?cns=${cns}`);
    corpo.pacienteId = p.id;
  } else {
    corpo.novoPaciente = novoPaciente;
  }
  return (await api.post('/api/episodios', corpo, 201)).id;
}
