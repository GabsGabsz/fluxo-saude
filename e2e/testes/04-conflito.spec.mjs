// Conflito de versão: outra pessoa altera o caso; a escrita com a versão antiga é recusada,
// nada é sobrescrito, a tela pede para recarregar e NÃO reenvia sozinha.
import { test, expect } from '@playwright/test';
import { entrar, capturar, clienteApi, abrirEpisodio, NORTE } from './apoio.mjs';

test('409 informa, não sobrescreve e não reenvia', async ({ page, baseURL }) => {
  const coord = await clienteApi(baseURL, 'coord.e2e');
  await coord.usarUnidade(NORTE.codigo);
  const id = await abrirEpisodio(coord, { novoPaciente: { nome: 'Paciente Ficticio Zeta' }, setorNome: 'Observação Norte' });

  await entrar(page, 'enf.e2e');
  await page.goto(`/#/episodio/${id}`);
  await expect(page.getByRole('heading', { name: 'Episódio — Paciente Ficticio Zeta' })).toBeVisible();
  const protocolo = page.getByRole('form', { name: 'Protocolo' });
  await protocolo.getByLabel('Sistema').fill('SISREG-FICTICIO');
  await protocolo.getByLabel('Número').fill('0001');

  // Outra pessoa muda o setor (versão 0 -> 1) enquanto o formulário está preenchido.
  const caso = await coord.get(`/api/episodios/${id}`);
  const emerg = (await coord.get('/api/catalogo')).setores.find((s) => s.nome === 'Emergência Norte');
  await coord.put(`/api/episodios/${id}/setor`, { versao: caso.resumo.versao, setorId: emerg.id });

  await protocolo.getByRole('button', { name: 'Salvar protocolo' }).click();
  await expect(protocolo.getByRole('alert')).toContainText('alterado por outra pessoa');
  await expect(protocolo.getByLabel('Número')).toHaveValue('0001'); // o que foi digitado não some
  await capturar(page, '04-conflito-de-versao');

  const depois = await coord.get(`/api/episodios/${id}`);
  expect(depois.resumo.versao).toBe(caso.resumo.versao + 1);
  expect(depois.resumo.protocoloNumero).toBeNull();          // nada sobrescrito
  expect(depois.resumo.setorNome).toBe('Emergência Norte');

  await protocolo.getByRole('button', { name: 'Recarregar dados' }).click();
  const dados = page.getByRole('region', { name: 'Dados do caso' });
  await expect(dados).toContainText('Emergência Norte');
  await expect(dados).toContainText(String(depois.resumo.versao));
  const final = await coord.get(`/api/episodios/${id}`);
  expect(final.resumo.protocoloNumero).toBeNull();            // nenhum reenvio automático
  await coord.fechar();
});
