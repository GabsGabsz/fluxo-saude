// Relatórios gerenciais (issue #9): CSV seguro e fiel ao resultado exibido; tela e exportação a partir
// do MESMO objeto (sem recálculo); exportação só depois do registro no servidor; Direção sem lista nominal.
import test from 'node:test';
import assert from 'node:assert/strict';
import { No, esperar } from './dom-minimo.mjs';

const rel = await import('../../main/resources/static/app/js/nucleo/relatorios.js');
const { montar } = await import('../../main/resources/static/app/js/telas/relatorios.js');

const L = (secao, chave, extra = {}) => ({ periodo: null, secao, chave, rotulo: null, grupo: null, quantidade: null, parte: null,
  base: null, episodios: null, minutos: null, media: null, mediana: null, p90: null, maximo: null, ...extra });

function resultado(tipo, linhas, extra = {}) {
  return {
    tipo, titulo: 'Relatório de teste', versaoCalculo: 'relatorios-v1', unidadeId: 'u1', unidadeNome: 'UPA Teste; "Norte"',
    fuso: 'America/Fortaleza', inicio: '2026-10-01', fim: '2026-10-07', inicioEm: '2026-10-01T03:00:00Z', fimEm: '2026-10-08T03:00:00Z',
    referencia: '2026-10-07T15:00:00Z', geradoEm: '2026-10-07T15:00:01Z', filtros: { setor: null, etapa: null, categoria: null },
    comparacao: null, linhas, listaPendencias: { disponivel: false, motivo: null, limite: 2000, itens: [] }, limitacoes: [],
    cobertura: 'Operação registrada pela equipe.', verbetes: {}, definicoes: [], assinatura: 'ab'.repeat(32), comprovante: 'carga.mac', ...extra,
  };
}

// ------------------------------------------------------------------ CSV
test('CSV: textos semelhantes a fórmulas recebem apóstrofo; números continuam números', () => {
  for (const perigoso of ['=SOMA(A1)', '+55 1', '-cmd', '@SUM(1)', '\t=1', '  =1', ' =1', '＝1', '\r=1']) {
    assert.ok(rel.celulaTexto(perigoso).startsWith(`"'`), `protegido: ${JSON.stringify(perigoso)}`);
  }
  assert.equal(rel.celulaTexto('Texto "com aspas"; e ponto-e-vírgula'), '"Texto ""com aspas""; e ponto-e-vírgula"');
  assert.equal(rel.celulaTexto('Linha\nnova'), '"Linha\nnova"');
  assert.equal(rel.celulaTexto('Ação — açúcar ç'), '"Ação — açúcar ç"');
  assert.equal(rel.celulaTexto(null), '""');
  assert.equal(rel.celulaNumero(-3), '-3', 'número negativo NÃO recebe apóstrofo');
  assert.equal(rel.celulaNumero(1.5), '1,5');
  assert.equal(rel.celulaNumero(642.000000001), '642');
  assert.equal(rel.celulaNumero(null), '');
});

test('CSV: metadados, linhas, lista nominal protegida e limitações — o mesmo conjunto exibido', () => {
  const r = resultado('PENDENCIAS', [L('ABERTAS_TOTAL', null, { quantidade: 2, parte: 1, mediana: 300.4 }),
    L('ENCERRADAS', 'RESOLVIDA', { quantidade: 4, parte: 3, base: 4 })], {
    listaPendencias: { disponivel: true, motivo: null, limite: 2000, itens: [{ pendenciaId: 'p1', episodioId: 'e1',
      pacienteNome: '@Paciente Ficticio', setorNome: 'Obs', etapaNome: 'Atendimento', motivoDescricao: null,
      descricao: '=HYPERLINK("http://x") transporte', categoria: 'LOGISTICA', criticidade: 'ALTA', responsavelTipo: 'SETOR',
      responsavelNome: 'Obs', prazo: '2026-10-07T18:00:00Z', criadaEm: '2026-10-07T10:00:00Z', vencida: true }] },
    limitacoes: [{ codigo: 'PRAZO_ULTIMO', secao: 'ENCERRADAS', texto: 'Usa o último prazo' }],
  });
  const csv = rel.gerarCsv(r);
  assert.ok(csv.startsWith('﻿'), 'BOM UTF-8');
  assert.ok(csv.includes('\r\n'), 'CRLF');
  assert.ok(csv.includes(`"Assinatura do conjunto (SHA-256)";"${'ab'.repeat(32)}"`));
  assert.ok(csv.includes('"Unidade";"UPA Teste; ""Norte"""'), 'texto com ; e aspas escapado');
  assert.ok(csv.includes('"ENCERRADAS";"Encerradas no período, por situação";"RESOLVIDA";"";"";4;3;4;75;'),
    'linha de dados com números e percentual derivado');
  assert.ok(csv.includes('300,4'), 'decimal com vírgula');
  assert.ok(csv.includes(`"'@Paciente Ficticio"`) && csv.includes(`"'=HYPERLINK(""http://x"") transporte"`),
    'dados nominais semelhantes a fórmulas protegidos');
  assert.ok(csv.includes('"PRAZO_ULTIMO";"ENCERRADAS";"Usa o último prazo"'));
  const direcao = rel.gerarCsv(resultado('PENDENCIAS', [], { listaPendencias: { disponivel: false,
    motivo: 'Perfil sem acesso nominal: só agregados.', limite: 2000, itens: [] } }));
  assert.ok(direcao.includes('Perfil sem acesso nominal') && !direcao.includes('Paciente'));
});

