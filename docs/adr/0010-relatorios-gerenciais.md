# ADR-0010 — Relatórios gerenciais

- **Status:** proposto (PR #11 da etapa 8), implementado em `V19`, `V20` e no módulo `relatorio`; revisado no PR #11 (seção 8)
- **Data:** 2026-10-07
- **Origem:** **extensão aprovada do projeto** (issue #9). Não é requisito da ERS v1.1 original.
  Reaproveita definições da etapa 7 (RF-019, RF-020, RF-039 — [ADR-0009](0009-plantao-e-indicadores.md)).
  A exportação CSV daqui **não** implementa o RF-025, que continua a definir com a instituição.
- **Pendências institucionais:** V-08 (reidentificação em grupos pequenos, acesso nominal),
  V-09 (fórmulas), V-10 (retenção e limites técnicos)

## Contexto

A coordenação e a direção precisam de cinco visões sobre a operação registrada:

1. resumo gerencial;
2. gargalos por etapa, setor e categoria;
3. acompanhamento de pendências;
4. evolução entre períodos comparáveis;
5. qualidade e atualidade dos registros.

Cada relatório precisa de tela com filtros, versão para impressão e CSV, e os três têm de mostrar
**o mesmo conjunto de dados**. Restrições da issue:

- Fórmulas documentadas e marcadas como propostas (V-09).
- Tempos históricos **não** podem ser refeitos a partir do estado atual. Esperas **não** podem ser
  atribuídas ao setor atual.
- O relatório de qualidade não trata campo opcional como falha e não inventa prazo oficial de
  desatualização.
- A Direção vê só agregados: sem nomes, CNS, prontuário, ids de episódio ou links.
- As exportações são auditadas.
- Nenhum relatório parcial é apresentado como completo.
- Sem novos frameworks ou infraestrutura.

## Decisão

### 1. Cálculo no banco, sobre a linha do tempo

O cálculo fica nas funções `fluxo.rel_*` (V19), todas `SECURITY INVOKER`. Elas rodam com o papel da
aplicação e, portanto, sob a **RLS por unidade** ([ADR-0004](0004-isolamento-por-unidade.md)).

- **`rel_intervalos`** reconstrói, para os episódios da unidade que tocam o período, os intervalos de:
  - **etapa:** `EPISODIO_ABERTO`, `ETAPA_ALTERADA`;
  - **setor:** `EPISODIO_ABERTO`, `SETOR_ALTERADO`;
  - **bloqueio:** `BLOQUEIO_DEFINIDO`, `BLOQUEIO_REMOVIDO`, com a categoria registrada **na época**.

  Os eventos são ordenados por `(ocorrido_em, registrado_em, id)`. A redefinição do mesmo motivo
  continua o mesmo intervalo. O intervalo em curso tem `fim` nulo e é fechado no instante de
  referência só no cálculo.
- **Filtros de setor/etapa:** usam a interseção exata de `tstzrange`. O tempo bloqueado conta só o
  trecho vivido no setor/etapa filtrados. Etapas e bloqueios concluídos contam pelo setor em que
  **começaram**.
- **Estoque** (abertos, pendências abertas, idades): usa o setor/etapa **atuais**, no instante de
  referência.
- **Separação:** durações **concluídas** e idades **em curso** ficam em seções separadas.
- **Episódios sem linha do tempo:** ficam fora dos tempos reconstruídos e aparecem no relatório de
  Qualidade (`LINHA_DO_TEMPO`).
- **Formato das linhas:** todas as funções devolvem o tipo genérico `fluxo.linha_relatorio`.
  Percentis são contínuos (`percentile_cont`).

**Desempenho.** Teste local com 20 mil episódios (`desempenho-relatorios.sh`):

| Cálculo | Tempo |
|---|---|
| Gargalos, 366 dias | ≈ 0,4 s |
| Gargalos com setor e etapa | ≈ 2,1 s |

O que permitiu esses tempos:

- `rel_intervalos` declara `ROWS 100000`;
- os CTEs foram reorganizados em ramos `UNION ALL`, para evitar *nested loops* em planos genéricos;
- `rel_valor_em` só é chamado quando há filtro.

### 2. Um instantâneo por relatório

`ServicoRelatorios.consultar` faz o cálculo inteiro numa transação **REPEATABLE READ READ ONLY**
(`ExecutorTransacional.executarLeituraConsistente`, a mesma da etapa 7). O cálculo inclui:

- linhas;
- alertas atuais (mesmo `MotorDeAlertas` da Torre, regras ativas);
- regras;
- nomes do cadastro;
- lista nominal.

Uma gravação confirmada durante o cálculo entra só no próximo relatório. Nenhuma métrica mistura
estados diferentes do banco. O isolamento global não muda.

### 3. Mesmo conjunto na tela, na impressão e no CSV

- O resultado leva a **assinatura** SHA-256 da forma canônica de tudo o que é exibido: cabeçalho,
  filtros, comparação, linhas, lista e limitações. A assinatura aparece no cabeçalho da tela, da
  impressão e do CSV.
- O resultado leva também um **comprovante** HMAC-SHA256 (`TokenRelatorio`) com: assinatura, tipo,
  unidade, usuário, período, filtros, referência, emissão, "nominal" e número de linhas.
- A interface gera a impressão e o CSV **do objeto exibido**, sem nova consulta. Por isso, uma
  atualização concorrente não pode fazer o arquivo divergir da tela. Um novo cálculo produz nova
  assinatura (`RelatoriosConsistenciaIT`).
- `POST /api/relatorios/exportacoes` confere o comprovante:
  - mesmo usuário e mesma unidade ativa;
  - emitido há até 2 h (`RELATORIO_EXPIRADO`, 409);
  - permissão **atual** (`INDICADORES_VER`; `EPISODIO_VER` se houver lista nominal).

  Só então registra a auditoria. A interface libera o CSV ou abre `window.print()` **depois** do
  registro. Se a tela mudou nesse meio-tempo (outro cálculo ou outra unidade), nada é liberado.
- O PDF é feito pela impressão do navegador (A4 paisagem, cabeçalho de tabela repetido, linhas
  inteiras). Não há PDF no servidor, e o sistema não comprova que a impressão foi concluída.

### 4. Auditoria (READ COMMITTED, fora do instantâneo)

O registro nunca acontece dentro da transação somente leitura. Cada registro usa uma transação
própria (`executor.executar`):

- **Exportação:** `auditoria.registrar('RELATORIO_EXPORTADO' | 'RELATORIO_IMPRESSAO_SOLICITADA', 'relatorio', <tipo>, dados)`.
  - `dados` contém: formato, assinatura, período, referência, momento do cálculo, ids dos filtros,
    número de linhas e se havia lista nominal.
  - Ator, unidade e origem vêm do contexto.
  - **Sem conteúdo nominal.**
- **Leitura da lista nominal:** `auditoria.registrar_consulta('CONSULTA_RELATORIO_PENDENCIAS', …)`
  (V17), com o conjunto de episódios exibidos (só ids). O registro acontece **antes** de a resposta
  sair.

### 5. Autorização e dados nominais

- Todos os relatórios exigem `INDICADORES_VER` (coordenação e direção), conferida no servidor a cada
  chamada, com a sessão revalidada no banco.
- A lista operacional de pendências (nome do paciente, ação registrada, responsável, prazo) exige
  também `EPISODIO_VER`.
  - A **Direção nunca a recebe**: a resposta traz só o motivo da indisponibilidade.
  - Nenhum outro relatório tem nome, CNS, prontuário, id de episódio ou link.
  - Agregados por responsável trazem só o **tipo** (usuário/setor/perfil).
- Limite técnico de 2000 pendências. Acima dele, a lista **não** é exibida, e o motivo é informado:
  nunca uma lista parcial.
- Alertas acima de 10 000 episódios abertos aparecem como "indisponível", não como um valor parcial.
- No navegador, nada fica em `localStorage`, e respostas tardias de outro contexto (unidade ou
  sessão) são descartadas, como nas demais telas ([ADR-0008](0008-interface-web.md)).

### 6. CSV seguro

- UTF-8 com BOM, separador `;`, CRLF, decimal com vírgula.
- Todo texto vai entre aspas.
- Texto que começa (depois de espaços) por `=`, `+`, `-`, `@`, suas variantes de largura total,
  tabulação ou CR recebe um apóstrofo inicial.
- Números saem sem aspas.

O mesmo vale para dados digitados pela equipe, como a descrição da pendência e o nome do paciente.

### 7. Limitações declaradas, não escondidas

Cada resposta traz `limitacoes` (código, seção, texto), exibidas junto da seção na tela e na
impressão, e listadas no CSV. Entre elas:

- motivo não é causa;
- sem ranking;
- fórmulas propostas;
- grupos pequenos;
- atribuição temporal;
- concluído × em curso;
- responsável atual;
- prazo último;
- alertas só atuais;
- histórico de regras;
- campo opcional;
- critério de atualidade não configurado.

### 8. Revisão do PR #11 (V20 e `relatorios-v2`)

**8.1 Qualidade pelo setor da época.** Na V19, `rel_qualidade` filtrava registros e bloqueios iniciados
pelo setor **atual/final** do episódio: um fato ocorrido em S1 passava para S2 depois de uma
transferência. A V20 (nova migração; a V19 não é reescrita) substitui a função:

- **Registros retroativos:** o período é o do **registro** (`registrado_em`); com filtro de setor, cada
  registro vai para o setor em vigor no instante do **fato** (`ocorrido_em`), pela ordem da linha do tempo
  `(ocorrido_em, registrado_em, id)`. A transferência pertence ao setor de destino.
- **Bloqueios iniciados:** com filtro de setor, vale o setor em vigor no início do intervalo (uma
  transferência no mesmo instante prevalece, como nos pedaços de Gargalos).
- **Sem setor determinável** (fato anterior ao primeiro evento de setor, ex.: registro legado sem evento
  de abertura): o fato fica **fora** do filtro e é contado em `SETOR_NAO_ATRIBUIDO`, com limitação. Nunca
  é atribuído ao setor atual.
- **Estoque** (atualidade, causa em investigação, cobertura de campos) continua pelo setor atual. A
  cobertura `LINHA_DO_TEMPO` tem escopo e filtro próprios, documentados (setor atual/final, porque
  justamente falta a linha do tempo).
- **Desempenho:** a atribuição é feita por funções de janela (último evento de setor até o fato), sem
  junção por faixa. Uma junção por faixa sobre o CTE caía em *nested loop* (6,4 s em 366 dias / 20 mil
  episódios); com a janela, ≈ 0,8 s.

**8.2 Bloqueios pela definição normalizada.** Mudar só o detalhe emite `BLOQUEIO_DEFINIDO` com o mesmo
motivo e o início original. Qualidade passou a contar os **inícios dos intervalos** de `rel_intervalos`,
a mesma definição de Gargalos e Evolução (`t17` confere as três concordando). Na mesma função, a
categoria do intervalo passou a ser a registrada **no início** (antes, a menor entre as redefinições).

**8.3 Evolução só com períodos encerrados.** Comparar um período que termina hoje (incompleto) com um
anterior completo sugere melhora ou piora artificial.

- O servidor recusa fim depois de ontem no fuso da unidade (422 `PERIODO_INCOMPLETO`), pelo relógio do
  servidor. A tela ajusta as datas e explica a restrição.
- Os demais relatórios podem incluir hoje.
- A comparação continua por igual número de dias locais. A duração real dos dois períodos vai no
  resultado (`horasAnterior`, `horasAtual`), e a diferença por horário de verão aparece na limitação
  `PERIODOS_EQUIVALENTES`.
- Alternativa descartada nesta entrega: recortes parciais equivalentes (ex.: até a mesma hora do dia),
  mais complexos de explicar e validar.

**8.4 Definições dentro do resultado.** O CSV não trazia as fórmulas, e a impressão da Evolução perdia
as exclusões, porque o dicionário era uma consulta separada e não impressa.

- O resultado passou a levar `definicoes`: um verbete por definição usada, para **todas** as seções do
  relatório. Cada verbete traz versão, fórmula, unidade, população, exclusões, denominador, marcos,
  ausentes, repetições e situação.
- O resultado leva também `verbetes` (seção → definição).
- Definições e verbetes entram na **forma canônica assinada**.
- Tela, impressão e CSV usam essas definições, nunca o dicionário separado, que fica como material
  complementar. A exportação continua sem recálculo.
- Evolução:
  - verbetes próprios: `PERMANENCIA`, com a exclusão do encerramento administrativo; `ENCERRADAS`, com
    o último prazo;
  - limitações `PERMANENCIA_SEM_ADMINISTRATIVO`, `PRAZO_ULTIMO` e `UNIDADES_DA_VARIACAO`;
  - unidades explícitas: contagem, minutos ou pontos percentuais; a variação relativa não se aplica a
    métricas em %.
- Textos novos no CSV também passam pela proteção contra fórmulas.

## Alternativas consideradas

- **Calcular a partir da página da Torre ou do estado atual:** rejeitado. Não cobre todos os
  registros, atribui toda a espera ao setor atual e mistura estados.
- **Recalcular ao exportar:** rejeitado. O arquivo poderia divergir da tela se houvesse gravação no
  meio. O comprovante garante que se exporta o que foi visto.
- **Guardar o resultado no servidor** (cache ou tabela) para exportar depois: rejeitado nesta etapa.
  Exigiria armazenar dado nominal derivado e definir uma retenção. A assinatura + HMAC resolve a
  consistência sem guardar nada.
- **PDF ou XLSX no servidor:** fora do escopo, porque exigiria nova dependência. A impressão do
  navegador atende à leitura, e o CSV atende à análise.
- **Suprimir contagens pequenas para a Direção:** o limiar é decisão institucional (V-08). Nenhum
  valor foi inventado; o risco é declarado em todo relatório.

## Consequências

- **Chave HMAC por processo:** é aleatória e gerada a cada início da JVM (`RelatoriosConfig`).
  - Depois de um reinício, comprovantes antigos são recusados (`COMPROVANTE_INVALIDO`) e basta
    recalcular.
  - Com **várias instâncias** atrás de balanceador sem afinidade, a exportação falharia. Antes de
    escalar horizontalmente, é preciso uma chave compartilhada (segredo de implantação).
- **Alertas** não têm histórico. A Evolução não os compara e informa as alterações de regras
  registradas (V18).
- **Responsável** é o atual, e **"no prazo"** usa o último prazo: o modelo não guarda o histórico
  desses campos.
- **Fórmulas:** qualquer mudança exige nova versão do sistema (versão no cabeçalho: `relatorios-v2` desde a revisão do PR #11).
- A auditoria cresce com cada exportação e cada leitura nominal. A retenção é V-10.
