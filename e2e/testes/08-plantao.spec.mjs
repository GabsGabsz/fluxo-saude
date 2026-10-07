// Passagem de plantão (RF-016/017): entrega por um profissional e recebimento por outro, com
// conflito entre leitura e confirmação (conteúdo alterado por outra pessoa antes do clique),
// permissões, troca de unidade e sessão revogada. Cada alteração "concorrente" é feita pela API
// DEPOIS que a tela exibiu o conteúdo e ANTES do clique — sem esperas arbitrárias.
import { test, expect } from '@playwright/test';
import { entrar, escolherUnidade, capturar, clienteApi, garantirEpisodio, FIX, NORTE, SUL } from './apoio.mjs';

const alfa = FIX.pacientes.find((p) => p.nome.endsWith('Alfa'));

test('entrega e recebimento por outro profissional, com conteúdo alterado antes de cada confirmação', async ({ page, browser, baseURL }) => {
  const coordApi = await clienteApi(baseURL, 'coord.e2e');
  await coordApi.usarUnidade(NORTE.codigo);
  const episodio = await garantirEpisodio(coordApi, { cns: alfa.cns, nome: alfa.nome, setorNome: NORTE.setores[0][1] });
  const setor = (await coordApi.get('/api/catalogo')).setores.find((s) => s.nome === NORTE.setores[0][1]);

  // ------------------------------------------------------------ preparação (enfermagem)
  await entrar(page, 'enf.e2e');
  await page.getByRole('link', { name: 'Passagem de plantão' }).click();
  await expect(page.getByRole('heading', { name: 'Passagem de plantão', exact: true })).toBeVisible();
  const preparar = page.getByRole('region', { name: 'Preparar passagem' });
  await expect(preparar).toContainText('Todos os casos estão listados abaixo (sem paginação)');
  await expect(page.getByRole('region', { name: /Demais casos ativos|Casos críticos|Transferências/ }).first()).toBeVisible();
  await expect(page.getByText(alfa.nome).first()).toBeVisible();

  // Outra pessoa cria uma pendência DEPOIS que a tela exibiu o conteúdo.
  const prazo = new Date(Date.now() + 3 * 3600_000).toISOString();
  const pend = await coordApi.post(`/api/episodios/${episodio}/pendencias`, { categoria: 'LOGISTICA',
    descricao: 'Confirmar ambulancia para remocao', responsavel: { setorId: setor.id }, prazo, criticidade: 'CRITICA' }, 201);

  const entrega = page.getByRole('form', { name: 'Entregar passagem' });
  await entrega.getByLabel('Observação da passagem (opcional)').fill('Leito 4 em higienizacao');
  const [recusa] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith('/api/plantao/passagens') && r.request().method() === 'POST'),
    entrega.getByRole('button', { name: 'Entregar passagem' }).click(),
  ]);
  expect(recusa.status()).toBe(409);
  expect((await recusa.json()).codigo).toBe('PASSAGEM_DESATUALIZADA');
  await expect(entrega.getByRole('alert')).toContainText('O conteúdo mudou desde que você o viu');
  expect((await coordApi.get('/api/plantao/passagens')).filter((x) => x.status === 'ENTREGUE')).toEqual([]); // nada gravado
  await capturar(page, '08-entrega-recusada-conteudo-alterado');

  // Nova leitura explícita + nova ação explícita
  await entrega.getByRole('button', { name: 'Recarregar dados' }).click();
  await expect(page.getByText('Confirmar ambulancia para remocao').first()).toBeVisible();
  await expect(page.getByRole('region', { name: /Casos críticos/ })).toContainText(alfa.nome);
  await page.getByRole('form', { name: 'Entregar passagem' }).getByRole('button', { name: 'Entregar passagem' }).click();
  await expect(page.getByRole('region', { name: 'Aguardando recebimento' })).toBeVisible();
  await expect(page.getByText('Leito 4 em higienizacao')).toBeVisible();
  await capturar(page, '08-passagem-entregue');
  const id = page.url().split('/').pop();

  // ------------------------------------------------------------ recebimento (coordenação, outro profissional)
  const contexto = await browser.newContext({ baseURL });
  const outra = await contexto.newPage();
  await entrar(outra, 'coord.e2e');
  await escolherUnidade(outra, NORTE.nome);
  await outra.getByRole('link', { name: 'Passagem de plantão' }).click();
  await outra.getByRole('link', { name: 'Abrir passagem pendente' }).click();
  await expect(outra.getByText('Nenhuma diferença entre o conteúdo entregue e a situação atual.')).toBeVisible();

  // A pendência é resolvida por outra pessoa DEPOIS que o recebedor abriu a passagem.
  const enfApi = await clienteApi(baseURL, 'enf.e2e');
  await enfApi.post(`/api/pendencias/${pend.id}/resolucao`, { versao: 0, texto: 'Ambulancia confirmada' }, 200);
  const receber = outra.getByRole('form', { name: 'Confirmar recebimento' });
  const [velha] = await Promise.all([
    outra.waitForResponse((r) => r.url().endsWith('/recebimento')),
    receber.getByRole('button', { name: 'Confirmar recebimento' }).click(),
  ]);
  expect(velha.status()).toBe(409);
  expect((await velha.json()).codigo).toBe('RECEBIMENTO_DESATUALIZADO');
  await expect(receber.getByRole('alert')).toContainText('A situação mudou desde que você abriu a passagem');
  expect((await coordApi.get(`/api/plantao/passagens/${id}`)).passagem.status).toBe('ENTREGUE'); // nada confirmado

  await receber.getByRole('button', { name: 'Recarregar dados' }).click();
  await expect(outra.getByRole('region', { name: 'Recebimento' })).toContainText('Pendências encerradas (1)');
  await capturar(outra, '08-recebimento-com-diferencas');
  await outra.getByRole('form', { name: 'Confirmar recebimento' }).getByRole('button', { name: 'Confirmar recebimento' }).click();
  await expect(outra.getByRole('heading', { name: 'Passagem recebida' })).toBeVisible();
  await expect(outra.getByRole('region', { name: 'Registro da passagem' })).toContainText('Caio Coordenador Ficticio');
  const final = await coordApi.get(`/api/plantao/passagens/${id}`);
  expect(final.passagem.status).toBe('RECEBIDA');
  expect(final.passagem.diferencasRecebimento.pendenciasEncerradas).toBe(1);
  // A passagem não resolveu nem alterou nada por conta própria: a pendência foi resolvida pela enfermagem.
  const caso = await coordApi.get(`/api/episodios/${episodio}`);
  expect(caso.pendencias.find((p) => p.id === pend.id).status).toBe('RESOLVIDA');
  expect(caso.encerradoEm).toBeNull();
  await capturar(outra, '08-passagem-recebida');
  await contexto.close();
  await coordApi.fechar();
  await enfApi.fechar();
});

