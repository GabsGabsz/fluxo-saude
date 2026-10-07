// Restrição por perfil: menu conforme permissões, recusa no servidor e painel sem nomes.
import { test, expect } from '@playwright/test';
import { entrar, capturar, clienteApi, abrirEpisodio, FIX, NORTE } from './apoio.mjs';

test.beforeAll(async ({ baseURL }) => {
  // Garante ao menos um caso aberto (nominal) para provar que o painel não expõe o nome.
  const coord = await clienteApi(baseURL, 'coord.e2e');
  await coord.usarUnidade(NORTE.codigo);
  await abrirEpisodio(coord, { novoPaciente: { nome: 'Paciente Ficticio Teta' }, setorNome: 'Observação Norte' });
  await coord.fechar();
});

const menu = (page) => page.getByRole('navigation', { name: 'Telas' }).getByRole('link');

test('direção: só o painel coletivo, sem nomes de pacientes; Torre recusada', async ({ page, baseURL }) => {
  await entrar(page, 'direcao.e2e');
  await expect(page.getByRole('heading', { name: 'Painel coletivo' })).toBeVisible();
  await expect(menu(page)).toHaveText(['Painel coletivo', 'Indicadores', 'Relatórios']);
  await expect(page.locator('main table tbody tr').first()).toBeVisible();
  const texto = await page.locator('main').innerText();
  for (const p of FIX.pacientes) expect(texto).not.toContain(p.nome);
  expect(texto).not.toContain('Teta');
  expect(texto).not.toMatch(/Paciente Ficticio/);
  await capturar(page, '06-painel-pseudonimizado');

  await page.goto('/#/torre');
  await expect(page.getByRole('heading', { name: 'Acesso não permitido' })).toBeVisible();
  const api = await clienteApi(baseURL, 'direcao.e2e');
  expect(await api.status('GET', '/api/episodios')).toBe(403);
  expect(await api.status('GET', '/api/travados')).toBe(403);
  await api.fechar();
});

test('administração: usuários e regras, sem acesso nominal a episódios', async ({ page }) => {
  await entrar(page, 'admin.norte');
  await expect(menu(page)).toHaveText(['Usuários', 'Regras de alerta']);
  await expect(page.getByRole('cell', { name: 'enf.e2e', exact: true })).toBeVisible();
  await capturar(page, '06-admin-usuarios');
  await page.getByRole('link', { name: 'Regras de alerta' }).click();
  await expect(page.getByText('Nenhuma regra configurada nesta unidade')).toBeVisible();
  await page.goto('/#/torre');
  await expect(page.getByRole('heading', { name: 'Acesso não permitido' })).toBeVisible();
});

test('enfermagem: telas operacionais, sem administração', async ({ page }) => {
  await entrar(page, 'enf.e2e');
  await expect(menu(page)).toHaveText(['Torre de Controle', 'Pacientes travados', 'Abrir episódio', 'Passagem de plantão',
    'Painel coletivo']);
  await page.goto('/#/usuarios');
  await expect(page.getByRole('heading', { name: 'Acesso não permitido' })).toBeVisible();
  await page.goto('/#/indicadores');
  await expect(page.getByRole('heading', { name: 'Acesso não permitido' })).toBeVisible();
});
