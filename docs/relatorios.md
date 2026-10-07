# Dicionário dos relatórios gerenciais (extensão aprovada — issue #9)

> **Escopo.** Os relatórios gerenciais são uma **extensão aprovada do projeto** (issue #9). Eles **não**
> são requisitos da ERS v1.1 original: reaproveitam os registros e as definições da etapa 7 (RF-019,
> RF-020, RF-039) e acrescentam recortes. A exportação CSV daqui **não** implementa o RF-025
> (exportação), que continua a definir com a instituição.

Fonte de verdade: `DicionarioRelatorios` (versão **`relatorios-v2`**). As definições usadas por um relatório vão
**dentro do próprio resultado** (`definicoes`, cobertas pela assinatura) e aparecem junto de cada seção na tela,
na impressão e no CSV; `GET /api/relatorios/dicionario` e o dicionário da tela são material complementar. A
seção "Verbetes" abaixo é **gerada** da classe. Cálculo: funções `fluxo.rel_*` das migrações V19 e V20
([ADR-0010](adr/0010-relatorios-gerenciais.md)); valores conferidos à mão em `backend/src/test/sql/t16_relatorios.sql`,
`t17_relatorios_qualidade_historica.sql` e `RelatoriosIT`.

**v2 (revisão do PR #11):** Qualidade atribui registros e inícios de bloqueio ao setor **da época** e conta
bloqueios pelos intervalos normalizados (V20); categoria de um bloqueio = a do início; Evolução só com
períodos **encerrados**; definições e limitações levadas no próprio resultado.

> **Todas as fórmulas são PROPOSTAS** até a validação institucional (V-09). Os relatórios são
> **operacionais**: mostram o que a equipe registrou, não trazem conclusão causal, recomendação
> clínica, metas nem ranking individual. Motivo registrado não é causa comprovada.

## Relatórios e seções

| Relatório | Seções (código no CSV) | Filtros |
|---|---|---|
| Resumo gerencial | `ENTRADAS`, `ENCERRAMENTOS`, `ENCERRAMENTOS_TOTAL`, `ABERTOS`, `ABERTOS_ETAPA`, `ABERTOS_SETOR`, `BLOQUEADOS_AGORA`, `PENDENCIAS_ABERTAS`, `CASOS_COM_VENCIDA`, `CASOS_EM_ALERTA`, `ALERTA_REGRA` | período, setor |
| Gargalos | `ETAPA_CONCLUIDA`, `ETAPA_EM_CURSO`, `SETOR_TEMPO`, `BLOQUEIO_CATEGORIA`, `BLOQUEIO_MOTIVO`, `BLOQUEIO_CONCLUIDO`, `BLOQUEIO_EM_CURSO`, `PENDENCIA_CATEGORIA`, `PENDENCIA_RESPONSAVEL` | período, setor, etapa, categoria |
| Pendências | `CRIADAS`, `CRIADAS_TOTAL`, `ENCERRADAS`, `ENCERRADAS_CATEGORIA`, `ABERTAS`, `ABERTAS_TOTAL`, `ABERTAS_CRITICIDADE`, `ABERTAS_RESPONSAVEL`, `VENCIDAS_ATRASO` + lista operacional nominal (só com acesso nominal) | período, setor, categoria |
| Evolução | `ENTRADAS`, `ENCERRAMENTOS`, `PERMANENCIA`, `PENDENCIAS_CRIADAS`, `PENDENCIAS_ENCERRADAS`, `BLOQUEIO_MINUTOS`, `BLOQUEIO_MINUTOS_CATEGORIA` — cada uma com `periodo` = `ATUAL` ou `ANTERIOR` | período **encerrado** (fim até ontem), setor |
| Qualidade | `ATUALIDADE`, `ATUALIDADE_SETOR`, `SEM_ATUALIZACAO_REGRA`, `REGISTROS_RETROATIVOS`, `CAUSA_EM_INVESTIGACAO_AGORA`, `BLOQUEIOS_INICIADOS_SEM_CAUSA`, `SETOR_NAO_ATRIBUIDO` (só com filtro de setor), `DESTINO_EM_TRANSFERENCIA`, `PROTOCOLO_EM_TRANSFERENCIA`, `LINHA_DO_TEMPO` | período, setor |

Colunas de cada linha (iguais na API, na tela e no CSV): `secao`, `chave` (código do grupo: etapa,
setor, categoria, desfecho…), `rotulo`, `grupo`, `quantidade`, `parte` (subconjunto de `quantidade`
definido no verbete), `base` (denominador), `episodios` (episódios distintos), `minutos`, `media`,
`mediana`, `p90`, `maximo`. O percentual exibido é sempre `parte/quantidade` ou `quantidade/base`,
e fica **ausente** quando o denominador é zero.

## Regras comuns

- **Período:** datas locais da unidade `[início, fim]` → intervalo semiaberto `[início 00:00, (fim+1) 00:00)`
  no fuso da unidade; até 366 dias; sem datas futuras. Instante exatamente no início entra; no fim, não.
- **Estoque** (abertos, idades, pendências abertas, alertas) usa o **instante de referência**: o relógio
  do servidor no início do cálculo, exibido no cabeçalho.
- **Linha do tempo:** tempos por etapa, setor e bloqueio são reconstruídos dos eventos (`EPISODIO_ABERTO`,
  `ETAPA_ALTERADA`, `SETOR_ALTERADO`, `BLOQUEIO_DEFINIDO`/`BLOQUEIO_REMOVIDO`) pelo instante do fato.
  A espera é atribuída ao setor/etapa **em que ocorreu**, nunca ao setor atual. Nada é refeito a partir
  do estado atual do episódio.
- **Concluídos × em curso:** durações de intervalos concluídos nunca são misturadas com a idade de
  esperas em curso.
- **Repetições:** cada passagem pela mesma etapa é um intervalo; redefinir o mesmo motivo de bloqueio
  (ex.: só o detalhe mudou) continua o mesmo intervalo, com o início e a **categoria do início**; remoção
  seguida de novo bloqueio são dois intervalos. Qualidade, Gargalos e Evolução contam inícios de bloqueio
  pela mesma definição.
- **Fatos históricos × estoque (filtro de setor):** um fato do período (registro, início de bloqueio,
  criação/encerramento de pendência, entrada) vai para o setor em vigor **no instante do fato**, pela linha
  do tempo; o estoque (abertos, atualidade, pendências abertas) usa o setor **atual**; encerramentos, o
  setor **final**. Uma transferência posterior não move o passado. Fato sem setor determinável fica **fora**
  do filtro e é sinalizado (`SETOR_NAO_ATRIBUIDO`) — nunca é atribuído ao setor atual.
- **Registros retroativos:** dois marcos distintos — o **período** é o do registro (`registrado_em`); o
  **setor** é o do fato (`ocorrido_em`).
- **Cobertura `LINHA_DO_TEMPO`:** escopo próprio (abertos no instante de referência, entrados ou encerrados no
  período) e filtro pelo setor atual/final, porque sem linha do tempo não há setor da época.
- **Percentis:** mediana e P90 contínuos (`percentile_cont`), em minutos.
- **Ausência de dados:** "sem dados" nunca é exibido como zero; ausência de registro não é ausência de problema.
- **Campos opcionais** (destino, protocolo fora das etapas que os exigem) são **cobertura**, não falha.
- **Atualidade:** nenhum prazo oficial de "registro desatualizado" é presumido; a contagem só existe
  para regras "sem atualização" configuradas pela unidade.
- **Alertas:** só no instante de referência, pelo mesmo motor da Torre e as regras ativas; não há
  histórico de alertas e a Evolução não os compara.
- **Evolução:** só períodos **encerrados** — o fim vai até ontem no fuso da unidade (servidor recusa com
  422 `PERIODO_INCOMPLETO`; a tela já ajusta as datas e explica). Um período que inclui hoje está incompleto
  e não é comparável com o anterior completo. O período anterior tem o mesmo número de dias locais; a
  duração real de cada um (`horasAnterior`, `horasAtual`) é informada e, com horário de verão, a diferença
  aparece na limitação `PERIODOS_EQUIVALENTES`. Unidades: variação **absoluta** na unidade da métrica
  (contagem, minutos, ou **pontos percentuais** para métricas em %); variação **relativa** em % do valor
  anterior (ausente com anterior zero; não se aplica a métricas em %). Os demais relatórios podem incluir
  hoje.

## Limitações exibidas com o resultado

Cada resposta traz `limitacoes` (`codigo`, `secao`, `texto`): com `secao`, aparecem junto da seção
na tela e na impressão; sem `secao`, no bloco "Limitações e cobertura". Todas vão no CSV.

| Código | Quando |
|---|---|
| `MOTIVO_NAO_E_CAUSA`, `SEM_RANKING`, `FORMULAS_PROPOSTAS`, `GRUPOS_PEQUENOS` | sempre |
| `FILTRO_DE_SETOR` | com filtro de setor |
| `ENTRADAS_SEM_SETOR` | entradas sem setor de entrada, com filtro de setor |
| `SEM_REGRAS` · `ALERTAS_INDISPONIVEIS` · `ALERTAS_ATUAIS` | casos em alerta: sem regra ativa · acima do limite técnico · caso normal |
| `CONCLUIDO_X_EM_CURSO`, `ATRIBUICAO_TEMPORAL`, `SEM_LINHA_DO_TEMPO`, `RESPONSAVEL_ATUAL` | gargalos |
| `PRAZO_ULTIMO`, `RESPONSAVEL_ATUAL`, `ACAO_REGISTRADA` | pendências |
| `ALERTAS_NAO_COMPARADOS`, `REGRAS_ALTERADAS`, `HISTORICO_DE_REGRAS`, `PERIODOS_ENCERRADOS`, `PERIODOS_EQUIVALENTES`, `PERMANENCIA_SEM_ADMINISTRATIVO`, `PRAZO_ULTIMO`, `UNIDADES_DA_VARIACAO` | evolução |
| `OPCIONAL`, `FALTA_DE_INFORMACAO`, `SEM_CRITERIO_CONFIGURADO`, `REGISTRO_X_FATO`, `ESCOPO_LINHA_DO_TEMPO`, `SETOR_NAO_ATRIBUIDO` (fatos sem setor da época, com filtro) | qualidade |

**Risco de reidentificação (`GRUPOS_PEQUENOS`).** Contagens pequenas — sobretudo com setor, etapa e
categoria combinados — podem permitir identificar um paciente mesmo sem nome. A política de supressão
é institucional (V-08) e **não foi inventada**: nenhum limiar é aplicado; o risco é declarado em todo
relatório e registrado em `decisoes-a-validar.md`.

## Tela, impressão e CSV — mesmo conjunto

- O servidor calcula **uma vez**, num único instantâneo do banco, e devolve o resultado com a
  **assinatura** SHA-256 do conjunto canônico (cabeçalho + linhas + lista + limitações + verbetes e
  definições usados) e um
  **comprovante** (HMAC).
- Tela, impressão e CSV são gerados no navegador a partir **desse** resultado; nada é recalculado.
  A assinatura aparece no cabeçalho dos três.
- Antes de liberar o CSV ou abrir a impressão, a interface chama `POST /api/relatorios/exportacoes`,
  que confere o comprovante (mesmo usuário e unidade, até 2 h, permissão atual) e registra na auditoria
  `RELATORIO_EXPORTADO` ou `RELATORIO_IMPRESSAO_SOLICITADA` — sem conteúdo nominal. O sistema **não**
  comprova que a impressão foi concluída.
- **Definições junto do relatório:** cada seção mostra a definição do **próprio resultado** (fórmula, unidade,
  população, exclusões, denominador); a Evolução imprime as definições completas das métricas comparadas.
  Se a consulta ao dicionário geral falhar, o relatório, a impressão e o CSV não perdem nada.
- **PDF:** só pela impressão do navegador ("Salvar como PDF"), em A4 paisagem; não há PDF gerado no servidor.

### Formato do CSV

- UTF-8 com BOM, separador `;`, fim de linha CRLF, decimal com vírgula, nome
  `relatorio-<tipo>-<unidade>-<inicio>_<fim>-<8 primeiros caracteres da assinatura>.csv`.
- Blocos: metadados (unidade, período, comparação com durações, fuso, filtros, referência, geração, versão,
  assinatura, cobertura), linhas de dados (formato longo, uma por linha do resultado, com a coluna
  "Definição (código)"), comparação (Evolução: valores, variação absoluta e relativa, unidade do valor e
  da variação), lista nominal (só se exibida), limitações e **definições** (versão, código, nome, fórmula,
  unidade, população, exclusões, denominador, marcos, fonte, abertos, ausentes, repetições, período, situação).
- **Proteção contra injeção de fórmulas:** todo texto vai entre aspas (aspas internas duplicadas);
  texto que começa — depois de espaços — por `=`, `+`, `-`, `@` (inclusive as variantes de largura
  total), tabulação ou retorno de carro recebe um apóstrofo inicial. Números saem sem aspas e sem
  apóstrofo (inclusive negativos). Vale também para os textos das definições e limitações.
- Nada é guardado no navegador (sem `localStorage`).

## Verbetes

### Entradas no período (`ENTRADAS`)

| Campo | Definição |
|---|---|
| Fórmula | quantidade de episódios com entrada_em no período |
| Unidade de medida | episódios |
| População | Episódios da unidade; com filtro de setor, o setor de ENTRADA (evento de abertura). |
| Exclusões | Nenhuma. 'parte' = entradas sem setor de entrada registrado na linha do tempo (não entram no filtro de setor). |
| Denominador | — |
| Marco inicial | entrada_em |
| Marco final | — |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. |
| Abertos e encerrados | Abertos e encerrados contam. |
| Dados ausentes | Período sem entradas: 0 (contagem). |
| Repetições | Um episódio conta uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Encerramentos por desfecho (`ENCERRAMENTOS`)

| Campo | Definição |
|---|---|
| Fórmula | contagem de episódios com encerrado_em no período, por desfecho |
| Unidade de medida | episódios |
| População | Episódios encerrados no período; com filtro de setor, o setor FINAL. |
| Exclusões | Nenhuma. |
| Denominador | — |
| Marco inicial | — |
| Marco final | encerrado_em |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. |
| Abertos e encerrados | Só encerrados. |
| Dados ausentes | Nenhum encerramento: 0. |
| Repetições | Um episódio conta uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Casos abertos (estoque) (`ABERTOS`)

| Campo | Definição |
|---|---|
| Fórmula | episódios sem encerramento no instante de referência |
| Unidade de medida | episódios |
| População | Episódios abertos; com filtro de setor, o setor ATUAL. |
| Exclusões | Encerrados. |
| Denominador | — |
| Marco inicial | entrada_em |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | — |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Abertos por etapa atual (`ABERTOS_ETAPA`)

| Campo | Definição |
|---|---|
| Fórmula | contagem por etapa; base = total de abertos |
| Unidade de medida | episódios |
| População | Episódios abertos. |
| Exclusões | — |
| Denominador | Total de abertos (base). |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | — |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Abertos por setor atual (`ABERTOS_SETOR`)

| Campo | Definição |
|---|---|
| Fórmula | contagem por setor; base = total de abertos |
| Unidade de medida | episódios |
| População | Episódios abertos. |
| Exclusões | — |
| Denominador | Total de abertos (base). |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | — |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Abertos com bloqueio registrado (`BLOQUEADOS_AGORA`)

| Campo | Definição |
|---|---|
| Fórmula | quantidade com motivo de bloqueio; base = abertos |
| Unidade de medida | episódios |
| População | Episódios abertos. |
| Exclusões | — |
| Denominador | Total de abertos. |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | Sem bloqueio registrado não significa ausência de espera. |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Pendências abertas e vencidas (`PENDENCIAS_ABERTAS`)

| Campo | Definição |
|---|---|
| Fórmula | quantidade de pendências abertas; parte = vencidas (prazo anterior ao instante de referência); episódios = casos com pendência aberta |
| Unidade de medida | pendências |
| População | Pendências abertas de episódios abertos. |
| Exclusões | — |
| Denominador | — |
| Marco inicial | criada_em |
| Marco final | prazo |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só pendências abertas. |
| Dados ausentes | — |
| Repetições | Cada pendência conta uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Casos com pendência vencida (`CASOS_COM_VENCIDA`)

| Campo | Definição |
|---|---|
| Fórmula | episódios com ≥ 1 pendência vencida; base = abertos |
| Unidade de medida | episódios |
| População | Episódios abertos. |
| Exclusões | — |
| Denominador | Total de abertos. |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | — |
| Repetições | Um episódio conta uma vez, mesmo com várias vencidas. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Casos em alerta operacional agora (`CASOS_EM_ALERTA`)

| Campo | Definição |
|---|---|
| Fórmula | episódios com ≥ 1 alerta pelo mesmo motor da Torre e as regras ATIVAS; base = abertos; por regra em ALERTA_REGRA |
| Unidade de medida | episódios |
| População | Episódios abertos (todos, sem paginação; acima do limite técnico, indisponível). |
| Exclusões | Sem regra ativa: indisponível (não zero). |
| Denominador | Total de abertos. |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. Alertas passados NÃO são reconstruídos (não há histórico de alertas). |
| Dados ausentes | Sem regras: indisponível. |
| Repetições | Um episódio conta uma vez por regra. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Duração das etapas concluídas (`ETAPA_CONCLUIDA`)

| Campo | Definição |
|---|---|
| Fórmula | por etapa: n de intervalos que TERMINARAM no período; média, mediana, P90 e máximo de (fim − início) |
| Unidade de medida | minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados) |
| População | Intervalos de etapa (exceto etapas de desfecho) com fim no período. Com filtro de setor: o setor em que a etapa COMEÇOU. |
| Exclusões | Etapas de desfecho (sem duração). |
| Denominador | n de intervalos (episódios distintos à parte). |
| Marco inicial | entrada na etapa (evento) |
| Marco final | saída da etapa (próximo evento de etapa ou encerramento) |
| Campo temporal e fonte | Linha do tempo do episódio (eventos de etapa, setor e bloqueio pelo instante do fato). Calculado no banco sobre TODOS os registros da unidade, nunca sobre a página da Torre. |
| Abertos e encerrados | Só intervalos concluídos — nunca misturados com a idade dos em curso. |
| Dados ausentes | Sem intervalos: "sem dados". |
| Repetições | Cada passagem pela mesma etapa é um intervalo (o mesmo episódio pode contar mais de uma vez). |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Idade das etapas em curso (`ETAPA_EM_CURSO`)

| Campo | Definição |
|---|---|
| Fórmula | por etapa: idade (instante de referência − entrada na etapa) dos episódios abertos |
| Unidade de medida | minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados) |
| População | Episódios abertos com etapa atual em curso; com filtro de setor, o setor atual. |
| Exclusões | Episódios sem linha do tempo (ver Qualidade). |
| Denominador | n de episódios. |
| Marco inicial | entrada na etapa atual |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. Linha do tempo do episódio (eventos de etapa, setor e bloqueio pelo instante do fato). Calculado no banco sobre TODOS os registros da unidade, nunca sobre a página da Torre. |
| Abertos e encerrados | Só em curso (espera ainda não terminou: não é duração). |
| Dados ausentes | Sem casos: "sem dados". |
| Repetições | Um intervalo (o atual) por episódio. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Tempo nos setores (`SETOR_TEMPO`)

