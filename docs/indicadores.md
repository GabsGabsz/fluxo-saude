# Dicionário de indicadores (RF-039)

Fonte de verdade: `DicionarioIndicadores` (servido em `GET /api/indicadores/dicionario` e exibido na tela
**Indicadores**). Este arquivo é gerado a partir dela. Cálculo: funções `fluxo.ind_*` da migração V16
([ADR-0009](adr/0009-plantao-e-indicadores.md)); valores conferidos à mão em
`backend/src/test/sql/t13_indicadores.sql` e `IndicadoresIT`.

> **Todas as fórmulas são PROPOSTAS** até a validação institucional dos indicadores oficiais e de suas
> fórmulas exatas (V-09). Não há metas, limites clínicos nem comparação entre profissionais. Os
> indicadores são operacionais e não indicam risco clínico.

## Permanência média (`PERMANENCIA_MEDIA`)

| Campo | Definição |
|---|---|
| Requisitos | RF-019, §10.5 |
| Finalidade | Tempo médio que os episódios encerrados no período ficaram na unidade. |
| Fórmula | média(encerrado_em − entrada_em) dos episódios incluídos |
| Unidade de medida | minutos (exibido em horas e minutos) |
| População | Episódios da unidade (e do setor, se filtrado) com encerramento no período. |
| Exclusões | Desfecho "encerramento administrativo" (cancelamento/registro indevido) — proposta; a contagem de excluídos é exibida. |
| Denominador | Número de episódios incluídos. |
| Marco inicial | Entrada do episódio (entrada_em). |
| Marco final | Encerramento (encerrado_em). |
| Evento e campo temporal | Encerramento do episódio. Calculado no banco sobre TODOS os registros da unidade (nunca sobre a lista paginada da Torre). |
| Abertos e encerrados | Episódios ainda abertos NÃO entram (não há marco final); aparecem no retrato atual. |
| Dados ausentes | Sem episódios incluídos: "sem dados" (nunca zero). |
| Repetições | Um episódio conta uma vez. |
| Período e fronteiras | Datas locais da unidade [início, fim], convertidas para o intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra. |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

## Permanência mediana (`PERMANENCIA_MEDIANA`)

| Campo | Definição |
|---|---|
| Requisitos | RF-019, §10.5 |
| Finalidade | Tempo típico de permanência, menos sensível a casos extremos que a média. |
| Fórmula | mediana (percentil 50 contínuo) de (encerrado_em − entrada_em) |
| Unidade de medida | minutos |
| População | Mesma população da permanência média. |
| Exclusões | Mesmas da permanência média. |
| Denominador | — |
| Marco inicial | entrada_em |
| Marco final | encerrado_em |
| Evento e campo temporal | Encerramento do episódio. Calculado no banco sobre TODOS os registros da unidade (nunca sobre a lista paginada da Torre). |
| Abertos e encerrados | Abertos não entram. |
| Dados ausentes | Sem episódios incluídos: "sem dados". |
| Repetições | Um episódio conta uma vez. |
| Período e fronteiras | Datas locais da unidade [início, fim], convertidas para o intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra. |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

## Encerrados acima de cada limite configurado (`ACIMA_DO_LIMITE`)

| Campo | Definição |
|---|---|
| Requisitos | RF-019, §10.5 |
| Finalidade | Quantos episódios encerrados no período ficaram além de cada limite de permanência configurado. |
| Fórmula | quantidade com (encerrado_em − entrada_em) ≥ limite; percentual = quantidade ÷ população × 100 |
| Unidade de medida | episódios e % |
| População | Episódios incluídos na permanência, para cada regra de alerta ATIVA do tipo "tempo total" sem filtro de etapa. |
| Exclusões | As mesmas da permanência; regras inativas ou restritas a uma etapa não entram. |
| Denominador | Episódios incluídos na permanência (o mesmo para todas as regras). |
| Marco inicial | entrada_em |
| Marco final | encerrado_em |
| Evento e campo temporal | Limite VIGENTE da regra no momento da consulta (alterações posteriores da regra mudam o resultado histórico — limitação conhecida). Calculado no banco sobre TODOS os registros da unidade (nunca sobre a lista paginada da Torre). |
| Abertos e encerrados | Abertos não entram (os abertos acima dos limites agora estão no retrato atual). |
| Dados ausentes | Sem regra configurada: indicador indisponível ("nenhum limite configurado"), não zero. População zero: percentual ausente (sem divisão por zero). |
| Repetições | Um episódio conta uma vez por regra. |
| Período e fronteiras | Datas locais da unidade [início, fim], convertidas para o intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra. Fronteira do limite: atingir exatamente o limite conta como acima (mesma regra dos alertas). |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

