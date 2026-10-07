// Alertas e ciência: "nenhuma regra" ≠ "nenhum alerta"; a ciência envia a versão EXIBIDA da
// regra; se a regra muda antes do clique, nada é gravado e é preciso nova ação explícita.
import { test, expect } from '@playwright/test';
import { entrar, escolherUnidade, capturar, clienteApi, abrirEpisodio, NORTE, SUL } from './apoio.mjs';

test('sem regras na unidade: a tela diz que alertas não são calculados', async ({ page }) => {
  await entrar(page, 'coord.e2e');
  await escolherUnidade(page, NORTE.nome);
  await page.getByRole('link', { name: 'Pacientes travados' }).click();
  await expect(page.getByText('Nenhuma regra de alerta ativa nesta unidade')).toBeVisible();
  await expect(page.getByText('A ausência de alertas NÃO significa que não há atraso.')).toBeVisible();
});

test('alerta alterado antes da ciência: 409, nada gravado, nova confirmação explícita', async ({ page, baseURL }) => {
  const admin = await clienteApi(baseURL, 'admin.sul');
  const regra = await admin.post('/api/config/regras-alerta', { nome: 'Permanência prolongada (ilustrativa)',
    tipo: 'TEMPO_TOTAL', limiteMinutos: 60, acaoEsperada: 'Acionar a coordenação de fluxo' }, 201);
  const coord = await clienteApi(baseURL, 'coord.e2e');
  await coord.usarUnidade(SUL.codigo);
  const duasHorasAtras = new Date(Date.now() - 2 * 3600_000).toISOString();
  const id = await abrirEpisodio(coord, { novoPaciente: { nome: 'Paciente Ficticio Delta' }, setorNome: 'Observação Sul',
    momento: { ocorridoEm: duasHorasAtras, justificativaAjuste: 'Registro tardio (cenário de teste)' } });

  await entrar(page, 'coord.e2e');
  await escolherUnidade(page, SUL.nome);
  await expect(page.getByRole('link', { name: 'Paciente Ficticio Delta' })).toBeVisible();
  await expect(page.locator('tr', { hasText: 'Paciente Ficticio Delta' })).toContainText('1 alerta');
  await page.getByRole('link', { name: 'Pacientes travados' }).click();
  const cartao = page.locator('li.cartao', { hasText: 'Paciente Ficticio Delta' });
  await expect(cartao).toContainText('Permanência prolongada (ilustrativa)');
  await expect(cartao).toContainText('Acionar a coordenação de fluxo');
  await capturar(page, '05-travados-antes-da-ciencia');

  // A administração altera a regra DEPOIS de a tela exibi-la (versão 0 -> 1).
  await admin.put(`/api/config/regras-alerta/${regra.id}`, { versao: regra.versao, nome: regra.nome, limiteMinutos: 61,
    acaoEsperada: 'Acionar a coordenação e a direção', ativa: true });

  await cartao.getByRole('button', { name: /Registrar ciência/ }).click();
  await expect(page.getByRole('alert')).toContainText('A regra deste alerta foi alterada desde que você a visualizou');
  const travados = await coord.get('/api/travados');
  const alerta = travados.itens.find((c) => c.episodioId === id).alertas[0];
  expect(alerta.ciencia).toBeNull();                       // nada gravado
  expect(alerta.regraVersao).toBe(regra.versao + 1);
  await expect(cartao).toContainText('Acionar a coordenação e a direção'); // lista recarregada
  await capturar(page, '05-ciencia-recusada-regra-alterada');

  await cartao.getByRole('button', { name: /Registrar ciência/ }).click();   // nova ação explícita
  await expect(page.getByText(/Ciência registrada: Permanência prolongada/)).toBeVisible();
  await expect(cartao).toContainText('Ciência de Caio Coordenador Ficticio');
  const depois = (await coord.get('/api/travados')).itens.find((c) => c.episodioId === id).alertas[0];
  expect(depois.ciencia).not.toBeNull();
  await capturar(page, '05-ciencia-registrada');
  await admin.fechar();
  await coord.fechar();
});