| Campo | Definição |
|---|---|
| Fórmula | por setor: soma dos minutos passados no setor sobrepostos ao período |
| Unidade de medida | minutos e episódios |
| População | Intervalos de setor reconstruídos da linha do tempo; com filtro de etapa, só enquanto o episódio estava naquela etapa. |
| Exclusões | — |
| Denominador | — |
| Marco inicial | entrada no setor |
| Marco final | saída do setor, encerramento ou instante de referência |
| Campo temporal e fonte | Linha do tempo do episódio (eventos de etapa, setor e bloqueio pelo instante do fato). Calculado no banco sobre TODOS os registros da unidade, nunca sobre a página da Torre. A espera é atribuída ao setor em que ocorreu, não ao setor atual. |
| Abertos e encerrados | Abertos contam até o instante de referência. |
| Dados ausentes | Sem tempo: linha omitida. |
| Repetições | Cada estadia no setor conta pelo tempo sobreposto. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Tempo bloqueado por categoria (`BLOQUEIO_CATEGORIA`)

| Campo | Definição |
|---|---|
| Fórmula | por categoria registrada no INÍCIO do bloqueio: minutos sobrepostos ao período; quantidade = intervalos com tempo no período; parte = intervalos INICIADOS no período |
| Unidade de medida | minutos, intervalos e episódios |
| População | Intervalos de bloqueio (BLOQUEIO_DEFINIDO até o próximo evento de bloqueio ou o encerramento). Filtros de setor/etapa: só o trecho vivido no setor/etapa. |
| Exclusões | Nenhuma; redefinição do mesmo motivo (ex.: só o detalhe mudou) continua o mesmo intervalo, com o início e a categoria originais. |
| Denominador | — |
| Marco inicial | BLOQUEIO_DEFINIDO |
| Marco final | próximo evento de bloqueio, encerramento ou instante de referência |
| Campo temporal e fonte | Linha do tempo do episódio (eventos de etapa, setor e bloqueio pelo instante do fato). Calculado no banco sobre TODOS os registros da unidade, nunca sobre a página da Torre. |
| Abertos e encerrados | Abertos contam até o instante de referência. |
| Dados ausentes | Sem bloqueio registrado: "sem dados" (não significa ausência de espera). |
| Repetições | Cada intervalo conta pelo tempo sobreposto; um intervalo que começou antes do período soma tempo mas não conta como iniciado. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Motivos registrados (`BLOQUEIO_MOTIVO`)