## Saídas por desfecho (inclui transferências) (`SAIDAS_POR_DESFECHO`)

| Campo | Definição |
|---|---|
| Requisitos | §10.5, CA-09 |
| Finalidade | Volume de encerramentos e, em especial, quantidade de transferências. |
| Fórmula | contagem de episódios encerrados no período, por desfecho; transferências = desfecho TRANSFERÊNCIA |
| Unidade de medida | episódios |
| População | Episódios com encerramento no período. |
| Exclusões | Nenhuma (o encerramento administrativo aparece separado). |
| Denominador | — |
| Marco inicial | — |
| Marco final | encerrado_em |
| Evento e campo temporal | Encerramento do episódio. Calculado no banco sobre TODOS os registros da unidade (nunca sobre a lista paginada da Torre). |
| Abertos e encerrados | Abertos não entram. |
| Dados ausentes | Nenhum encerramento: contagem zero (é uma contagem, não uma estatística). |
| Repetições | Um episódio conta uma vez. |
| Período e fronteiras | Datas locais da unidade [início, fim], convertidas para o intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra. |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

## Tempo entre solicitação e aceite (`TEMPO_SOLICITACAO_ACEITE`)

| Campo | Definição |
|---|---|
| Requisitos | §10.5, §11 |
| Finalidade | Espera entre a solicitação de transferência e o aceite do destino. |
| Fórmula | média e mediana de (1º aceite após a 1ª solicitação − 1ª solicitação) |
| Unidade de medida | minutos |
| População | Episódios cujo marco final (aceite) ocorreu no período. |
| Exclusões | Episódios sem solicitação registrada antes do aceite: não entram no tempo e são contados como "sem marco inicial". |
| Denominador | Episódios com os dois marcos. |
| Marco inicial | 1ª entrada em etapa que exige protocolo externo (configuração da unidade; padrão: "Transferência solicitada"). |
| Marco final | 1ª entrada em etapa de natureza ACEITO a partir da solicitação. |
| Evento e campo temporal | Instante do fato (ocorrido_em) dos eventos ETAPA_ALTERADA da linha do tempo — o estado atual não reconstrói o percurso. Calculado no banco sobre TODOS os registros da unidade (nunca sobre a lista paginada da Torre). |
| Abertos e encerrados | Inclui episódios ainda abertos que já tiveram o aceite no período. |
| Dados ausentes | Sem episódios com os dois marcos: "sem dados". |
| Repetições | Solicitações e aceites repetidos: usa a PRIMEIRA solicitação e o PRIMEIRO aceite depois dela. |
| Período e fronteiras | Datas locais da unidade [início, fim], convertidas para o intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra. |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

## Tempo entre aceite e saída (`TEMPO_ACEITE_SAIDA`)

| Campo | Definição |
|---|---|
| Requisitos | §10.5, §11 |
| Finalidade | Espera entre o aceite e a saída efetiva do paciente transferido. |
| Fórmula | média e mediana de (encerrado_em − último aceite antes do encerramento) |
| Unidade de medida | minutos |
| População | Episódios encerrados no período com desfecho TRANSFERÊNCIA. |
| Exclusões | Transferidos sem aceite registrado: não entram no tempo; contados como "sem marco inicial". |
| Denominador | Episódios com aceite registrado. |
| Marco inicial | ÚLTIMA entrada em etapa de natureza ACEITO antes do encerramento. |
| Marco final | encerrado_em |
| Evento e campo temporal | Eventos ETAPA_ALTERADA (ocorrido_em) e encerramento. Calculado no banco sobre TODOS os registros da unidade (nunca sobre a lista paginada da Torre). |
| Abertos e encerrados | Abertos não entram. |
| Dados ausentes | Sem episódios com aceite: "sem dados". |
| Repetições | Aceites repetidos: usa o ÚLTIMO antes da saída. |
| Período e fronteiras | Datas locais da unidade [início, fim], convertidas para o intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra. |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