// Definição como o servidor a devolve DENTRO do resultado (DefinicaoIndicador).
const DEF = (codigo, extra = {}) => ({ codigo, nome: `Nome ${codigo}`, requisitos: 'issue #9', finalidade: 'f', formula: `fórmula ${codigo}`,
  unidadeMedida: 'minutos', populacao: `população ${codigo}`, exclusoes: '—', denominador: 'n', marcoInicial: 'a', marcoFinal: 'b',
  campoTemporal: 'c', abertosEEncerrados: 'd', dadosAusentes: 'e', repeticoes: 'r', periodoEFronteiras: 'p',
  situacao: 'PROPOSTA — V-09', ...extra });

function evolucao() {
  return resultado('EVOLUCAO', [
    L('PERMANENCIA', null, { periodo: 'ANTERIOR', quantidade: 2, mediana: 300 }), L('PERMANENCIA', null, { periodo: 'ATUAL', quantidade: 3, mediana: 660 }),
    L('PENDENCIAS_ENCERRADAS', null, { periodo: 'ANTERIOR', quantidade: 4, parte: 2, base: 4 }),
    L('PENDENCIAS_ENCERRADAS', null, { periodo: 'ATUAL', quantidade: 4, parte: 3, base: 4 })], {
    comparacao: { inicio: '2026-09-23', fim: '2026-09-29', inicioEm: 'x', fimEm: 'y', horasAnterior: 168, horasAtual: 168 },
    inicio: '2026-09-30', fim: '2026-10-06',
    verbetes: { EVOLUCAO: 'EVOLUCAO', PERMANENCIA: 'PERMANENCIA', PENDENCIAS_ENCERRADAS: 'ENCERRADAS' },
    definicoes: [DEF('EVOLUCAO', { formula: 'variação absoluta em pontos percentuais para métricas em %' }),
      DEF('PERMANENCIA', { exclusoes: 'Desfecho "encerramento administrativo" — proposta' }),
      DEF('ENCERRADAS', { campoTemporal: '"No prazo" usa o ÚLTIMO prazo registrado.', formula: '=HYPERLINK("x") definição' })],
    limitacoes: [{ codigo: 'PRAZO_ULTIMO', secao: 'PENDENCIAS_ENCERRADAS', texto: '"Até o prazo" usa o ÚLTIMO prazo' },
      { codigo: 'PERMANENCIA_SEM_ADMINISTRATIVO', secao: 'PERMANENCIA', texto: 'Permanência exclui encerramento administrativo' },
      { codigo: 'PERIODOS_ENCERRADOS', secao: null, texto: 'Só períodos encerrados' }],
  });
}