| Campo | Definição |
|---|---|
| Fórmula | como BLOQUEIO_CATEGORIA, por motivo (grupo = categoria) |
| Unidade de medida | minutos, intervalos e episódios |
| População | Mesma de BLOQUEIO_CATEGORIA. |
| Exclusões | — |
| Denominador | — |
| Marco inicial | BLOQUEIO_DEFINIDO |
| Marco final | idem |
| Campo temporal e fonte | Linha do tempo do episódio (eventos de etapa, setor e bloqueio pelo instante do fato). Calculado no banco sobre TODOS os registros da unidade, nunca sobre a página da Torre. Motivo registrado pela equipe — NÃO é causa comprovada. |
| Abertos e encerrados | idem |
| Dados ausentes | idem |
| Repetições | idem |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Duração dos bloqueios concluídos (`BLOQUEIO_CONCLUIDO`)

| Campo | Definição |
|---|---|
| Fórmula | por categoria: média, mediana, P90 e máximo da duração inteira dos bloqueios que TERMINARAM no período |
| Unidade de medida | minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados) |
| População | Intervalos de bloqueio com fim no período; filtros pelo setor/etapa no início do bloqueio. |
| Exclusões | — |
| Denominador | n de intervalos. |
| Marco inicial | BLOQUEIO_DEFINIDO |
| Marco final | fim do bloqueio |
| Campo temporal e fonte | Linha do tempo do episódio (eventos de etapa, setor e bloqueio pelo instante do fato). Calculado no banco sobre TODOS os registros da unidade, nunca sobre a página da Torre. |
| Abertos e encerrados | Só concluídos. |
| Dados ausentes | Sem dados: "sem dados". |
| Repetições | Cada intervalo conta uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Idade dos bloqueios em curso (`BLOQUEIO_EM_CURSO`)

