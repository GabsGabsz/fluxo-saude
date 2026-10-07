// Passagem de plantão (RF-016/017): entrega por um profissional e recebimento por outro, com
// conflito entre leitura e confirmação (conteúdo alterado por outra pessoa antes do clique),
// permissões, troca de unidade e sessão revogada. Cada alteração "concorrente" é feita pela API
// DEPOIS que a tela exibiu o conteúdo e ANTES do clique — sem esperas arbitrárias.
import { test, expect } from '@playwright/test';
import { entrar, escolherUnidade, capturar, clienteApi, garantirEpisodio, abrirEpisodio, localNoFuso, FIX, NORTE, SUL }
  from './apoio.mjs';

const alfa = FIX.pacientes.find((p) => p.nome.endsWith('Alfa'));

test('entrega e recebimento por outro profissional: situação atual (antes × agora) e conteúdo alterado antes de cada confirmação', async ({ page, browser, baseURL }) => {
  const coordApi = await clienteApi(baseURL, 'coord.e2e');
  await coordApi.usarUnidade(NORTE.codigo);
  const episodio = await garantirEpisodio(coordApi, { cns: alfa.cns, nome: alfa.nome, setorNome: NORTE.setores[0][1] });
  const setor = (await coordApi.get('/api/catalogo')).setores.find((s) => s.nome === NORTE.setores[0][1]);
  // Caso próprio deste cenário (etapa/motivo mudam depois da entrega sem mexer nos casos de outros testes).
  const kappa = await abrirEpisodio(coordApi, { novoPaciente: { nome: 'Paciente Ficticio Kappa' }, setorNome: NORTE.setores[0][1] });

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

  // ------------------------------------------------------------ mudanças DEPOIS da entrega (outra pessoa)
  // Pendência: responsável (setor → perfil médico) e prazo; caso Kappa: etapa e motivo do bloqueio.
  const prazoNovo = new Date(Math.floor((Date.now() + 9 * 3600_000) / 60_000) * 60_000).toISOString();
  await coordApi.patch(`/api/pendencias/${pend.id}`, { versao: 0, responsavel: { papel: 'MEDICO' }, prazo: prazoNovo });
  const cat = await coordApi.get('/api/catalogo');
  const etapaLeito = cat.etapas.find((e) => e.codigo === 'AGUARDANDO_RECURSO_LEITO');
  const semLeito = cat.motivos.find((m) => m.codigo === 'SEM_LEITO_ESPECIALIDADE');
  const semCapacidade = cat.motivos.find((m) => m.codigo === 'DESTINO_SEM_CAPACIDADE');
  await coordApi.put(`/api/episodios/${kappa}/etapa`,
    { versao: (await coordApi.get(`/api/episodios/${kappa}`)).resumo.versao, etapaId: etapaLeito.id, motivoId: semLeito.id });

  // ------------------------------------------------------------ recebimento (coordenação, outro profissional)
  const contexto = await browser.newContext({ baseURL });
  const outra = await contexto.newPage();
  await entrar(outra, 'coord.e2e');
  await escolherUnidade(outra, NORTE.nome);
  await outra.getByRole('link', { name: 'Passagem de plantão' }).click();
  await outra.getByRole('link', { name: 'Abrir passagem pendente' }).click();

  // A tela mostra a SITUAÇÃO ATUAL (valores novos) ao lado do que foi entregue, antes de confirmar.
  const situacao = outra.getByRole('region', { name: 'Recebimento' });
  await expect(situacao).toContainText('Situação atual');
  const pendItem = situacao.locator(`[data-pendencia="${pend.id}"]`);
  await expect(pendItem).toContainText(`Setor: ${setor.nome}`);                     // na entrega
  await expect(pendItem).toContainText('Perfil: Médico');                            // agora
  await expect(pendItem).toContainText(dataHoraNorte(prazo));                        // prazo na entrega
  await expect(pendItem).toContainText(dataHoraNorte(prazoNovo));                    // prazo atual
  await expect(pendItem.getByRole('link', { name: alfa.nome })).toHaveAttribute('href', `#/episodio/${episodio}`);
  const casoItem = situacao.locator(`[data-caso="${kappa}"]`);
  await expect(casoItem).toContainText('Em atendimento');
  await expect(casoItem).toContainText(etapaLeito.nome);
  await expect(casoItem).toContainText(semLeito.descricao);
  await expect(outra.getByRole('region', { name: 'Conteúdo entregue' })).toContainText('Mudou depois da entrega');
  await capturar(outra, '08-recebimento-situacao-atual');

  // OUTRA mudança depois da leitura (motivo do caso Kappa): a assinatura vista não vale mais.
  await coordApi.put(`/api/episodios/${kappa}/motivo`,
    { versao: (await coordApi.get(`/api/episodios/${kappa}`)).resumo.versao, motivoId: semCapacidade.id });
  const receber = outra.getByRole('form', { name: 'Confirmar recebimento' });
  const [velha] = await Promise.all([
    outra.waitForResponse((r) => r.url().endsWith('/recebimento')),
    receber.getByRole('button', { name: 'Confirmar recebimento' }).click(),
  ]);
  expect(velha.status()).toBe(409);
  expect((await velha.json()).codigo).toBe('RECEBIMENTO_DESATUALIZADO');
  await expect(receber.getByRole('alert')).toContainText('A situação mudou desde que você abriu a passagem');
  const naoConfirmada = (await coordApi.get(`/api/plantao/passagens/${id}`)).passagem;  // nada gravado
  expect(naoConfirmada.status).toBe('ENTREGUE');
  expect(naoConfirmada.recebidaPor).toBeNull();
  expect(naoConfirmada.versao).toBe(0);

  // Nova leitura explícita: o novo motivo aparece; só então a confirmação.
  await receber.getByRole('button', { name: 'Recarregar dados' }).click();
  await expect(outra.getByRole('region', { name: 'Recebimento' }).locator(`[data-caso="${kappa}"]`))
    .toContainText(semCapacidade.descricao);
  await expect(outra.getByRole('region', { name: 'Recebimento' }).locator(`[data-pendencia="${pend.id}"]`))
    .toContainText('Perfil: Médico');
  await capturar(outra, '08-recebimento-com-diferencas');
  await outra.getByRole('form', { name: 'Confirmar recebimento' }).getByRole('button', { name: 'Confirmar recebimento' }).click();
  await expect(outra.getByRole('heading', { name: 'Passagem recebida' })).toBeVisible();
  await expect(outra.getByRole('region', { name: 'Registro da passagem' })).toContainText('Caio Coordenador Ficticio');
  const final = await coordApi.get(`/api/plantao/passagens/${id}`);
  expect(final.passagem.status).toBe('RECEBIDA');
  expect(final.passagem.diferencasRecebimento.casosAlterados).toBe(1);
  expect(final.passagem.diferencasRecebimento.pendenciasAlteradas).toBe(1);
  // A passagem não alterou nada por conta própria: os valores são os que as pessoas gravaram.
  const caso = await coordApi.get(`/api/episodios/${episodio}`);
  const pendFinal = caso.pendencias.find((p) => p.id === pend.id);
  expect(pendFinal.status).toBe('ABERTA');
  expect(caso.encerradoEm).toBeNull();
  await capturar(outra, '08-passagem-recebida');
  await contexto.close();
  await coordApi.fechar();
});

