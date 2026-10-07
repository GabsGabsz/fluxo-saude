package br.fluxosaude.indicador.dominio;

import java.util.List;

/**
 * Dicionário dos indicadores calculados (RF-039, CA-09). TODAS as fórmulas são PROPOSTAS até a
 * validação institucional dos indicadores oficiais e suas fórmulas exatas (V-09). Não há metas,
 * limites clínicos nem comparação entre profissionais. Implementação: funções fluxo.ind_* (V16).
 */
public final class DicionarioIndicadores {

    public static final String PROPOSTA = "PROPOSTA — aguarda validação institucional (V-09)";

    private static final String PERIODO = "Datas locais da unidade [início, fim], convertidas para o intervalo "
            + "semiaberto [início 00:00, (fim + 1 dia) 00:00) no fuso horário da unidade; o banco trata a mudança de "
            + "horário de verão (dias de 23 h ou 25 h). Instante exatamente no início entra; exatamente no fim não entra.";
    private static final String HISTORICO = "Calculado no banco sobre TODOS os registros da unidade (nunca sobre a "
            + "lista paginada da Torre).";

    private static final List<DefinicaoIndicador> DEFINICOES = List.of(
        new DefinicaoIndicador("PERMANENCIA_MEDIA", "Permanência média", "RF-019, §10.5",
            "Tempo médio que os episódios encerrados no período ficaram na unidade.",
            "média(encerrado_em − entrada_em) dos episódios incluídos", "minutos (exibido em horas e minutos)",
            "Episódios da unidade (e do setor, se filtrado) com encerramento no período.",
            "Desfecho \"encerramento administrativo\" (cancelamento/registro indevido) — proposta; a contagem de "
                + "excluídos é exibida.",
            "Número de episódios incluídos.", "Entrada do episódio (entrada_em).", "Encerramento (encerrado_em).",
            "Encerramento do episódio. " + HISTORICO,
            "Episódios ainda abertos NÃO entram (não há marco final); aparecem no retrato atual.",
            "Sem episódios incluídos: \"sem dados\" (nunca zero).", "Um episódio conta uma vez.", PERIODO, PROPOSTA),
        new DefinicaoIndicador("PERMANENCIA_MEDIANA", "Permanência mediana", "RF-019, §10.5",
            "Tempo típico de permanência, menos sensível a casos extremos que a média.",
            "mediana (percentil 50 contínuo) de (encerrado_em − entrada_em)", "minutos",
            "Mesma população da permanência média.", "Mesmas da permanência média.", "—",
            "entrada_em", "encerrado_em", "Encerramento do episódio. " + HISTORICO,
            "Abertos não entram.", "Sem episódios incluídos: \"sem dados\".", "Um episódio conta uma vez.", PERIODO,
            PROPOSTA),
        new DefinicaoIndicador("ACIMA_DO_LIMITE", "Encerrados acima de cada limite configurado", "RF-019, §10.5",
            "Quantos episódios encerrados no período ficaram além de cada limite de permanência configurado.",
            "quantidade com (encerrado_em − entrada_em) ≥ limite; percentual = quantidade ÷ população × 100",
            "episódios e %",
            "Episódios incluídos na permanência, para cada regra de alerta ATIVA do tipo \"tempo total\" sem filtro "
                + "de etapa.",
            "As mesmas da permanência; regras inativas ou restritas a uma etapa não entram.",
            "Episódios incluídos na permanência (o mesmo para todas as regras).", "entrada_em", "encerrado_em",
            "Limite VIGENTE da regra no momento da consulta (alterações posteriores da regra mudam o resultado "
                + "histórico — limitação conhecida). " + HISTORICO,
            "Abertos não entram (os abertos acima dos limites agora estão no retrato atual).",
            "Sem regra configurada: indicador indisponível (\"nenhum limite configurado\"), não zero. "
                + "População zero: percentual ausente (sem divisão por zero).",
            "Um episódio conta uma vez por regra.", PERIODO + " Fronteira do limite: atingir exatamente o limite conta "
                + "como acima (mesma regra dos alertas).", PROPOSTA),
        new DefinicaoIndicador("SAIDAS_POR_DESFECHO", "Saídas por desfecho (inclui transferências)", "§10.5, CA-09",
            "Volume de encerramentos e, em especial, quantidade de transferências.",
            "contagem de episódios encerrados no período, por desfecho; transferências = desfecho TRANSFERÊNCIA",
            "episódios", "Episódios com encerramento no período.", "Nenhuma (o encerramento administrativo aparece "
                + "separado).", "—", "—", "encerrado_em", "Encerramento do episódio. " + HISTORICO,
            "Abertos não entram.", "Nenhum encerramento: contagem zero (é uma contagem, não uma estatística).",
            "Um episódio conta uma vez.", PERIODO, PROPOSTA),
        new DefinicaoIndicador("TEMPO_SOLICITACAO_ACEITE", "Tempo entre solicitação e aceite", "§10.5, §11",
            "Espera entre a solicitação de transferência e o aceite do destino.",
            "média e mediana de (1º aceite após a 1ª solicitação − 1ª solicitação)", "minutos",
            "Episódios cujo marco final (aceite) ocorreu no período.",
            "Episódios sem solicitação registrada antes do aceite: não entram no tempo e são contados como "
                + "\"sem marco inicial\".",
            "Episódios com os dois marcos.",
            "1ª entrada em etapa que exige protocolo externo (configuração da unidade; padrão: \"Transferência "
                + "solicitada\").",
            "1ª entrada em etapa de natureza ACEITO a partir da solicitação.",
            "Instante do fato (ocorrido_em) dos eventos ETAPA_ALTERADA da linha do tempo — o estado atual não "
                + "reconstrói o percurso. " + HISTORICO,
            "Inclui episódios ainda abertos que já tiveram o aceite no período.",
            "Sem episódios com os dois marcos: \"sem dados\".",
            "Solicitações e aceites repetidos: usa a PRIMEIRA solicitação e o PRIMEIRO aceite depois dela.",
            PERIODO, PROPOSTA),
        new DefinicaoIndicador("TEMPO_ACEITE_SAIDA", "Tempo entre aceite e saída", "§10.5, §11",
            "Espera entre o aceite e a saída efetiva do paciente transferido.",
            "média e mediana de (encerrado_em − último aceite antes do encerramento)", "minutos",
            "Episódios encerrados no período com desfecho TRANSFERÊNCIA.",
            "Transferidos sem aceite registrado: não entram no tempo; contados como \"sem marco inicial\".",
            "Episódios com aceite registrado.", "ÚLTIMA entrada em etapa de natureza ACEITO antes do encerramento.",
            "encerrado_em", "Eventos ETAPA_ALTERADA (ocorrido_em) e encerramento. " + HISTORICO,
            "Abertos não entram.", "Sem episódios com aceite: \"sem dados\".",
            "Aceites repetidos: usa o ÚLTIMO antes da saída.", PERIODO, PROPOSTA),
        new DefinicaoIndicador("MOTIVOS_DE_BLOQUEIO", "Distribuição dos motivos de atraso/gargalo", "RF-020, CA-09",
            "Quais motivos mais retêm pacientes e por quanto tempo.",
            "por motivo: soma da sobreposição de cada intervalo bloqueado com o período; % = soma do motivo ÷ soma "
                + "de todos os motivos × 100; também nº de intervalos iniciados no período e nº de episódios",
            "minutos, %, intervalos e episódios",
            "Intervalos de bloqueio de episódios da unidade que se sobrepõem ao período.",
            "Nenhuma; trocar só o detalhe do mesmo motivo não inicia novo intervalo.",
            "Soma dos minutos bloqueados de todos os motivos no período.",
            "Evento BLOQUEIO_DEFINIDO (ocorrido_em).",
            "Próximo evento de bloqueio (troca ou remoção), encerramento do episódio ou \"agora\" se ainda bloqueado.",
            "Eventos BLOQUEIO_DEFINIDO/BLOQUEIO_REMOVIDO da linha do tempo, em ordem do fato. " + HISTORICO,
            "Inclui bloqueios de episódios abertos (até agora) e encerrados.",
            "Sem bloqueio no período: \"sem dados\" (não 0%).",
            "Cada intervalo conta pelo tempo que se sobrepõe ao período; um intervalo iniciado antes do período "
                + "contribui com tempo mas não conta como iniciado.", PERIODO, PROPOSTA),
        new DefinicaoIndicador("VOLUME_DIARIO", "Volume diário (tendência)", "§10.5 (tendência histórica)",
            "Entradas e saídas por dia, para ver a tendência no período.",
            "contagem de entradas (entrada_em) e de saídas (encerrado_em) por dia local", "episódios por dia",
            "Episódios da unidade (e do setor, se filtrado).", "Nenhuma.", "—", "—", "—",
            "entrada_em e encerrado_em, agrupados pelo dia local da unidade. " + HISTORICO,
            "Entradas incluem episódios ainda abertos.", "Dia sem movimento aparece com 0 (contagem).",
            "Um episódio conta uma vez em cada série.", PERIODO, PROPOSTA),
        new DefinicaoIndicador("RETRATO_ATUAL", "Retrato atual da unidade", "§10.1, RF-019",
            "Situação AGORA (não histórica): casos ativos, por etapa e motivo atual, pendências vencidas e casos "
                + "acima de cada limite configurado.",
            "contagens sobre TODOS os episódios abertos no instante da consulta (relógio do servidor); acima dos "
                + "limites = mesmo cálculo dos alertas operacionais", "episódios / pendências",
            "Episódios abertos da unidade (e do setor, se filtrado).", "Encerrados.", "—", "—", "agora (servidor)",
            "Estado atual do episódio e das pendências.", "Só abertos.",
            "Sem regras ativas: \"acima dos limites\" indisponível (não zero).", "—",
            "Instantâneo no horário do servidor; não depende do período escolhido.", PROPOSTA));

    private DicionarioIndicadores() {
    }

    public static List<DefinicaoIndicador> definicoes() {
        return DEFINICOES;
    }
}
