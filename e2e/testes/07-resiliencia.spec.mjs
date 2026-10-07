// Resiliência da tela: resposta atrasada da unidade anterior é descartada; falha de conexão
// sinaliza dados possivelmente desatualizados sem apagar a lista nem disparar escrita.
import { test, expect } from '@playwright/test';
import { entrar, escolherUnidade, clienteApi, garantirEpisodio, FIX, NORTE, SUL } from './apoio.mjs';

const alfa = FIX.pacientes.find((p) => p.nome.endsWith('Alfa'));
const beta = FIX.pacientes.find((p) => p.nome.endsWith('Beta'));

test.beforeAll(async ({ baseURL }) => {
  // Garante um caso aberto em cada unidade (independe da ordem dos arquivos).
  const coord = await clienteApi(baseURL, 'coord.e2e');
  for (const [u, p] of [[NORTE, alfa], [SUL, beta]]) {
    await coord.usarUnidade(u.codigo);
    await garantirEpisodio(coord, { cns: p.cns, nome: p.nome, setorNome: u.setores[0][1] });
  }
  await coord.fechar();
});

test('resposta atrasada da unidade anterior não aparece depois da troca', async ({ page }) => {
  await entrar(page, 'coord.e2e');
  await escolherUnidade(page, NORTE.nome);
  await expect(page.getByRole('link', { name: alfa.nome })).toBeVisible();

  // A próxima leitura da Torre (ainda na Norte) é respondida pelo servidor mas ENTREGUE com atraso.
  let atrasada = true;
  await page.route('**/api/episodios?*', async (route) => {
    if (!atrasada) return route.continue();
    atrasada = false;
    const resposta = await route.fetch();
    await new Promise((r) => setTimeout(r, 2500));
    await route.fulfill({ response: resposta });
  });
  await page.getByRole('button', { name: 'Atualizar agora' }).click();
  await escolherUnidade(page, SUL.nome);           // troca enquanto a resposta da Norte está em voo
  await expect(page.getByRole('link', { name: beta.nome })).toBeVisible();
  for (let i = 0; i < 6; i += 1) {                  // por 3 s, inclusive depois da chegada da resposta antiga
    await expect(page.getByText(alfa.nome)).toHaveCount(0);
    await page.waitForTimeout(500);
  }
});

test('outra aba troca a unidade: esta aba não lê nem grava na unidade que não exibe', async ({ browser, baseURL }) => {
  const contexto = await browser.newContext({ baseURL });   // mesma sessão (cookies) nas duas abas
  const abaA = await contexto.newPage();
  // Sem BroadcastChannel na aba A: prova a defesa do SERVIDOR (cabeçalho X-Fluxo-Unidade -> 409).
  await abaA.addInitScript(() => { delete window.BroadcastChannel; });
  await entrar(abaA, 'coord.e2e');
  await escolherUnidade(abaA, NORTE.nome);
  await expect(abaA.getByRole('link', { name: alfa.nome })).toBeVisible();

  const abaB = await contexto.newPage();
  await abaB.goto('/');
  await escolherUnidade(abaB, SUL.nome);

  const respostas = [];
  abaA.on('response', (r) => { if (r.url().includes('/api/episodios')) respostas.push(r.status()); });
  await abaA.getByRole('button', { name: 'Atualizar agora' }).click();
  await expect(abaA.getByText('A unidade ativa foi trocada em outra aba ou janela')).toBeVisible();
  expect(respostas[0]).toBe(409);                            // recusada antes de ler a outra unidade
  await expect(abaA.getByLabel('Unidade ativa:').locator('option:checked')).toHaveText(SUL.nome);
  await expect(abaA.getByRole('link', { name: beta.nome })).toBeVisible();
  await expect(abaA.getByText(alfa.nome)).toHaveCount(0);
  await contexto.close();
});