test('CSV leva as definições e limitações do PRÓPRIO resultado, protegidas contra fórmulas, e as unidades das variações', () => {
  const csv = rel.gerarCsv(evolucao());
  assert.ok(csv.includes('"Definições (relatorios-v1)";"Nome";"Fórmula";"Unidade de medida";"População";"Exclusões";"Denominador"'));
  assert.ok(csv.includes('"PERMANENCIA";"Nome PERMANENCIA";"fórmula PERMANENCIA";"minutos";"população PERMANENCIA";'
    + '"Desfecho ""encerramento administrativo"" — proposta"'), 'exclusão da permanência no CSV');
  assert.ok(csv.includes('"""No prazo"" usa o ÚLTIMO prazo registrado."'), 'último prazo no CSV');
  assert.ok(csv.includes(`"'=HYPERLINK(""x"") definição"`), 'texto novo semelhante a fórmula também protegido');
  assert.ok(csv.includes('"PRAZO_ULTIMO";"PENDENCIAS_ENCERRADAS"') && csv.includes('"PERMANENCIA_SEM_ADMINISTRATIVO";"PERMANENCIA"'));
  assert.ok(csv.includes('"Variação absoluta";"Variação relativa (%)";"Unidade do valor";"Unidade da variação absoluta"'));
  assert.ok(/"% encerradas até o \(último\) prazo";50;75;25;"não se aplica";"percentual \(%\)";"pontos percentuais \(p\.p\.\)";"ENCERRADAS"/.test(csv),
    'métrica em %: variação em p.p., relativa não se aplica');
  assert.ok(/"Permanência mediana[^"]*";300;660;360;120;"minutos";"minutos";"PERMANENCIA"/.test(csv));
  assert.ok(csv.includes('mesma duração: 168 h') && csv.includes('só períodos encerrados'));
  assert.ok(csv.includes(';"PERMANENCIA"\r\n'), 'linhas de dados indicam a definição (código) que as explica');
});

test('evolução: variação absoluta e percentual; base anterior zero não divide', () => {
  assert.deepEqual(rel.variacao(0, 5), { abs: 5, pct: null });
  assert.deepEqual(rel.variacao(4, 5), { abs: 1, pct: 25 });
  assert.deepEqual(rel.variacao(null, 5), { abs: null, pct: null });
  const r = resultado('EVOLUCAO', [
    L('ENTRADAS', null, { periodo: 'ANTERIOR', quantidade: 3 }), L('ENTRADAS', null, { periodo: 'ATUAL', quantidade: 3 }),
    L('PERMANENCIA', null, { periodo: 'ANTERIOR', quantidade: 0, mediana: null }),
    L('PERMANENCIA', null, { periodo: 'ATUAL', quantidade: 3, mediana: 660 }),
    L('BLOQUEIO_MINUTOS_CATEGORIA', 'REGULACAO', { periodo: 'ATUAL', minutos: 1320 })],
  { comparacao: { inicio: '2026-09-24', fim: '2026-09-30' } });
  const c = rel.comparacao(r);
  const entradas = c.find((x) => x.titulo === 'Entradas');
  assert.deepEqual([entradas.anterior, entradas.atual, entradas.abs, entradas.pct], [3, 3, 0, 0]);
  const perm = c.find((x) => x.titulo.startsWith('Permanência mediana'));
  assert.equal(perm.anterior, null, 'sem encerrados: ausente, não zero');
  assert.equal(rel.textoComparacao(perm).abs, 'sem dados');
  const reg = c.find((x) => x.titulo.includes('Regulação'));
  assert.deepEqual([reg.anterior, reg.atual], [0, 1320], 'categoria só no período atual: anterior = 0 minuto');
  assert.ok(rel.gerarCsv(r).includes('"Comparação";"Período anterior"'));
});

// ------------------------------------------------------------------ tela
function contexto(resposta, registros) {
  return {
    catalogo: () => ({ setores: [{ id: 's1', nome: 'Obs' }], etapas: [{ id: 'e1', nome: 'Atendimento', natureza: 'ATENDIMENTO' }] }),
    fuso: () => 'America/Fortaleza',
    agora: () => Date.parse('2026-10-07T15:00:00Z'),
    api: {
      obter: async (url) => (url.startsWith('/api/relatorios/dicionario') ? { versao: 'relatorios-v1', definicoes: [] } : resposta),
      criar: async (url, corpo) => { registros.push({ url, corpo }); if (registros.falhar) throw new Error('recusado'); return {}; },
    },
    sincronizar() {}, tratarErroGlobal: () => false, anunciar() {},
  };
}

async function calcular(raiz, tipoValor) {
  const form = raiz.todos((n) => n.tagName === 'form')[0];
  const tipo = form.todos((n) => n.tagName === 'select')[0];
  tipo.value = tipoValor;
  form.ouvintes.submit[0]({ preventDefault() {} });
  await esperar(); await esperar(); await esperar();
}

