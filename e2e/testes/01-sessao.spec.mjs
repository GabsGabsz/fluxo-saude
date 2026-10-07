// Sessão: login, troca de senha obrigatória, saída, sessão revogada — nada sensível no navegador.
import { test, expect } from '@playwright/test';
import { entrar, capturar, clienteApi, usuario } from './apoio.mjs';

const armazenamentoVazio = (page) => page.evaluate(() => [localStorage.length, sessionStorage.length]);

test('credencial inválida não entra e não revela qual campo errou', async ({ page }) => {
  await entrar(page, 'enf.e2e', 'senha errada de proposito 00');
  await expect(page.getByRole('alert')).toContainText('Usuário ou senha inválidos.');
  await expect(page.getByLabel('Senha', { exact: true })).toHaveValue('');
  await expect(page.getByRole('navigation', { name: 'Telas' })).toHaveCount(0);
});

test('troca de senha obrigatória antes de qualquer tela; saída limpa a tela', async ({ page }) => {
  await entrar(page, 'novo.e2e');
  await expect(page.getByRole('heading', { name: 'Troca de senha obrigatória' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Telas' })).toHaveCount(0);
  await capturar(page, '01-troca-de-senha-obrigatoria');

  const nova = 'cachoeira serena no planalto 7531';
  await page.getByLabel('Senha atual').fill(usuario('novo.e2e').senha);
  await page.getByLabel('Nova senha', { exact: true }).fill(nova);
  await page.getByLabel('Confirme a nova senha').fill(nova);
  await page.getByRole('button', { name: 'Trocar senha' }).click();
  await expect(page.getByRole('heading', { name: 'Torre de Controle' })).toBeVisible();
  expect(await armazenamentoVazio(page)).toEqual([0, 0]);

  await page.getByRole('button', { name: 'Sair' }).click();
  await expect(page.getByText('Você saiu do sistema.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Torre de Controle' })).toHaveCount(0);
  expect(await armazenamentoVazio(page)).toEqual([0, 0]);

  // A senha nova vale; a antiga não.
  await entrar(page, 'novo.e2e', nova);
  await expect(page.getByRole('heading', { name: 'Torre de Controle' })).toBeVisible();
});

test('sessão revogada pela administração: a tela volta ao login sem os dados anteriores', async ({ page, baseURL }) => {
  await entrar(page, 'revogado.e2e');
  await expect(page.getByRole('heading', { name: 'Torre de Controle' })).toBeVisible();

  const admin = await clienteApi(baseURL, 'admin.norte');
  const lista = await admin.get('/api/admin/usuarios');
  const alvo = lista.itens.find((u) => u.login === 'revogado.e2e');
  await admin.post(`/api/admin/usuarios/${alvo.id}/senha-provisoria`, { versao: alvo.versao }, 200);
  await admin.fechar();

  await page.getByRole('button', { name: 'Atualizar agora' }).click();
  await expect(page.getByText('Sua sessão foi encerrada')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Fluxo Saúde — entrar' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Telas' })).toHaveCount(0);
  await expect(page.locator('main table')).toHaveCount(0);
  await capturar(page, '01-sessao-revogada');
});