test('quem entregou não confirma o próprio recebimento; pode cancelar com justificativa', async ({ page, baseURL }) => {
  await entrar(page, 'enf.e2e');
  await page.goto('/#/plantao');
  await page.getByRole('form', { name: 'Entregar passagem' }).getByRole('button', { name: 'Entregar passagem' }).click();
  const aguardando = page.getByRole('region', { name: 'Aguardando recebimento' });
  await expect(aguardando).toContainText('o recebimento deve ser confirmado por outro profissional');
  await expect(page.getByRole('button', { name: 'Confirmar recebimento' })).toHaveCount(0);
  await aguardando.getByText('Cancelar esta passagem').click();
  await aguardando.getByLabel('Justificativa do cancelamento').fill('Entregue antes da hora');
  await aguardando.getByRole('button', { name: 'Cancelar passagem' }).click();
  await expect(page.getByRole('heading', { name: 'Passagem cancelada' })).toBeVisible();
  const api = await clienteApi(baseURL, 'enf.e2e');
  expect((await api.get('/api/plantao/passagens')).filter((x) => x.status === 'ENTREGUE')).toEqual([]);
  await api.fechar();
});

// Depende do teste anterior ter cancelado a passagem (sem pendente, a tela mostra "Preparar passagem").
test('permissões, troca de unidade e sessão revogada na tela de plantão', async ({ page, baseURL }) => {
  // Perfis sem PLANTAO_GERENCIAR
  await entrar(page, 'direcao.e2e');
  await expect(page.getByRole('heading', { name: 'Painel coletivo' })).toBeVisible();   // menu já desenhado
  await expect(page.getByRole('navigation', { name: 'Telas' })).toContainText('Indicadores');
  await expect(page.getByRole('navigation', { name: 'Telas' })).not.toContainText('Passagem de plantão');
  await page.goto('/#/plantao');
  await expect(page.getByRole('heading', { name: 'Acesso não permitido' })).toBeVisible();
  await page.getByRole('button', { name: 'Sair' }).click();

  // Troca de unidade: o conteúdo da Norte some e aparece o da Sul.
  await entrar(page, 'coord.e2e');
  await escolherUnidade(page, NORTE.nome);
  await page.goto('/#/plantao');
  await expect(page.getByText(alfa.nome).first()).toBeVisible();
  await escolherUnidade(page, SUL.nome);
  await page.goto('/#/plantao');
  await expect(page.getByRole('region', { name: 'Preparar passagem' })).toBeVisible();
  await expect(page.getByText(alfa.nome)).toHaveCount(0);
  await page.getByRole('button', { name: 'Sair' }).click();

  // Sessão revogada: um profissional novo (criado pela administração) perde a sessão no meio do uso.
  const admin = await clienteApi(baseURL, 'admin.norte');
  const criado = await admin.post('/api/admin/usuarios', { login: 'medico.plantao.e2e', nome: 'Mario Medico Ficticio',
    papeis: ['MEDICO'] }, 201);
  await entrar(page, 'medico.plantao.e2e', criado.senhaProvisoria);
  const nova = 'girafa amarela no zoologico 4417';
  await page.getByLabel('Senha atual').fill(criado.senhaProvisoria);
  await page.getByLabel('Nova senha', { exact: true }).fill(nova);
  await page.getByLabel('Confirme a nova senha').fill(nova);
  await page.getByRole('button', { name: 'Trocar senha' }).click();
  await page.getByRole('link', { name: 'Passagem de plantão' }).click();
  await expect(page.getByRole('heading', { name: 'Passagem de plantão', exact: true })).toBeVisible();
  const conta = await admin.get(`/api/admin/usuarios/${criado.id}`);
  await admin.post(`/api/admin/usuarios/${criado.id}/senha-provisoria`, { versao: conta.versao }, 200);
  await admin.fechar();
  await page.getByRole('button', { name: 'Atualizar conteúdo' }).click();
  await expect(page.getByText('Sua sessão foi encerrada')).toBeVisible();
  await expect(page.getByText(alfa.nome)).toHaveCount(0);
});

