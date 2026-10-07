// Relatórios gerenciais (issue #9) contra a aplicação e o PostgreSQL REAIS: valor calculado à mão a partir
// de uma linha do tempo criada pela API (filtros de setor e etapa), CSV e impressão iguais ao que a tela
// mostra (mesma assinatura, sem recálculo), CSV com textos semelhantes a fórmulas protegidos, impressão
// paginada (PDF do navegador), Direção sem dado nominal, troca de unidade e sessão revogada.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';
import { entrar, escolherUnidade, capturar, clienteApi, abrirEpisodio, NORTE, SUL } from './apoio.mjs';

const aqui = path.dirname(fileURLToPath(import.meta.url));
const EMERG = 'Emergência Norte';

test('coordenação: gargalo calculado à mão, CSV e impressão iguais à tela, fórmulas neutralizadas', async ({ page, baseURL }) => {
  const coord = await clienteApi(baseURL, 'coord.e2e');
  await coord.usarUnidade(NORTE.codigo);
  const cat = await coord.get('/api/catalogo');
  const solicitar = cat.etapas.find((e) => e.codigo === 'AGUARDANDO_SOLICITACAO_TRANSFERENCIA');
  const atendimento = cat.etapas.find((e) => e.codigo === 'EM_ATENDIMENTO');
  const naoEnviada = cat.motivos.find((m) => m.codigo === 'SOLICITACAO_NAO_ENVIADA');
  const t = Math.floor(Date.now() / 60_000) * 60_000;
  const em = (h) => ({ ocorridoEm: new Date(t - h * 3600_000).toISOString(), justificativaAjuste: 'Registro tardio (cenário de teste)' });
  // Mu, em Emergência Norte: atendimento 3 h atrás; aguardando solicitação (bloqueio REGULAÇÃO) 2 h atrás; volta 1 h atrás.
  const mu = await abrirEpisodio(coord, { novoPaciente: { nome: 'Paciente Ficticio Mu' }, setorNome: EMERG, momento: em(3) });
  const v = (await coord.put(`/api/episodios/${mu}/etapa`, { versao: 0, etapaId: solicitar.id, motivoId: naoEnviada.id, momento: em(2) })).versao;
  await coord.put(`/api/episodios/${mu}/etapa`, { versao: v, etapaId: atendimento.id, momento: em(1) });
  const setor = cat.setores.find((s) => s.nome === EMERG);
  const prazo = new Date(Date.now() + 8 * 3600_000).toISOString();
  await coord.post(`/api/episodios/${mu}/pendencias`, { categoria: 'LOGISTICA', descricao: '=HYPERLINK("http://exemplo.invalido") ambulancia',
    responsavel: { setorId: setor.id }, prazo, criticidade: 'ALTA' }, 201);
  for (let i = 1; i <= 45; i += 1) {   // lista longa: impressão em várias páginas
    await coord.post(`/api/episodios/${mu}/pendencias`, { categoria: 'ADMINISTRATIVO', descricao: `Conferir documento ${i}; "copia"`,
      responsavel: { papel: 'ENFERMAGEM' }, prazo, criticidade: 'BAIXA' }, 201);
  }

  await entrar(page, 'coord.e2e');
  await escolherUnidade(page, NORTE.nome);
  await page.getByRole('link', { name: 'Relatórios' }).click();
  await expect(page.getByRole('heading', { name: 'Relatórios gerenciais' })).toBeVisible();
  const filtros = page.getByRole('form', { name: 'Filtros do relatório' });
  await filtros.getByLabel('Relatório').selectOption('GARGALOS');
  const fim = await filtros.getByLabel('Fim (inclusive)').inputValue();
  const ontem = new Date(`${fim}T12:00:00Z`);
  ontem.setUTCDate(ontem.getUTCDate() - 1);
  await filtros.getByLabel(/^Início/).fill(ontem.toISOString().slice(0, 10));
  await filtros.getByLabel('Setor').selectOption({ label: EMERG });
  await filtros.getByLabel('Etapa (só gargalos)').selectOption({ label: solicitar.nome });
  const [resp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/relatorios/gargalos?')),
    filtros.getByRole('button', { name: 'Calcular' }).click(),
  ]);
  const dados = await resp.json();
  const relatorio = page.getByRole('article', { name: 'Relatório' });
  // Valor à mão: 60 min na etapa (2 h → 1 h atrás), no setor Emergência; bloqueio REGULAÇÃO de 60 min no mesmo trecho.
  const etapaConcl = relatorio.locator('[data-secao="ETAPA_CONCLUIDA"]');
  await expect(etapaConcl).toContainText(solicitar.nome);
  await expect(etapaConcl.locator('tbody tr').first()).toContainText('1 h 00 min');
  const bloq = relatorio.locator('[data-secao="BLOQUEIO_CATEGORIA"]');
  await expect(bloq).toContainText('Regulação');
  await expect(bloq.locator('tbody tr').first()).toContainText('1 h 00 min');
  await expect(relatorio).toContainText(`setor: ${EMERG}; etapa: ${solicitar.nome}`);
  await expect(relatorio.locator('[data-secao="SETOR_TEMPO"] .limitacao')).toContainText('linha do tempo');
  const assinatura = dados.assinatura;
  await expect(relatorio.locator('.assinatura')).toHaveText(assinatura);
  const linhaApi = dados.linhas.find((l) => l.secao === 'BLOQUEIO_CATEGORIA' && l.chave === 'REGULACAO');
  expect(linhaApi.minutos).toBeCloseTo(60, 5);

  // CSV: registrado no servidor e gerado do MESMO resultado (assinatura, linhas e valores iguais).
  const [download, registro] = await Promise.all([
    page.waitForEvent('download'),
    page.waitForResponse((r) => r.url().endsWith('/api/relatorios/exportacoes')),
    relatorio.getByRole('button', { name: 'Baixar CSV' }).click(),
  ]);
  expect(registro.status()).toBe(201);
  expect((await registro.json()).assinatura).toBe(assinatura);
  const csv = fs.readFileSync(await download.path(), 'utf8');
  expect(csv.charCodeAt(0)).toBe(0xFEFF);
  expect(csv).toContain(`"Assinatura do conjunto (SHA-256)";"${assinatura}"`);
  expect(csv).toContain(`"BLOQUEIO_CATEGORIA";"Tempo bloqueado por categoria (registrada na época)";"REGULACAO"`);
  const dadosCsv = csv.split('\r\n').filter((l) => /^"";"[A-Z_]+";/.test(l));
  expect(dadosCsv.length).toBe(dados.linhas.length);
  await expect(relatorio.locator('dt:text-is("Linhas de dados") + dd')).toHaveText(String(dados.linhas.length));
  // Definições dos cálculos vão no próprio CSV (as do resultado assinado), com a versão.
  expect(csv).toContain(`"Definições (${dados.versaoCalculo})";"Nome";"Fórmula";"Unidade de medida";"População"`);
  expect(csv).toContain('"BLOQUEIO_CATEGORIA";"Tempo bloqueado por categoria";"por categoria registrada no INÍCIO');
  expect(dados.definicoes.map((d) => d.codigo)).toContain('BLOQUEIO_CATEGORIA');
  fs.mkdirSync(path.join(aqui, '..', 'capturas'), { recursive: true });
  fs.writeFileSync(path.join(aqui, '..', 'capturas', '10-relatorio-gargalos.csv'), csv);

  // Pendências: lista nominal (coordenação) com o texto semelhante a fórmula neutralizado no CSV.
  await filtros.getByLabel('Relatório').selectOption('PENDENCIAS');
  await filtros.getByLabel('Setor').selectOption({ label: EMERG });
  await Promise.all([page.waitForResponse((r) => r.url().includes('/api/relatorios/pendencias?')),
    filtros.getByRole('button', { name: 'Calcular' }).click()]);
  const lista = relatorio.locator('[data-secao="LISTA_PENDENCIAS"]');
  await expect(lista).toContainText('Paciente Ficticio Mu');
  expect(await lista.locator('tbody tr').count()).toBeGreaterThanOrEqual(46);   // 46 do Mu (+ eventuais de outros cenários)
  const [download2] = await Promise.all([page.waitForEvent('download'),
    relatorio.getByRole('button', { name: 'Baixar CSV' }).click()]);
  const csv2 = fs.readFileSync(await download2.path(), 'utf8');
  expect(csv2).toContain(`"'=HYPERLINK(""http://exemplo.invalido"") ambulancia"`);
  expect(csv2).toContain('"Conferir documento 7; ""copia"""');

  // Impressão: solicitada (registrada) e versão de impressão legível, paginada, sem menus nem botões.
  await page.evaluate(() => { window.print = () => { window.impressoes = (window.impressoes || 0) + 1; }; });
  await Promise.all([page.waitForResponse((r) => r.url().endsWith('/api/relatorios/exportacoes') && r.status() === 201),
    relatorio.getByRole('button', { name: /Imprimir/ }).click()]);
  expect(await page.evaluate(() => window.impressoes)).toBe(1);
  await page.emulateMedia({ media: 'print' });
  await expect(page.getByRole('navigation', { name: 'Telas' })).toBeHidden();
  await expect(filtros).toBeHidden();
  await expect(relatorio.getByRole('button', { name: 'Baixar CSV' })).toBeHidden();
  await expect(relatorio.locator('.assinatura')).toBeVisible();
  await expect(relatorio.locator('[data-definicao="ABERTAS"]').first()).toBeVisible();   // definição impressa junto da seção
  await capturar(page, '10-relatorio-impressao');
  const pdf = await page.pdf({ preferCSSPageSize: true, printBackground: false });   // A4 paisagem (CSS @page)
  fs.writeFileSync(path.join(aqui, '..', 'capturas', '10-relatorio-pendencias.pdf'), pdf);
  const paginas = (pdf.toString('latin1').match(/\/Type\s*\/Page[^s]/g) || []).length;
  expect(paginas).toBeGreaterThan(1);
  await page.emulateMedia({ media: 'screen' });

  // Evolução: período que inclui hoje é recusado NO SERVIDOR (não depende do navegador).
  expect(await coord.status('GET', `/api/relatorios/evolucao?inicio=${fim}&fim=${fim}`)).toBe(422);
  expect(await coord.status('GET', `/api/relatorios/evolucao?inicio=${ontem.toISOString().slice(0, 10)}&fim=${ontem.toISOString().slice(0, 10)}`))
    .toBe(200);

  // Troca de unidade: o relatório da Norte some; o cálculo seguinte é da Sul.
  await escolherUnidade(page, SUL.nome);
  await page.goto('/#/relatorios');
  await expect(page.getByRole('article', { name: 'Relatório' })).toHaveCount(0);
  await Promise.all([page.waitForResponse((r) => r.url().includes('/api/relatorios/resumo?')),
    page.getByRole('button', { name: 'Calcular' }).click()]);
  await expect(page.getByRole('article', { name: 'Relatório' })).toContainText(SUL.nome);
  await coord.fechar();
});