| Campo | Definição |
|---|---|
| Fórmula | por categoria: idade (instante de referência − início) dos bloqueios ainda ativos |
| Unidade de medida | minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados) |
| População | Episódios abertos bloqueados; filtros pelo setor/etapa atuais. |
| Exclusões | — |
| Denominador | n de episódios. |
| Marco inicial | BLOQUEIO_DEFINIDO |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só em curso. |
| Dados ausentes | Sem dados: "sem dados". |
| Repetições | Um bloqueio ativo por episódio. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Pendências abertas por categoria (`PENDENCIA_CATEGORIA`)

| Campo | Definição |
|---|---|
| Fórmula | quantidade; parte = vencidas; idade (referência − criação): mediana, P90, máximo |
| Unidade de medida | pendências e minutos |
| População | Pendências abertas de episódios abertos (setor/etapa atuais). |
| Exclusões | — |
| Denominador | — |
| Marco inicial | criada_em |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertas. |
| Dados ausentes | — |
| Repetições | Cada pendência uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Pendências abertas por tipo de responsável (`PENDENCIA_RESPONSAVEL`)

| Campo | Definição |
|---|---|
| Fórmula | como PENDENCIA_CATEGORIA, por tipo (USUARIO, SETOR ou PERFIL) — sem nomes |
| Unidade de medida | pendências |
| População | Idem. |
| Exclusões | — |
| Denominador | — |
| Marco inicial | criada_em |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. Responsável ATUAL (trocas não guardam o anterior). |
| Abertos e encerrados | Só abertas. |
| Dados ausentes | — |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Pendências criadas (`CRIADAS`)