test('gravação preparada na Norte com o CSRF pendente, troca para a Sul: o pedido antigo não é enviado', async ({ page, context, baseURL }) => {
  const coord = await clienteApi(baseURL, 'coord.e2e');
  await coord.usarUnidade(NORTE.codigo);
  const idNorte = await garantirEpisodio(coord, { cns: alfa.cns, nome: alfa.nome, setorNome: NORTE.setores[0][1] });
  const TEXTO = 'Observacao preparada na Norte e nunca enviada (cenario de corrida)';

  // Instrumentação SÓ do teste (não faz parte da aplicação): registra, no próprio navegador, cada
  // chamada a fetch e quando a obtenção do token CSRF terminou. É o que torna a verificação
  // determinística: o teste espera o evento, não um intervalo de tempo.
  await page.addInitScript(() => {
    const original = window.fetch.bind(window);
    window.__e2e = { envios: [], csrfConcluidos: 0 };
    window.fetch = (url, op = {}) => {
      window.__e2e.envios.push(`${String(op.method || 'GET').toUpperCase()} ${url}`);
      const p = original(url, op);
      return String(url).endsWith('/api/sessao/csrf')
        ? p.then((r) => { window.__e2e.csrfConcluidos += 1; return r; }) : p;
    };
  });
  const naRede = [];
  page.on('request', (r) => { if (r.method() === 'POST' && r.url().includes('/observacoes')) naRede.push(r.url()); });

  await entrar(page, 'coord.e2e');
  await escolherUnidade(page, NORTE.nome);
  await page.goto(`/#/episodio/${idNorte}`);
  await expect(page.getByRole('heading', { name: `Episódio — ${alfa.nome}` })).toBeVisible();

  // Portão: a PRIMEIRA obtenção de token fica retida até o teste liberar; as demais passam.
  let liberarCsrf;
  const portao = new Promise((r) => { liberarCsrf = r; });
  let avisarRetida;
  const retida = new Promise((r) => { avisarRetida = r; });
  let vistas = 0;
  await page.route('**/api/sessao/csrf', async (route) => {
    vistas += 1;
    if (vistas === 1) { avisarRetida(); await portao; }
    await route.continue();
  });
  await context.clearCookies({ name: 'XSRF-TOKEN' }); // a próxima escrita precisa buscar o token

  const obs = page.getByRole('form', { name: 'Observação' });
  await obs.getByLabel('Observação operacional').fill(TEXTO);
  const formulario = await obs.elementHandle();        // continua acessível depois de sair da tela
  await obs.getByRole('button', { name: 'Registrar observação' }).click();
  await retida;                                        // gravação preparada na Norte, à espera do token
  expect(await formulario.getAttribute('aria-busy')).toBe('true');

  page.once('dialog', (d) => d.accept());              // "há alterações não enviadas" ao trocar de unidade
  await escolherUnidade(page, SUL.nome);               // a troca obtém o próprio token e conclui
  await expect(page.getByRole('link', { name: beta.nome })).toBeVisible();

  const antes = await page.evaluate(() => window.__e2e.csrfConcluidos);
  liberarCsrf();
  // Espera o navegador concluir a obtenção do token retido; a decisão de enviar (ou não) roda
  // logo em seguida, na mesma fila de microtarefas, antes desta função ser reavaliada.
  await page.waitForFunction((n) => window.__e2e.csrfConcluidos > n, antes);
  // Sinal POSITIVO de que a operação terminou (o formulário tira aria-busy no "finally"),
  // qualquer que seja o caminho: não depende de a decisão acontecer na mesma microtarefa.
  await page.waitForFunction((f) => !f.hasAttribute('aria-busy'), formulario);
  const enviadosNoNavegador = await page.evaluate(() => window.__e2e.envios.filter((e) => e.startsWith('POST') && e.includes('/observacoes')));
  expect(enviadosNoNavegador).toEqual([]);             // fetch nunca foi chamado para a gravação antiga
  expect(naRede).toEqual([]);                          // e nada saiu pela rede

  // Nenhum registro correspondente: nem no caso da Norte, nem em qualquer caso da Sul.
  const casoNorte = await coord.get(`/api/episodios/${idNorte}`);
  expect(casoNorte.observacoes.map((o) => o.texto)).not.toContain(TEXTO);
  await coord.usarUnidade(SUL.codigo);
  for (const linha of (await coord.get('/api/episodios')).itens) {
    const caso = await coord.get(`/api/episodios/${linha.episodioId}`);
    expect(caso.observacoes.map((o) => o.texto)).not.toContain(TEXTO);
  }
  await expect(page.getByText(TEXTO)).toHaveCount(0);
  await coord.fechar();
});

test('falha de conexão: aviso de dados desatualizados, lista mantida, nenhuma escrita', async ({ page }) => {
  await entrar(page, 'enf.e2e');
  await expect(page.getByRole('link', { name: alfa.nome })).toBeVisible();
  const escritas = [];
  page.on('request', (r) => { if (!['GET', 'HEAD'].includes(r.method())) escritas.push(`${r.method()} ${r.url()}`); });
  await page.route('**/api/**', (route) => route.abort('internetdisconnected'));
  await page.getByRole('button', { name: 'Atualizar agora' }).click();
  await expect(page.getByText('Sem conexão com o servidor. Dados exibidos podem estar desatualizados')).toBeVisible();
  await expect(page.getByRole('link', { name: alfa.nome })).toBeVisible();
  expect(escritas).toEqual([]);
  await page.unroute('**/api/**');
  await page.getByRole('button', { name: 'Atualizar agora' }).click();
  await expect(page.getByText('Sem conexão com o servidor.')).toHaveCount(0);
});
