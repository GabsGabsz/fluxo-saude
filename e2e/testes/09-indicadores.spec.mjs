// Indicadores (RF-019/020): a Direção vê só agregados (sem nomes, documentos ou links para
// casos); retrato atual separado do histórico; valor calculado à mão para um encerramento
// controlado; período vazio = "sem dados"; dicionário com todas as fórmulas marcadas como proposta.
// Premissa: nenhuma outra especificação ENCERRA episódios na unidade Norte no dia do teste.
import { test, expect } from '@playwright/test';
import { entrar, capturar, clienteApi, abrirEpisodio, FIX, NORTE } from './apoio.mjs';

test('Direção: retrato, histórico com valor esperado, período vazio e dicionário — sem dado nominal', async ({ page, baseURL }) => {
  // Um episódio aberto há 3 h (registro retroativo) e encerrado agora: permanência 180 min.
  const enf = await clienteApi(baseURL, 'enf.e2e');
  const tresHorasAtras = new Date(Date.now() - 3 * 3600_000).toISOString();
  const id = await abrirEpisodio(enf, { novoPaciente: { nome: 'Paciente Ficticio Iota' }, setorNome: NORTE.setores[0][1],
    momento: { ocorridoEm: tresHorasAtras, justificativaAjuste: 'Registro tardio (cenário de teste)' } });
  const cat = await enf.get('/api/catalogo');
  const alta = cat.etapas.find((e) => e.codigo === 'ALTA');
  await enf.put(`/api/episodios/${id}/etapa`, { versao: 0, etapaId: alta.id });
  await enf.fechar();

  await entrar(page, 'direcao.e2e');
  await page.getByRole('link', { name: 'Indicadores' }).click();
  await expect(page.getByRole('heading', { name: 'Indicadores', exact: true })).toBeVisible();
  const retrato = page.getByRole('region', { name: 'Retrato atual da unidade (agora)' });
  const historico = page.getByRole('region', { name: 'Histórico do período' });
  await expect(retrato).toContainText('Conta TODOS os episódios abertos');
  await expect(historico).toContainText('America/Fortaleza');

  // Período só de hoje: o único encerramento do dia na Norte é o do cenário (180 min).
  const hoje = await page.getByLabel('Fim (inclusive)').inputValue();
  const calcular = page.getByRole('button', { name: 'Calcular' });
  await expect(calcular).toBeEnabled();                       // cálculo inicial concluído
  await page.getByLabel(/^Início/).fill(hoje);
  await Promise.all([page.waitForResponse((x) => x.url().includes('/api/indicadores?')), calcular.click()]);
  await expect(historico.locator('dt:text-is("Encerrados incluídos") + dd')).toHaveText('1');
  await expect(historico.locator('dt:text-is("Média") + dd')).toHaveText('3 h 00 min');
  await expect(historico.locator('dt:text-is("Mediana") + dd')).toHaveText('3 h 00 min');
  await capturar(page, '09-indicadores-direcao');

  // Mesmo número pela API (mesma sessão de Direção) e "casos ativos" do retrato = API.
  const dir = await clienteApi(baseURL, 'direcao.e2e');
  const r = await dir.get(`/api/indicadores?inicio=${hoje}&fim=${hoje}`);
  expect(r.historico.permanencia.incluidos).toBe(1);
  expect(Math.floor(r.historico.permanencia.mediaMin)).toBe(180);
  const abertos = r.retrato.itens.find((i) => i.dimensao === 'ABERTOS').quantidade;
  await expect(retrato.locator('dt:text-is("Casos ativos") + dd')).toHaveText(String(abertos));
  const texto = JSON.stringify(r);
  for (const p of FIX.pacientes) expect(texto).not.toContain(p.nome);
  expect(texto).not.toContain('Iota');
  expect(texto).not.toContain(id);
  await dir.fechar();

  // Tela sem nomes nem links para casos
  const tela = await page.locator('main').innerText();
  expect(tela).not.toMatch(/Paciente Ficticio/);
  await expect(page.locator('main a[href^="#/episodio"]')).toHaveCount(0);

  // Período sem encerramentos: "sem dados", nunca zero na média
  await page.getByLabel(/^Início/).fill('2020-01-01');
  await page.getByLabel('Fim (inclusive)').fill('2020-01-02');
  await expect(calcular).toBeEnabled();
  await Promise.all([page.waitForResponse((x) => x.url().includes('/api/indicadores?')), calcular.click()]);
  await expect(historico.locator('dt:text-is("Encerrados incluídos") + dd')).toHaveText('0');
  await expect(historico.locator('dt:text-is("Média") + dd')).toHaveText('sem dados');

  // Limitações junto dos resultados (não só no dicionário): filtro de setor = setor atual/final.
  await expect(historico.locator('.limitacao')).toHaveCount(0);
  await page.getByLabel('Setor').selectOption({ label: NORTE.setores[0][1] });
  await expect(calcular).toBeEnabled();
  await Promise.all([page.waitForResponse((x) => x.url().includes('/api/indicadores?')), calcular.click()]);
  await expect(historico.locator('.limitacao').first()).toContainText('setor ATUAL (abertos) ou FINAL (encerrados)');
  await expect(historico.locator('h3', { hasText: 'Motivos de atraso' })).toBeVisible();

  // Dicionário: todos os verbetes marcados como proposta
  const dic = page.getByRole('region', { name: 'Dicionário de cálculo (RF-039)' });
  await expect(dic.locator('details')).toHaveCount(9);
  await expect(dic.locator('details summary', { hasText: 'Proposta' })).toHaveCount(9);
});