| Campo | Definição |
|---|---|
| Fórmula | contagem por categoria (CRIADAS_TOTAL: total; parte = com prazo alterado depois) |
| Unidade de medida | pendências |
| População | Pendências com criada_em no período; setor do episódio NA CRIAÇÃO (linha do tempo). |
| Exclusões | — |
| Denominador | — |
| Marco inicial | criada_em |
| Marco final | — |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. |
| Abertos e encerrados | Abertas e encerradas. |
| Dados ausentes | — |
| Repetições | Cada pendência uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Pendências encerradas (`ENCERRADAS`)

| Campo | Definição |
|---|---|
| Fórmula | por situação (resolvida/cancelada) e por categoria: quantidade; parte = encerradas até o prazo; base = encerradas; tempo até o encerramento (mediana, P90) |
| Unidade de medida | pendências e minutos |
| População | Pendências com encerrada_em no período; setor do episódio NO ENCERRAMENTO. |
| Exclusões | — |
| Denominador | Encerradas (base). |
| Marco inicial | criada_em |
| Marco final | encerrada_em |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. "No prazo" usa o ÚLTIMO prazo registrado. |
| Abertos e encerrados | Só encerradas. |
| Dados ausentes | Base zero: percentual ausente. |
| Repetições | Cada pendência uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Pendências abertas (`ABERTAS`)