test('direção: só agregados, sem lista nominal nem links; sessão revogada encerra a tela', async ({ page, baseURL }) => {
  await entrar(page, 'direcao.e2e');
  await expect(page.getByRole('navigation', { name: 'Telas' })).toContainText('Relatórios');
  await page.getByRole('link', { name: 'Relatórios' }).click();
  const filtros = page.getByRole('form', { name: 'Filtros do relatório' });
  const hoje = await filtros.getByLabel('Fim (inclusive)').inputValue();
  for (const tipo of ['RESUMO', 'PENDENCIAS', 'GARGALOS', 'EVOLUCAO', 'QUALIDADE']) {
    await filtros.getByLabel('Relatório').selectOption(tipo);
    const [r] = await Promise.all([page.waitForResponse((x) => x.url().includes(`/api/relatorios/${tipo.toLowerCase()}?`)),
      filtros.getByRole('button', { name: 'Calcular' }).click()]);
    expect(r.status()).toBe(200);
    const corpo = await r.text();
    expect(corpo).not.toContain('Paciente Ficticio');
    expect(corpo).not.toContain('"episodioId"');
    await expect(page.getByRole('article', { name: 'Relatório' })).toBeVisible();
    await expect(page.locator('main a[href^="#/episodio"]')).toHaveCount(0);
    await expect(page.locator('main')).not.toContainText('Paciente Ficticio');
    if (tipo === 'PENDENCIAS') await expect(page.locator('main')).toContainText('Perfil sem acesso nominal');
    if (tipo === 'EVOLUCAO') {
      await expect(page.locator('main')).toContainText('Alertas operacionais não são comparados');
      // Só períodos encerrados: o formulário levou o fim para ontem e explica a restrição.
      expect(await filtros.getByLabel('Fim (inclusive)').inputValue() < hoje).toBe(true);
      await expect(filtros).toContainText('compara só períodos ENCERRADOS');
      // Definições e limitações no próprio relatório (impressas), inclusive a exclusão da permanência.
      const rel = page.getByRole('article', { name: 'Relatório' });
      await expect(rel.getByRole('region', { name: 'Definições das métricas comparadas' })).toContainText('encerramento administrativo');
      await expect(rel).toContainText('ÚLTIMO prazo');
      await expect(rel).toContainText('pontos percentuais');
    }
  }
  await capturar(page, '10-relatorio-direcao');

  await page.getByRole('button', { name: 'Sair' }).click();

  // Sessão revogada (senha provisória redefinida pela administração) no meio do uso: a tela pede novo
  // login, sem o relatório. Conta própria do teste, para não alterar o usuário compartilhado direcao.e2e.
  const admin = await clienteApi(baseURL, 'admin.norte');
  const criado = await admin.post('/api/admin/usuarios', { login: 'direcao.relatorios.e2e', nome: 'Diana Direcao Ficticia',
    papeis: ['DIRECAO'] }, 201);
  await entrar(page, 'direcao.relatorios.e2e', criado.senhaProvisoria);
  const nova = 'tucano azul na varanda 5823';
  await page.getByLabel('Senha atual').fill(criado.senhaProvisoria);
  await page.getByLabel('Nova senha', { exact: true }).fill(nova);
  await page.getByLabel('Confirme a nova senha').fill(nova);
  await page.getByRole('button', { name: 'Trocar senha' }).click();
  await page.getByRole('link', { name: 'Relatórios' }).click();
  await Promise.all([page.waitForResponse((x) => x.url().includes('/api/relatorios/resumo?') && x.status() === 200),
    filtros.getByRole('button', { name: 'Calcular' }).click()]);
  await expect(page.getByRole('article', { name: 'Relatório' })).toBeVisible();
  const conta = await admin.get(`/api/admin/usuarios/${criado.id}`);
  await admin.post(`/api/admin/usuarios/${criado.id}/senha-provisoria`, { versao: conta.versao }, 200);
  await admin.fechar();
  await filtros.getByRole('button', { name: 'Calcular' }).click();
  await expect(page.getByText('Sua sessão foi encerrada')).toBeVisible();
  await expect(page.getByRole('article', { name: 'Relatório' })).toHaveCount(0);
});
