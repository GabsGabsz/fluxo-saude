// Abertura e atualização de episódio pela interface: etapa com motivo, pendência (criar,
// resolver), observação e linha do tempo; novo paciente.
import { test, expect } from '@playwright/test';
import { entrar, capturar, localNoFuso, FIX, NORTE } from './apoio.mjs';

const gama = FIX.pacientes.find((p) => p.nome.endsWith('Gama'));

test('enfermagem abre episódio por CNS e atualiza etapa, pendência e observação', async ({ page }) => {
  await entrar(page, 'enf.e2e');
  await page.getByRole('link', { name: 'Abrir episódio' }).first().click();
  await expect(page.getByRole('heading', { name: 'Abrir episódio' })).toBeVisible();

  await page.getByLabel('Valor exato').fill(gama.cns);
  await page.getByRole('button', { name: 'Buscar' }).click();
  await page.getByLabel(new RegExp(gama.nome)).check();
  await page.getByLabel('Setor de entrada').selectOption({ label: 'Emergência Norte' });
  await page.getByRole('button', { name: 'Abrir episódio' }).click();
  await expect(page.getByRole('heading', { name: `Episódio — ${gama.nome}` })).toBeVisible();

  // Etapa que exige motivo de bloqueio
  const etapa = page.getByRole('form', { name: 'Mudar etapa' });
  await etapa.getByLabel('Nova etapa').selectOption({ label: 'Aguardando exame/parecer' });
  await etapa.getByLabel('Motivo do bloqueio').selectOption({ label: 'Aguardando exame (Assistencial)' });
  await etapa.getByRole('button', { name: 'Confirmar etapa' }).click();
  const dados = page.getByRole('region', { name: 'Dados do caso' });
  await expect(dados).toContainText('Aguardando exame/parecer');
  await expect(dados).toContainText('Aguardando exame');
  const tempo = page.getByRole('region', { name: 'Linha do tempo' });
  await expect(tempo).toContainText('Em atendimento → Aguardando exame/parecer');
  await expect(tempo).toContainText('Bloqueio definido');

  // Pendência com responsável (setor) e prazo no fuso da unidade
  await page.getByText('Nova pendência', { exact: true }).click();
  const nova = page.getByRole('form', { name: 'Nova pendência' });
  await nova.getByLabel('Categoria').selectOption({ label: 'Assistencial' });
  await nova.getByLabel('Descrição / próxima ação').fill('Cobrar laudo do exame de imagem');
  await nova.getByLabel('Setor', { exact: true }).check();
  await nova.getByLabel('Setor responsável').selectOption({ label: 'Observação Norte' });
  await nova.getByLabel(/^Prazo/).fill(localNoFuso(Date.now() + 2 * 3600_000, NORTE.fuso));
  await nova.getByLabel('Criticidade operacional').selectOption({ label: 'Alta' });
  await nova.getByRole('button', { name: 'Criar pendência' }).click();
  const pend = page.getByRole('region', { name: /Pendências/ });
  await expect(pend).toContainText('Cobrar laudo do exame de imagem');
  await expect(pend).toContainText('Setor: Observação Norte');
  await capturar(page, '03-episodio-com-pendencia');

  await pend.getByText('Resolver', { exact: true }).click();
  const resolver = pend.getByRole('form', { name: 'Resolver pendência' });
  await resolver.getByLabel('Como foi resolvida').fill('Laudo recebido e anexado ao processo');
  await resolver.getByRole('button', { name: 'Resolver pendência' }).click();
  await expect(pend).toContainText('Resolvida');
  await expect(pend).toContainText('Laudo recebido e anexado ao processo');

  const obs = page.getByRole('form', { name: 'Observação' });
  await obs.getByLabel('Observação operacional').fill('Familiar informado sobre o andamento');
  await obs.getByRole('button', { name: 'Registrar observação' }).click();
  await expect(page.getByRole('region', { name: 'Observações' })).toContainText('Familiar informado sobre o andamento');
  await expect(tempo).toContainText('Pendência encerrada');
  await capturar(page, '03-episodio-atualizado');
});

test('abertura com cadastro de novo paciente', async ({ page }) => {
  await entrar(page, 'enf.e2e');
  await page.goto('/#/abrir');
  await page.getByLabel('Valor exato').fill(FIX.novoPacienteCns);
  await page.getByRole('button', { name: 'Buscar' }).click();
  await expect(page.getByText('Nenhum paciente encontrado')).toBeVisible();
  await page.getByLabel(/cadastrar novo paciente/).check();
  const novo = page.getByRole('group', { name: 'Dados do novo paciente' });
  await novo.getByLabel('Nome completo').fill('Paciente Ficticio Epsilon');
  await novo.getByLabel('CNS').fill(FIX.novoPacienteCns);
  await page.getByLabel('Setor de entrada').selectOption({ label: 'Observação Norte' });
  await page.getByRole('button', { name: 'Abrir episódio' }).click();
  await expect(page.getByRole('heading', { name: 'Episódio — Paciente Ficticio Epsilon' })).toBeVisible();
});