| Campo | Definição |
|---|---|
| Fórmula | por categoria, criticidade e tipo de responsável: quantidade, vencidas (parte) e idade |
| Unidade de medida | pendências e minutos |
| População | Pendências abertas no instante de referência (setor atual). |
| Exclusões | — |
| Denominador | — |
| Marco inicial | criada_em |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertas. |
| Dados ausentes | Sem pendências: "sem dados". |
| Repetições | Cada pendência uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Atraso das pendências vencidas (`VENCIDAS_ATRASO`)

| Campo | Definição |
|---|---|
| Fórmula | referência − prazo das abertas vencidas (mediana, P90, máximo) |
| Unidade de medida | minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados) |
| População | Pendências abertas com prazo anterior à referência. |
| Exclusões | — |
| Denominador | — |
| Marco inicial | prazo |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertas. |
| Dados ausentes | Nenhuma vencida: "sem dados". |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Lista operacional de pendências abertas (nominal) (`LISTA_PENDENCIAS`)

| Campo | Definição |
|---|---|
| Fórmula | pendências abertas ordenadas por prazo, com caso, setor, etapa, motivo atual, ação registrada, responsável e prazo |
| Unidade de medida | pendências |
| População | Só para perfis com acesso nominal (não para a Direção); limite técnico — acima dele a lista NÃO é exibida (nunca parcial). |
| Exclusões | — |
| Denominador | — |
| Marco inicial | criada_em |
| Marco final | prazo |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertas. |
| Dados ausentes | — |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Comparação entre períodos (`EVOLUCAO`)