const textoDe = (raiz) => raiz.textContent;

test('tela: cabeçalho completo e seções com as limitações ao lado', async () => {
  const r = resultado('RESUMO', [L('ENTRADAS', null, { quantidade: 3, parte: 1 }), L('ABERTOS', null, { quantidade: 2 }),
    L('ABERTOS_SETOR', 's1', { rotulo: 'Obs', quantidade: 2, base: 2 })], {
    limitacoes: [{ codigo: 'SEM_REGRAS', secao: 'CASOS_EM_ALERTA', texto: 'Nenhuma regra ativa' },
      { codigo: 'MOTIVO_NAO_E_CAUSA', secao: null, texto: 'Motivo não é causa comprovada.' }] });
  const raiz = new No('main');
  montar(raiz, contexto(r, []));
  await calcular(raiz, 'RESUMO');
  const t = textoDe(raiz);
  for (const esperado of ['UPA Teste', 'America/Fortaleza', 'Instante de referência', 'Gerado em', 'relatorios-v1', 'ab'.repeat(32),
    'nenhum (toda a unidade)', 'Motivo não é causa comprovada.', 'Nenhuma regra ativa', 'pela impressão do navegador']) {
    assert.ok(t.includes(esperado), `exibe: ${esperado}`);
  }
  const secao = raiz.todos((n) => n.getAttribute('data-secao') === 'CASOS_EM_ALERTA')[0];
  assert.match(secao.textContent, /Limitação: Nenhuma regra ativa/, 'limitação junto da seção a que se refere');
  assert.match(secao.textContent, /Sem dados para este recorte/, 'sem dados ≠ zero');
  const setorLinha = raiz.todos((n) => n.getAttribute('data-secao') === 'ABERTOS_SETOR')[0].textContent;
  assert.match(setorLinha, /Obs/);
  assert.match(setorLinha, /100,0%/);
});

test('exportação: registra no servidor ANTES de gerar; CSV = resultado exibido; recusa não gera arquivo', async () => {
  const r = resultado('RESUMO', [L('ENTRADAS', null, { quantidade: 7, parte: 0 })]);
  const registros = [];
  const raiz = new No('main');
  const baixados = [];
  globalThis.URL.createObjectURL = (blob) => { baixados.push(blob); return 'blob:teste'; };
  globalThis.URL.revokeObjectURL = () => {};
  globalThis.document.body = new No('body');
  globalThis.Blob = class { constructor(partes) { this.texto = partes.join(''); } };
  let impressoes = 0;
  globalThis.window = { print: () => { impressoes += 1; } };
  montar(raiz, contexto(r, registros));
  await calcular(raiz, 'RESUMO');
  const botao = (rotulo) => raiz.todos((n) => n.tagName === 'button' && n.textContent.startsWith(rotulo))[0];

  botao('Baixar CSV').ouvintes.click[0]();
  await esperar(); await esperar();
  assert.deepEqual(registros, [{ url: '/api/relatorios/exportacoes', corpo: { comprovante: 'carga.mac', formato: 'CSV' } }]);
  assert.equal(baixados.length, 1);
  assert.equal(baixados[0].texto, rel.gerarCsv(r), 'o arquivo é o CSV do resultado exibido (nenhum recálculo)');
  assert.ok(baixados[0].texto.includes(';7;0;'));

  botao('Imprimir').ouvintes.click[0]();
  await esperar(); await esperar();
  assert.equal(registros[1].corpo.formato, 'IMPRESSAO');
  assert.equal(impressoes, 1);

  registros.falhar = true;
  botao('Baixar CSV').ouvintes.click[0]();
  await esperar(); await esperar();
  assert.equal(baixados.length, 1, 'registro recusado: nenhum arquivo');
  assert.match(textoDe(raiz), /recusado|erro/i);
});

