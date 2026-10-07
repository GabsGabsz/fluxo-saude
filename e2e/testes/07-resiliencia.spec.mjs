// Resiliência da tela: resposta atrasada da unidade anterior é descartada; falha de conexão
// sinaliza dados possivelmente desatualizados sem apagar a lista nem disparar escrita.
import { test, expect } from '@playwright/test';
import { entrar, escolherUnidade, clienteApi, abrirEpisodio, FIX, NORTE, SUL } from './apoio.mjs';

const alfa = FIX.pacientes.find((p) => p.nome.endsWith('Alfa'));
const beta = FIX.pacientes.find((p) => p.nome.endsWith('Beta'));

test.beforeAll(async ({ baseURL }) => {
  // Garante um caso aberto em cada unidade (independe da ordem dos arquivos).
  const coord = await clienteApi(baseURL, 'coord.e2e');
  for (const [u, p] of [[NORTE, alfa], [SUL, beta]]) {
    await coord.usarUnidade(u.codigo);
    const torre = await coord.get('/api/episodios');
    if (!torre.itens.some((l) => l.pacienteNome === p.nome)) await abrirEpisodio(coord, { cns: p.cns, setorNome: u.setores[0][1] });
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
