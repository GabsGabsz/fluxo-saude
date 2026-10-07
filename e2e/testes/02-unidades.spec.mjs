// Troca de unidade ativa: a tela passa a mostrar só os dados da nova unidade (sem mistura).
import { test, expect } from '@playwright/test';
import { entrar, escolherUnidade, capturar, clienteApi, abrirEpisodio, FIX, NORTE, SUL } from './apoio.mjs';

const alfa = FIX.pacientes.find((p) => p.nome.endsWith('Alfa'));
const beta = FIX.pacientes.find((p) => p.nome.endsWith('Beta'));

test.beforeAll(async ({ baseURL }) => {
  const coord = await clienteApi(baseURL, 'coord.e2e');
  await coord.usarUnidade(NORTE.codigo);
  await abrirEpisodio(coord, { cns: alfa.cns, setorNome: NORTE.setores[0][1] });
  await coord.usarUnidade(SUL.codigo);
  await abrirEpisodio(coord, { cns: beta.cns, setorNome: SUL.setores[0][1] });
  await coord.fechar();
});

test('coordenação alterna Norte ↔ Sul sem misturar pacientes, setores ou fuso', async ({ page }) => {
  await entrar(page, 'coord.e2e');
  await expect(page.getByRole('heading', { name: 'Torre de Controle' })).toBeVisible();

  await escolherUnidade(page, NORTE.nome);
  await expect(page.getByRole('link', { name: alfa.nome })).toBeVisible();
  await expect(page.getByText(beta.nome)).toHaveCount(0);
  await expect(page.getByLabel('Setor', { exact: true }).locator('option', { hasText: 'Observação Sul' })).toHaveCount(0);
  await capturar(page, '02-torre-unidade-norte');

  await escolherUnidade(page, SUL.nome);
  await expect(page.getByRole('link', { name: beta.nome })).toBeVisible();
  await expect(page.getByText(alfa.nome)).toHaveCount(0);
  await expect(page.getByLabel('Setor', { exact: true }).locator('option', { hasText: 'Observação Norte' })).toHaveCount(0);
  await expect(page.getByLabel('Setor', { exact: true }).locator('option', { hasText: 'Observação Sul' })).toHaveCount(1);
  await capturar(page, '02-torre-unidade-sul');

  // Endereço de um caso da outra unidade não mostra dados: o servidor responde 404 (RLS).
  await escolherUnidade(page, NORTE.nome);
  const linkAlfa = await page.getByRole('link', { name: alfa.nome }).getAttribute('href');
  await escolherUnidade(page, SUL.nome);
  await page.goto(`/${linkAlfa}`);
  await expect(page.getByText('Episódio não encontrado na unidade ativa.')).toBeVisible();
  await expect(page.getByText(alfa.nome)).toHaveCount(0);
});