| Campo | Definição |
|---|---|
| Fórmula | mesmas métricas no período escolhido e no período anterior de MESMA quantidade de dias locais, ambos ENCERRADOS (fim até ontem no fuso da unidade); variação absoluta (na unidade da métrica: contagem, minutos, ou pontos percentuais para métricas em %) e variação relativa em % (ausente quando o valor anterior é zero; não se aplica a métricas que já são percentuais) |
| Unidade de medida | conforme a métrica |
| População | Entradas, encerramentos (e transferências), permanência dos encerrados (mediana), pendências criadas/encerradas (até o prazo) e tempo e inícios de bloqueio por categoria — cada uma com o seu verbete neste resultado. |
| Exclusões | Permanência: exclui encerramento administrativo. Pendências "até o prazo": usa o ÚLTIMO prazo registrado. |
| Denominador | Cada métrica com sua base. |
| Marco inicial | conforme a métrica |
| Marco final | conforme a métrica |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. Só períodos encerrados: um período que inclui hoje está incompleto e não é comparável. Os dois períodos usam as mesmas definições, no mesmo instantâneo do banco; com horário de verão, a duração em horas pode diferir (informada no resultado). |
| Abertos e encerrados | Alertas não são comparados (sem histórico). |
| Dados ausentes | Valor anterior zero: variação relativa ausente. |
| Repetições | Como nas seções correspondentes. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Permanência dos encerrados (evolução) (`PERMANENCIA`)

| Campo | Definição |
|---|---|
| Fórmula | mediana (percentil 50 contínuo) de (encerrado_em − entrada_em) dos episódios encerrados no período; n = encerrados incluídos |
| Unidade de medida | minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados) |
| População | Episódios encerrados no período; com filtro de setor, o setor FINAL. |
| Exclusões | Desfecho "encerramento administrativo" (cancelamento/registro indevido) — proposta, como na etapa 7. |
| Denominador | Encerrados incluídos (n). |
| Marco inicial | entrada_em |
| Marco final | encerrado_em |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. |
| Abertos e encerrados | Só encerrados. |
| Dados ausentes | Sem encerrados incluídos: "sem dados" (nunca zero). |
| Repetições | Um episódio conta uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Tempo desde o último registro (`ATUALIDADE`)

| Campo | Definição |
|---|---|
| Fórmula | referência − último registro (registrado_em) na linha do tempo dos abertos: média, mediana, P90, máximo; quantidade = com registro; base = abertos |
| Unidade de medida | minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados) |
| População | Episódios abertos. |
| Exclusões | — |
| Denominador | Abertos (base). |
| Marco inicial | último registro |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | Sem nenhum registro: fora da estatística, contado na diferença base − quantidade. |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Abertos além do critério configurado (`SEM_ATUALIZACAO_REGRA`)

| Campo | Definição |
|---|---|
| Fórmula | por regra ATIVA "sem atualização" (minutos = limite): abertos com referência − último registro ≥ limite |
| Unidade de medida | episódios |
| População | Abertos (na etapa da regra, se houver). |
| Exclusões | — |
| Denominador | Abertos sujeitos à regra. |
| Marco inicial | último registro |
| Marco final | instante de referência |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. Nenhum prazo oficial é presumido: sem regra configurada, não há contagem. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | Sem regra: seção vazia. |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Registros retroativos (`REGISTROS_RETROATIVOS`)