## Distribuição dos motivos de atraso/gargalo (`MOTIVOS_DE_BLOQUEIO`)

| Campo | Definição |
|---|---|
| Requisitos | RF-020, CA-09 |
| Finalidade | Quais motivos mais retêm pacientes e por quanto tempo. |
| Fórmula | por motivo: soma da sobreposição de cada intervalo bloqueado com o período; % = soma do motivo ÷ soma de todos os motivos × 100; também nº de intervalos iniciados no período e nº de episódios |
| Unidade de medida | minutos, %, intervalos e episódios |
| População | Intervalos de bloqueio de episódios da unidade que se sobrepõem ao período. |
| Exclusões | Nenhuma; trocar só o detalhe do mesmo motivo não inicia novo intervalo. |
| Denominador | Soma dos minutos bloqueados de todos os motivos no período. |
| Marco inicial | Evento BLOQUEIO_DEFINIDO (ocorrido_em). |
| Marco final | Próximo evento de bloqueio (troca ou remoção), encerramento do episódio ou "agora" se ainda bloqueado. |
| Evento e campo temporal | Eventos BLOQUEIO_DEFINIDO/BLOQUEIO_REMOVIDO da linha do tempo, em ordem do fato. Calculado no banco sobre TODOS os registros da unidade (nunca sobre a lista paginada da Torre). |
| Abertos e encerrados | Inclui bloqueios de episódios abertos (até agora) e encerrados. |
| Dados ausentes | Sem bloqueio no período: "sem dados" (não 0%). |
| Repetições | Cada intervalo conta pelo tempo que se sobrepõe ao período; um intervalo iniciado antes do período contribui com tempo mas não conta como iniciado. |
| Período e fronteiras | Datas locais da unidade [início, fim], convertidas para o intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra. |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

## Volume diário (tendência) (`VOLUME_DIARIO`)

| Campo | Definição |
|---|---|
| Requisitos | §10.5 (tendência histórica) |
| Finalidade | Entradas e saídas por dia, para ver a tendência no período. |
| Fórmula | contagem de entradas (entrada_em) e de saídas (encerrado_em) por dia local |
| Unidade de medida | episódios por dia |
| População | Episódios da unidade (e do setor, se filtrado). |
| Exclusões | Nenhuma. |
| Denominador | — |
| Marco inicial | — |
| Marco final | — |
| Evento e campo temporal | entrada_em e encerrado_em, agrupados pelo dia local da unidade. Calculado no banco sobre TODOS os registros da unidade (nunca sobre a lista paginada da Torre). |
| Abertos e encerrados | Entradas incluem episódios ainda abertos. |
| Dados ausentes | Dia sem movimento aparece com 0 (contagem). |
| Repetições | Um episódio conta uma vez em cada série. |
| Período e fronteiras | Datas locais da unidade [início, fim], convertidas para o intervalo semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra. |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

## Retrato atual da unidade (`RETRATO_ATUAL`)

| Campo | Definição |
|---|---|
| Requisitos | §10.1, RF-019 |
| Finalidade | Situação AGORA (não histórica): casos ativos, por etapa e motivo atual, pendências vencidas e casos acima de cada limite configurado. |
| Fórmula | contagens sobre TODOS os episódios abertos no instante da consulta (relógio do servidor); acima dos limites = mesmo cálculo dos alertas operacionais |
| Unidade de medida | episódios / pendências |
| População | Episódios abertos da unidade (e do setor, se filtrado). |
| Exclusões | Encerrados. |
| Denominador | — |
| Marco inicial | — |
| Marco final | agora (servidor) |
| Evento e campo temporal | Estado atual do episódio e das pendências. |
| Abertos e encerrados | Só abertos. |
| Dados ausentes | Sem regras ativas: "acima dos limites" indisponível (não zero). |
| Repetições | — |
| Período e fronteiras | Instantâneo no horário do servidor; não depende do período escolhido. |
| Situação | PROPOSTA — aguarda validação institucional (V-09) |