// ------------------------------------------------------------------ revisão do PR #11 (pontos 3 e 4)
test('versão imprimível da Evolução traz definições e limitações mesmo se o dicionário separado falhar', async () => {
  const r = evolucao();
  const c = contexto(r, []);
  c.api.obter = async (url) => { if (url.startsWith('/api/relatorios/dicionario')) throw new Error('dicionário indisponível'); return r; };
  const raiz = new No('main');
  montar(raiz, c);
  await calcular(raiz, 'EVOLUCAO');
  const artigo = raiz.todos((n) => n.getAttribute('aria-label') === 'Relatório')[0];
  assert.ok(artigo, 'relatório desenhado');
  const t = artigo.textContent;   // o artigo é o que se imprime (filtros e dicionário ficam fora da impressão)
  for (const esperado of ['Desfecho "encerramento administrativo"', '"No prazo" usa o ÚLTIMO prazo registrado.',
    'Permanência exclui encerramento administrativo', '"Até o prazo" usa o ÚLTIMO prazo', 'pontos percentuais (p.p.)',
    'não se aplica', 'Definições das métricas comparadas', 'mesma duração: 168 h', 'População', 'Denominador']) {
    assert.ok(t.includes(esperado), `impressão contém: ${esperado}`);
  }
  const defs = artigo.todos((n) => n.getAttribute('data-definicao'));
  assert.deepEqual(defs.map((d) => d.getAttribute('data-definicao')), ['EVOLUCAO', 'PERMANENCIA', 'ENCERRADAS']);
  const ocultos = artigo.todos((n) => String(n.getAttribute('class') || '').includes('nao-imprimir'));
  assert.ok(!ocultos.some((n) => n.textContent.includes('ÚLTIMO prazo registrado')), 'definições fora de bloco oculto na impressão');
  assert.match(textoDe(raiz), /dicionário indisponível|erro/i, 'falha do dicionário aparece só no material complementar');
});

test('seções das demais telas usam a definição do resultado, não o dicionário', async () => {
  const r = resultado('QUALIDADE', [L('REGISTROS_RETROATIVOS', null, { quantidade: 2, parte: 1, base: 2 })], {
    verbetes: { REGISTROS_RETROATIVOS: 'REGISTROS_RETROATIVOS' },
    definicoes: [DEF('REGISTROS_RETROATIVOS', { populacao: 'setor em vigor no INSTANTE DO FATO' })] });
  const c = contexto(r, []);
  c.api.obter = async (url) => (url.startsWith('/api/relatorios/dicionario') ? { versao: 'outra', definicoes: [DEF('REGISTROS_RETROATIVOS',
    { populacao: 'TEXTO DO DICIONÁRIO SEPARADO' })] } : r);
  const raiz = new No('main');
  montar(raiz, c);
  await calcular(raiz, 'QUALIDADE');
  const secao = raiz.todos((n) => n.getAttribute('data-secao') === 'REGISTROS_RETROATIVOS')[0];
  assert.match(secao.textContent, /setor em vigor no INSTANTE DO FATO/);
  assert.doesNotMatch(secao.textContent, /DICIONÁRIO SEPARADO/);
  assert.equal(raiz.todos((n) => n.getAttribute('data-secao') === 'SETOR_NAO_ATRIBUIDO').length, 0,
    'sem filtro de setor, a seção de fatos sem setor não aparece');
});

test('Evolução: formulário só aceita período encerrado (fim até ontem) e explica; os outros relatórios incluem hoje', () => {
  const raiz = new No('main');
  montar(raiz, contexto(resultado('RESUMO', []), []));   // "agora" = 07/10/2026 12:00 em Fortaleza
  const form = raiz.todos((n) => n.tagName === 'form')[0];
  const [tipo] = form.todos((n) => n.tagName === 'select');
  const [inicio, fim] = form.todos((n) => n.tagName === 'input' && n.getAttribute('type') === 'date');
  const aviso = raiz.todos((n) => n.getAttribute('id') === 'aviso-evolucao')[0];
  assert.equal(fim.value, '2026-10-07');
  assert.equal(aviso.hidden, true);
  tipo.value = 'EVOLUCAO';
  tipo.ouvintes.change[0]();
  assert.equal(fim.value, '2026-10-06', 'fim ajustado para ontem');
  assert.equal(fim.max, '2026-10-06');
  assert.ok(inicio.value <= fim.value);
  assert.equal(aviso.hidden, false);
  assert.match(aviso.textContent, /períodos ENCERRADOS.*06\/10\/2026.*America\/Fortaleza/s);
  tipo.value = 'RESUMO';
  tipo.ouvintes.change[0]();
  assert.equal(fim.max, '2026-10-07', 'demais relatórios podem terminar hoje');
  assert.equal(aviso.hidden, true);
});