| Campo | Definição |
|---|---|
| Fórmula | registros FEITOS no período (registrado_em); parte = com ajuste manual (fato informado antes do registro); atraso (registro − fato) dos retroativos |
| Unidade de medida | minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados) |
| População | Eventos da linha do tempo com registrado_em no período. Com filtro de setor: o setor em vigor no INSTANTE DO FATO (ocorrido_em), pela linha do tempo — nunca o setor atual; a transferência pertence ao setor de destino. |
| Exclusões | Com filtro de setor: fatos sem setor determinável (ver SETOR_NAO_ATRIBUIDO). |
| Denominador | Registros no período. |
| Marco inicial | ocorrido_em (atribuição do setor) |
| Marco final | registrado_em (período) |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. Dois marcos distintos: o PERÍODO é o do registro; o SETOR é o do fato. |
| Abertos e encerrados | Abertos e encerrados. |
| Dados ausentes | — |
| Repetições | Cada registro uma vez. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Bloqueios com causa ainda não definida (`CAUSA_EM_INVESTIGACAO_AGORA`)

| Campo | Definição |
|---|---|
| Fórmula | abertos bloqueados com categoria NAO_DEFINIDA; base = abertos bloqueados |
| Unidade de medida | episódios |
| População | Abertos bloqueados. |
| Exclusões | — |
| Denominador | Abertos bloqueados. |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | — |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Bloqueios iniciados sem causa definida (`BLOQUEIOS_INICIADOS_SEM_CAUSA`)

| Campo | Definição |
|---|---|
| Fórmula | bloqueios INICIADOS no período com categoria NAO_DEFINIDA no início; base = bloqueios iniciados |
| Unidade de medida | intervalos |
| População | Inícios dos intervalos de bloqueio normalizados (mesma definição de Gargalos e Evolução); com filtro de setor, o setor em que o bloqueio começou. |
| Exclusões | Redefinição do mesmo motivo (ex.: só o detalhe mudou) não é novo bloqueio; bloqueio iniciado antes do período não conta. |
| Denominador | Bloqueios iniciados. |
| Marco inicial | início do intervalo (BLOQUEIO_DEFINIDO que o abriu) |
| Marco final | — |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. |
| Abertos e encerrados | Iniciados no período, abertos ou já removidos. |
| Dados ausentes | — |
| Repetições | Remoção seguida de novo bloqueio = dois inícios. |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Fatos sem setor determinável (`SETOR_NAO_ATRIBUIDO`)

| Campo | Definição |
|---|---|
| Fórmula | com filtro de setor: registros (REGISTROS) e inícios de bloqueio (BLOQUEIOS_INICIADOS) do período sem setor determinável pela linha do tempo; base = todos os do período, antes do filtro |
| Unidade de medida | registros ou intervalos |
| População | Fatos anteriores ao primeiro evento de setor do episódio (ex.: registro legado sem evento de abertura). |
| Exclusões | — |
| Denominador | Fatos do período (todos os setores). |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. |
| Abertos e encerrados | Abertos e encerrados. |
| Dados ausentes | Ficam FORA do filtro de setor — não são atribuídos ao setor atual. |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Cobertura de campos opcionais (`COBERTURA`)

| Campo | Definição |
|---|---|
| Fórmula | DESTINO/PROTOCOLO_EM_TRANSFERENCIA: abertos em etapa de transferência com o campo registrado (quantidade) sobre a base — campo OPCIONAL: ausência não é falha |
| Unidade de medida | episódios |
| População | Abertos em etapas Aceito/Transporte ou que exigem protocolo. |
| Exclusões | — |
| Denominador | Abertos nessas etapas. |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Instante de referência do relatório (relógio do servidor no início do cálculo); não depende do período. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | Base zero: ausente. |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |

### Cobertura da linha do tempo (`LINHA_DO_TEMPO`)

| Campo | Definição |
|---|---|
| Fórmula | episódios no escopo com evento de abertura (quantidade) sobre o total (base) |
| Unidade de medida | episódios |
| População | Escopo próprio: episódios abertos no instante de referência, que entraram no período ou que encerraram no período. Filtro de setor: setor ATUAL (abertos) ou FINAL (encerrados) — sem linha do tempo, não há como atribuir o setor da época. |
| Exclusões | — |
| Denominador | Episódios no escopo. |
| Marco inicial | — |
| Marco final | — |
| Campo temporal e fonte | Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início entra; exatamente no fim não entra. |
| Abertos e encerrados | Abertos e encerrados. |
| Dados ausentes | Sem linha do tempo, o episódio não entra nos tempos reconstruídos (gargalos). |
| Repetições | — |
| Situação | PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09) |