// Alertas no recebimento (revisão do PR #10): a confirmação assina os alertas da situação atual, então a
// tela identifica cada um — o que saiu, o que entrou, o que mudou de versão —, com a regra NA VERSÃO do
// alerta (a configuração atual aparece só como aviso: "desativada/alterada depois"). As regras são de
// "tempo na etapa" numa etapa usada só por este caso, para não mexer nos demais casos nem nos indicadores.
test('recebimento identifica cada alerta: A trocado por B (mesma quantidade), C em nova versão; 409 após nova mudança', async ({ browser, baseURL }) => {
  const admin = await clienteApi(baseURL, 'admin.norte');
  const coordApi = await clienteApi(baseURL, 'coord.e2e');
  await coordApi.usarUnidade(NORTE.codigo);
  const enfApi = await clienteApi(baseURL, 'enf.e2e');
  const cat = await coordApi.get('/api/catalogo');
  const decisao = cat.etapas.find((e) => e.codigo === 'AGUARDANDO_DECISAO');
  const semVaga = cat.motivos.find((m) => m.codigo === 'SEM_VAGA');
  const tresHoras = new Date(Math.floor((Date.now() - 3 * 3600_000) / 60_000) * 60_000).toISOString();
  const duasHoras = new Date(Math.floor((Date.now() - 2 * 3600_000) / 60_000) * 60_000).toISOString();
  const ajuste = (ocorridoEm) => ({ ocorridoEm, justificativaAjuste: 'Registro tardio (cenário de teste)' });
  const lambda = await abrirEpisodio(coordApi, { novoPaciente: { nome: 'Paciente Ficticio Lambda' }, setorNome: NORTE.setores[0][1],
    momento: ajuste(tresHoras) });
  await coordApi.put(`/api/episodios/${lambda}/etapa`, { versao: 0, etapaId: decisao.id, motivoId: semVaga.id, momento: ajuste(duasHoras) });
  const nova = (nome, limiteMinutos, acaoEsperada) => admin.post('/api/config/regras-alerta',
    { nome, tipo: 'TEMPO_NA_ETAPA', etapaId: decisao.id, limiteMinutos, acaoEsperada }, 201);
  const altera = (r, versao, nome, limiteMinutos, acaoEsperada, ativa = true) => admin.put(`/api/config/regras-alerta/${r.id}`,
    { versao, nome, etapaId: decisao.id, limiteMinutos, acaoEsperada, ativa });
  const a = await nova('Decisao demorada (A)', 60, 'Avisar coordenacao');
  const c = await nova('Decisao em atraso (C)', 30, 'Rever conduta');
  let b = null;
  const contexto = await browser.newContext({ baseURL });
  try {
    // Entrega com A e C (pela API: o foco aqui é o recebimento).
    const previa = await enfApi.get('/api/plantao/previa');
    expect(previa.casos.find((x) => x.episodioId === lambda).alertas.map((x) => x.regraId).sort()).toEqual([a.id, c.id].sort());
    const { id } = await enfApi.post('/api/plantao/passagens', { assinatura: previa.assinatura }, 201);

    // Depois da entrega: A renomeada e desativada, B criada, C alterada (limite e ação) → 2 alertas antes e depois.
    await altera(a, 0, 'A renomeada depois', 60, 'Outra acao', false);
    b = await nova('Decisao prolongada (B)', 90, 'Acionar NIR');
    await altera(c, 0, 'Decisao em atraso (C v1)', 45, 'Rever conduta e avisar NIR');

    const outra = await contexto.newPage();
    await entrar(outra, 'coord.e2e');
    await escolherUnidade(outra, NORTE.nome);
    await outra.goto(`/#/plantao/${id}`);
    const situacao = outra.getByRole('region', { name: 'Recebimento' });
    const linha = (regra, mudanca) => situacao.locator(`[data-caso="${lambda}"] tr[data-alerta="${regra.id}"][data-mudanca="${mudanca}"]`);
    const celula = (regra, mudanca, coluna) => linha(regra, mudanca).locator(`[data-rotulo="${coluna}"]`);

    // A saiu: dados da versão 0 (não o nome atual) + aviso de que foi desativada depois.
    await expect(celula(a, 'REMOVIDO', 'Na entrega')).toContainText('Decisao demorada (A) — versão 0; Tempo na etapa; etapa: Aguardando decisão; limite: 1 h 00 min — ação esperada: Avisar coordenacao');
    await expect(celula(a, 'REMOVIDO', 'Na entrega')).toContainText(`referência: ${dataHoraNorte(duasHoras)}`);
    await expect(celula(a, 'REMOVIDO', 'Na entrega')).toContainText('Regra desativada depois (versão atual 1)');
    await expect(celula(a, 'REMOVIDO', 'Agora')).toContainText('Não está mais em alerta');
    await expect(situacao).not.toContainText('A renomeada depois');
    // B entrou.
    await expect(celula(b, 'ADICIONADO', 'Na entrega')).toContainText('Não havia');
    await expect(celula(b, 'ADICIONADO', 'Agora')).toContainText('Decisao prolongada (B) — versão 0; Tempo na etapa; etapa: Aguardando decisão; limite: 1 h 30 min — ação esperada: Acionar NIR');
    await expect(celula(b, 'ADICIONADO', 'Agora'))
      .toContainText(`limite atingido em ${dataHoraNorte(new Date(Date.parse(duasHoras) + 90 * 60_000).toISOString())}`);
    // C mudou de versão: cada lado com os dados da sua versão.
    await expect(celula(c, 'ALTERADO', 'Na entrega')).toContainText('Decisao em atraso (C) — versão 0');
    await expect(celula(c, 'ALTERADO', 'Na entrega')).toContainText('ação esperada: Rever conduta');
    await expect(celula(c, 'ALTERADO', 'Na entrega')).toContainText('Regra alterada depois (versão atual 1)');
    await expect(celula(c, 'ALTERADO', 'Agora')).toContainText('Decisao em atraso (C v1) — versão 1');
    await expect(celula(c, 'ALTERADO', 'Agora')).toContainText('ação esperada: Rever conduta e avisar NIR');
    // Conteúdo entregue: A como era na entrega.
    await expect(outra.getByRole('region', { name: 'Conteúdo entregue' }).locator(`[data-caso="${lambda}"] [data-alerta="${a.id}"]`))
      .toContainText('Decisao demorada (A) — versão 0');
    await capturar(outra, '08-recebimento-alertas');

    // Nova versão de B DEPOIS da leitura: a confirmação vista é recusada e nada é gravado.
    await altera(b, 0, 'Decisao prolongada (B)', 90, 'Acionar NIR e direcao');
    const receber = outra.getByRole('form', { name: 'Confirmar recebimento' });
    const [velha] = await Promise.all([
      outra.waitForResponse((r) => r.url().endsWith('/recebimento')),
      receber.getByRole('button', { name: 'Confirmar recebimento' }).click(),
    ]);
    expect(velha.status()).toBe(409);
    expect((await velha.json()).codigo).toBe('RECEBIMENTO_DESATUALIZADO');
    const naoConfirmada = (await coordApi.get(`/api/plantao/passagens/${id}`)).passagem;
    expect(naoConfirmada.status).toBe('ENTREGUE');
    expect(naoConfirmada.recebidaPor).toBeNull();
    expect(naoConfirmada.versao).toBe(0);

    // Recarga explícita: B na versão 1; só então a confirmação.
    await receber.getByRole('button', { name: 'Recarregar dados' }).click();
    await expect(celula(b, 'ADICIONADO', 'Agora')).toContainText('Decisao prolongada (B) — versão 1');
    await expect(celula(b, 'ADICIONADO', 'Agora')).toContainText('ação esperada: Acionar NIR e direcao');
    await outra.getByRole('form', { name: 'Confirmar recebimento' }).getByRole('button', { name: 'Confirmar recebimento' }).click();
    await expect(outra.getByRole('heading', { name: 'Passagem recebida' })).toBeVisible();
    expect((await coordApi.get(`/api/plantao/passagens/${id}`)).passagem.status).toBe('RECEBIDA');

  } finally {
    // Limpeza mesmo em falha: regras do cenário desativadas (não interferem nos testes seguintes).
    const regras = await admin.get('/api/config/regras-alerta');
    for (const r of regras.filter((x) => x.ativa && [a.id, c.id, b && b.id].includes(x.id))) {
      await admin.put(`/api/config/regras-alerta/${r.id}`, { versao: r.versao, nome: r.nome, etapaId: decisao.id,
        limiteMinutos: r.limiteMinutos, acaoEsperada: r.acaoEsperada, ativa: false });
    }
    await contexto.close();
  }
  await Promise.all([admin.fechar(), coordApi.fechar(), enfApi.fechar()]);
});

/** Mesmo formato da tela (dd/mm/aaaa hh:mm) no fuso da unidade Norte. */
function dataHoraNorte(iso) {
  const [data, hora] = localNoFuso(Date.parse(iso), NORTE.fuso).split('T');
  const [a, m, d] = data.split('-');
  return `${d}/${m}/${a} ${hora}`;
}

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

