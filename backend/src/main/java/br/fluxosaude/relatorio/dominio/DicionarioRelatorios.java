package br.fluxosaude.relatorio.dominio;

import br.fluxosaude.indicador.dominio.DefinicaoIndicador;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dicionário das seções dos relatórios gerenciais (extensão aprovada, issue #9). Cada verbete diz
 * fórmula, população, denominador, marcos, exclusões, abertos/encerrados, repetições, dados
 * ausentes e período. Métricas que coincidem com a etapa 7 reutilizam a mesma definição
 * (indicadores, V-09). TODAS as fórmulas são propostas até a validação institucional; nenhuma
 * métrica aqui é causal, clínica ou de produtividade individual.
 */
public final class DicionarioRelatorios {

    /**
     * Versão das definições: aparece no cabeçalho de tela, impressão e CSV, e as definições usadas vão
     * DENTRO de cada resultado (assinadas). v2 (revisão do PR #11): Qualidade pelo setor do fato e por
     * bloqueios normalizados (V20); Evolução só com períodos encerrados; categoria do início.
     */
    public static final String VERSAO = "relatorios-v2";
    public static final String PROPOSTA = "PROPOSTA — extensão aprovada (issue #9); fórmulas institucionais pendentes (V-09)";
    private static final String REQ = "Extensão aprovada (issue #9), apoiada em RF-019/020/039";

    private static final String PERIODO = "Datas locais da unidade [início, fim] → intervalo semiaberto [início 00:00, "
            + "(fim + 1 dia) 00:00) no fuso da unidade (horário de verão tratado pelo banco). Instante exatamente no início "
            + "entra; exatamente no fim não entra.";
    private static final String ESTOQUE = "Instante de referência do relatório (relógio do servidor no início do cálculo); "
            + "não depende do período.";
    private static final String LINHA = "Linha do tempo do episódio (eventos de etapa, setor e bloqueio pelo instante do "
            + "fato). Calculado no banco sobre TODOS os registros da unidade, nunca sobre a página da Torre.";
    private static final String P = "minutos; mediana e P90 = percentis 50 e 90 contínuos (interpolados)";

    private static final Map<String, DefinicaoIndicador> DEFINICOES = new LinkedHashMap<>();

    static {
        // ------------------------------------------------------------------ resumo
        d("ENTRADAS", "Entradas no período", "quantidade de episódios com entrada_em no período", "episódios",
                "Episódios da unidade; com filtro de setor, o setor de ENTRADA (evento de abertura).",
                "Nenhuma. 'parte' = entradas sem setor de entrada registrado na linha do tempo (não entram no filtro "
                        + "de setor).", "—", "entrada_em", "—", PERIODO, "Abertos e encerrados contam.",
                "Período sem entradas: 0 (contagem).", "Um episódio conta uma vez.");
        d("ENCERRAMENTOS", "Encerramentos por desfecho", "contagem de episódios com encerrado_em no período, por desfecho",
                "episódios", "Episódios encerrados no período; com filtro de setor, o setor FINAL.", "Nenhuma.", "—", "—",
                "encerrado_em", PERIODO, "Só encerrados.", "Nenhum encerramento: 0.", "Um episódio conta uma vez.");
        d("ABERTOS", "Casos abertos (estoque)", "episódios sem encerramento no instante de referência", "episódios",
                "Episódios abertos; com filtro de setor, o setor ATUAL.", "Encerrados.", "—", "entrada_em",
                "instante de referência", ESTOQUE, "Só abertos.", "—", "—");
        d("ABERTOS_ETAPA", "Abertos por etapa atual", "contagem por etapa; base = total de abertos", "episódios",
                "Episódios abertos.", "—", "Total de abertos (base).", "—", "—", ESTOQUE, "Só abertos.", "—", "—");
        d("ABERTOS_SETOR", "Abertos por setor atual", "contagem por setor; base = total de abertos", "episódios",
                "Episódios abertos.", "—", "Total de abertos (base).", "—", "—", ESTOQUE, "Só abertos.", "—", "—");
        d("BLOQUEADOS_AGORA", "Abertos com bloqueio registrado", "quantidade com motivo de bloqueio; base = abertos",
                "episódios", "Episódios abertos.", "—", "Total de abertos.", "—", "—", ESTOQUE,
                "Só abertos.", "Sem bloqueio registrado não significa ausência de espera.", "—");
        d("PENDENCIAS_ABERTAS", "Pendências abertas e vencidas", "quantidade de pendências abertas; parte = vencidas "
                + "(prazo anterior ao instante de referência); episódios = casos com pendência aberta", "pendências",
                "Pendências abertas de episódios abertos.", "—", "—", "criada_em", "prazo", ESTOQUE,
                "Só pendências abertas.", "—", "Cada pendência conta uma vez.");
        d("CASOS_COM_VENCIDA", "Casos com pendência vencida", "episódios com ≥ 1 pendência vencida; base = abertos",
                "episódios", "Episódios abertos.", "—", "Total de abertos.", "—", "—", ESTOQUE, "Só abertos.", "—",
                "Um episódio conta uma vez, mesmo com várias vencidas.");
        d("CASOS_EM_ALERTA", "Casos em alerta operacional agora", "episódios com ≥ 1 alerta pelo mesmo motor da Torre e "
                + "as regras ATIVAS; base = abertos; por regra em ALERTA_REGRA", "episódios",
                "Episódios abertos (todos, sem paginação; acima do limite técnico, indisponível).",
                "Sem regra ativa: indisponível (não zero).", "Total de abertos.", "—", "—", ESTOQUE,
                "Só abertos. Alertas passados NÃO são reconstruídos (não há histórico de alertas).",
                "Sem regras: indisponível.", "Um episódio conta uma vez por regra.");
        // ------------------------------------------------------------------ gargalos
        d("ETAPA_CONCLUIDA", "Duração das etapas concluídas", "por etapa: n de intervalos que TERMINARAM no período; "
                + "média, mediana, P90 e máximo de (fim − início)", P,
                "Intervalos de etapa (exceto etapas de desfecho) com fim no período. Com filtro de setor: o setor em que "
                        + "a etapa COMEÇOU.", "Etapas de desfecho (sem duração).", "n de intervalos (episódios distintos à parte).",
                "entrada na etapa (evento)", "saída da etapa (próximo evento de etapa ou encerramento)", LINHA,
                "Só intervalos concluídos — nunca misturados com a idade dos em curso.", "Sem intervalos: \"sem dados\".",
                "Cada passagem pela mesma etapa é um intervalo (o mesmo episódio pode contar mais de uma vez).");
        d("ETAPA_EM_CURSO", "Idade das etapas em curso", "por etapa: idade (instante de referência − entrada na etapa) "
                + "dos episódios abertos", P, "Episódios abertos com etapa atual em curso; com filtro de setor, o setor atual.",
                "Episódios sem linha do tempo (ver Qualidade).", "n de episódios.", "entrada na etapa atual", "instante de referência",
                ESTOQUE + " " + LINHA, "Só em curso (espera ainda não terminou: não é duração).", "Sem casos: \"sem dados\".",
                "Um intervalo (o atual) por episódio.");
        d("SETOR_TEMPO", "Tempo nos setores", "por setor: soma dos minutos passados no setor sobrepostos ao período",
                "minutos e episódios", "Intervalos de setor reconstruídos da linha do tempo; com filtro de etapa, só "
                        + "enquanto o episódio estava naquela etapa.", "—", "—", "entrada no setor", "saída do setor, "
                        + "encerramento ou instante de referência", LINHA + " A espera é atribuída ao setor em que ocorreu, "
                        + "não ao setor atual.", "Abertos contam até o instante de referência.", "Sem tempo: linha omitida.",
                "Cada estadia no setor conta pelo tempo sobreposto.");
        d("BLOQUEIO_CATEGORIA", "Tempo bloqueado por categoria", "por categoria registrada no INÍCIO do bloqueio: minutos sobrepostos "
                + "ao período; quantidade = intervalos com tempo no período; parte = intervalos INICIADOS no período",
                "minutos, intervalos e episódios", "Intervalos de bloqueio (BLOQUEIO_DEFINIDO até o próximo evento de "
                        + "bloqueio ou o encerramento). Filtros de setor/etapa: só o trecho vivido no setor/etapa.",
                "Nenhuma; redefinição do mesmo motivo (ex.: só o detalhe mudou) continua o mesmo intervalo, com o início e a categoria originais.", "—", "BLOQUEIO_DEFINIDO",
                "próximo evento de bloqueio, encerramento ou instante de referência", LINHA,
                "Abertos contam até o instante de referência.", "Sem bloqueio registrado: \"sem dados\" (não significa "
                        + "ausência de espera).", "Cada intervalo conta pelo tempo sobreposto; um intervalo que começou "
                        + "antes do período soma tempo mas não conta como iniciado.");
        d("BLOQUEIO_MOTIVO", "Motivos registrados", "como BLOQUEIO_CATEGORIA, por motivo (grupo = categoria)",
                "minutos, intervalos e episódios", "Mesma de BLOQUEIO_CATEGORIA.", "—", "—", "BLOQUEIO_DEFINIDO",
                "idem", LINHA + " Motivo registrado pela equipe — NÃO é causa comprovada.", "idem", "idem", "idem");
        d("BLOQUEIO_CONCLUIDO", "Duração dos bloqueios concluídos", "por categoria: média, mediana, P90 e máximo da "
                + "duração inteira dos bloqueios que TERMINARAM no período", P, "Intervalos de bloqueio com fim no período; "
                        + "filtros pelo setor/etapa no início do bloqueio.", "—", "n de intervalos.", "BLOQUEIO_DEFINIDO",
                "fim do bloqueio", LINHA, "Só concluídos.", "Sem dados: \"sem dados\".", "Cada intervalo conta uma vez.");
        d("BLOQUEIO_EM_CURSO", "Idade dos bloqueios em curso", "por categoria: idade (instante de referência − início) "
                + "dos bloqueios ainda ativos", P, "Episódios abertos bloqueados; filtros pelo setor/etapa atuais.", "—",
                "n de episódios.", "BLOQUEIO_DEFINIDO", "instante de referência", ESTOQUE, "Só em curso.",
                "Sem dados: \"sem dados\".", "Um bloqueio ativo por episódio.");
        d("PENDENCIA_CATEGORIA", "Pendências abertas por categoria", "quantidade; parte = vencidas; idade (referência − "
                + "criação): mediana, P90, máximo", "pendências e minutos", "Pendências abertas de episódios abertos "
                        + "(setor/etapa atuais).", "—", "—", "criada_em", "instante de referência", ESTOQUE,
                "Só abertas.", "—", "Cada pendência uma vez.");
        d("PENDENCIA_RESPONSAVEL", "Pendências abertas por tipo de responsável", "como PENDENCIA_CATEGORIA, por tipo "
                + "(USUARIO, SETOR ou PERFIL) — sem nomes", "pendências", "Idem.", "—", "—", "criada_em",
                "instante de referência", ESTOQUE + " Responsável ATUAL (trocas não guardam o anterior).", "Só abertas.",
                "—", "—");
        // ------------------------------------------------------------------ pendências
        d("CRIADAS", "Pendências criadas", "contagem por categoria (CRIADAS_TOTAL: total; parte = com prazo alterado "
                + "depois)", "pendências", "Pendências com criada_em no período; setor do episódio NA CRIAÇÃO "
                        + "(linha do tempo).", "—", "—", "criada_em", "—", PERIODO, "Abertas e encerradas.", "—",
                "Cada pendência uma vez.");
        d("ENCERRADAS", "Pendências encerradas", "por situação (resolvida/cancelada) e por categoria: quantidade; parte = "
                + "encerradas até o prazo; base = encerradas; tempo até o encerramento (mediana, P90)", "pendências e minutos",
                "Pendências com encerrada_em no período; setor do episódio NO ENCERRAMENTO.", "—", "Encerradas (base).",
                "criada_em", "encerrada_em", PERIODO + " \"No prazo\" usa o ÚLTIMO prazo registrado.", "Só encerradas.",
                "Base zero: percentual ausente.", "Cada pendência uma vez.");
        d("ABERTAS", "Pendências abertas", "por categoria, criticidade e tipo de responsável: quantidade, vencidas "
                + "(parte) e idade", "pendências e minutos", "Pendências abertas no instante de referência (setor atual).",
                "—", "—", "criada_em", "instante de referência", ESTOQUE, "Só abertas.", "Sem pendências: \"sem dados\".",
                "Cada pendência uma vez.");
        d("VENCIDAS_ATRASO", "Atraso das pendências vencidas", "referência − prazo das abertas vencidas (mediana, P90, "
                + "máximo)", P, "Pendências abertas com prazo anterior à referência.", "—", "—", "prazo",
                "instante de referência", ESTOQUE, "Só abertas.", "Nenhuma vencida: \"sem dados\".", "—");
        d("LISTA_PENDENCIAS", "Lista operacional de pendências abertas (nominal)", "pendências abertas ordenadas por prazo, "
                + "com caso, setor, etapa, motivo atual, ação registrada, responsável e prazo", "pendências",
                "Só para perfis com acesso nominal (não para a Direção); limite técnico — acima dele a lista NÃO é "
                        + "exibida (nunca parcial).", "—", "—", "criada_em", "prazo", ESTOQUE,
                "Só abertas.", "—", "—");
        // ------------------------------------------------------------------ evolução
        d("EVOLUCAO", "Comparação entre períodos", "mesmas métricas no período escolhido e no período anterior de MESMA "
                + "quantidade de dias locais, ambos ENCERRADOS (fim até ontem no fuso da unidade); variação absoluta (na "
                + "unidade da métrica: contagem, minutos, ou pontos percentuais para métricas em %) e variação relativa "
                + "em % (ausente quando o valor anterior é zero; não se aplica a métricas que já são percentuais)",
                "conforme a métrica", "Entradas, encerramentos (e transferências), permanência dos encerrados (mediana), "
                        + "pendências criadas/encerradas (até o prazo) e tempo e inícios de bloqueio por categoria — cada "
                        + "uma com o seu verbete neste resultado.",
                "Permanência: exclui encerramento administrativo. Pendências \"até o prazo\": usa o ÚLTIMO prazo "
                        + "registrado.", "Cada métrica com sua base.", "conforme a métrica", "conforme a métrica",
                PERIODO + " Só períodos encerrados: um período que inclui hoje está incompleto e não é comparável. Os "
                        + "dois períodos usam as mesmas definições, no mesmo instantâneo do banco; com horário de verão, "
                        + "a duração em horas pode diferir (informada no resultado).", "Alertas não são comparados (sem histórico).",
                "Valor anterior zero: variação relativa ausente.", "Como nas seções correspondentes.");
        d("PERMANENCIA", "Permanência dos encerrados (evolução)", "mediana (percentil 50 contínuo) de (encerrado_em − "
                + "entrada_em) dos episódios encerrados no período; n = encerrados incluídos", P,
                "Episódios encerrados no período; com filtro de setor, o setor FINAL.",
                "Desfecho \"encerramento administrativo\" (cancelamento/registro indevido) — proposta, como na etapa 7.",
                "Encerrados incluídos (n).", "entrada_em", "encerrado_em", PERIODO, "Só encerrados.",
                "Sem encerrados incluídos: \"sem dados\" (nunca zero).", "Um episódio conta uma vez.");
        // ------------------------------------------------------------------ qualidade
        d("ATUALIDADE", "Tempo desde o último registro", "referência − último registro (registrado_em) na linha do "
                + "tempo dos abertos: média, mediana, P90, máximo; quantidade = com registro; base = abertos", P,
                "Episódios abertos.", "—", "Abertos (base).", "último registro", "instante de referência", ESTOQUE,
                "Só abertos.", "Sem nenhum registro: fora da estatística, contado na diferença base − quantidade.",
                "—");
        d("SEM_ATUALIZACAO_REGRA", "Abertos além do critério configurado", "por regra ATIVA \"sem atualização\" "
                + "(minutos = limite): abertos com referência − último registro ≥ limite", "episódios",
                "Abertos (na etapa da regra, se houver).", "—", "Abertos sujeitos à regra.", "último registro",
                "instante de referência", ESTOQUE + " Nenhum prazo oficial é presumido: sem regra configurada, não "
                        + "há contagem.", "Só abertos.", "Sem regra: seção vazia.", "—");
        d("REGISTROS_RETROATIVOS", "Registros retroativos", "registros FEITOS no período (registrado_em); parte = com "
                + "ajuste manual (fato informado antes do registro); atraso (registro − fato) dos retroativos", P,
                "Eventos da linha do tempo com registrado_em no período. Com filtro de setor: o setor em vigor no INSTANTE "
                        + "DO FATO (ocorrido_em), pela linha do tempo — nunca o setor atual; a transferência pertence ao "
                        + "setor de destino.", "Com filtro de setor: fatos sem setor determinável (ver SETOR_NAO_ATRIBUIDO).",
                "Registros no período.", "ocorrido_em (atribuição do setor)", "registrado_em (período)", PERIODO
                        + " Dois marcos distintos: o PERÍODO é o do registro; o SETOR é o do fato.", "Abertos e encerrados.",
                "—", "Cada registro uma vez.");
        d("CAUSA_EM_INVESTIGACAO_AGORA", "Bloqueios com causa ainda não definida", "abertos bloqueados com categoria "
                + "NAO_DEFINIDA; base = abertos bloqueados", "episódios", "Abertos bloqueados.", "—", "Abertos bloqueados.",
                "—", "—", ESTOQUE, "Só abertos.", "—", "—");
        d("BLOQUEIOS_INICIADOS_SEM_CAUSA", "Bloqueios iniciados sem causa definida", "bloqueios INICIADOS no período "
                + "com categoria NAO_DEFINIDA no início; base = bloqueios iniciados", "intervalos",
                "Inícios dos intervalos de bloqueio normalizados (mesma definição de Gargalos e Evolução); com filtro de "
                        + "setor, o setor em que o bloqueio começou.",
                "Redefinição do mesmo motivo (ex.: só o detalhe mudou) não é novo bloqueio; bloqueio iniciado antes do "
                        + "período não conta.", "Bloqueios iniciados.", "início do intervalo (BLOQUEIO_DEFINIDO que o abriu)",
                "—", PERIODO, "Iniciados no período, abertos ou já removidos.", "—",
                "Remoção seguida de novo bloqueio = dois inícios.");
        d("SETOR_NAO_ATRIBUIDO", "Fatos sem setor determinável", "com filtro de setor: registros (REGISTROS) e inícios de "
                + "bloqueio (BLOQUEIOS_INICIADOS) do período sem setor determinável pela linha do tempo; base = todos "
                + "os do período, antes do filtro", "registros ou intervalos",
                "Fatos anteriores ao primeiro evento de setor do episódio (ex.: registro legado sem evento de abertura).",
                "—", "Fatos do período (todos os setores).", "—", "—", PERIODO,
                "Abertos e encerrados.", "Ficam FORA do filtro de setor — não são atribuídos ao setor atual.", "—");
        d("COBERTURA", "Cobertura de campos opcionais", "DESTINO/PROTOCOLO_EM_TRANSFERENCIA: abertos em etapa de "
                + "transferência com o campo registrado (quantidade) sobre a base — campo OPCIONAL: ausência não é falha",
                "episódios", "Abertos em etapas Aceito/Transporte ou que exigem protocolo.", "—", "Abertos nessas etapas.",
                "—", "—", ESTOQUE, "Só abertos.", "Base zero: ausente.", "—");
        d("LINHA_DO_TEMPO", "Cobertura da linha do tempo", "episódios no escopo com evento de abertura (quantidade) "
                + "sobre o total (base)", "episódios", "Escopo próprio: episódios abertos no instante de referência, que "
                        + "entraram no período ou que encerraram no período. Filtro de setor: setor ATUAL (abertos) ou "
                        + "FINAL (encerrados) — sem linha do tempo, não há como atribuir o setor da época.", "—",
                "Episódios no escopo.", "—", "—", PERIODO, "Abertos e encerrados.",
                "Sem linha do tempo, o episódio não entra nos tempos reconstruídos (gargalos).", "—");
    }

    private DicionarioRelatorios() {
    }

    private static void d(String codigo, String nome, String formula, String unidade, String populacao, String exclusoes,
                          String denominador, String marcoInicial, String marcoFinal, String campoTemporal,
                          String abertos, String ausentes, String repeticoes) {
        DEFINICOES.put(codigo, new DefinicaoIndicador(codigo, nome, REQ, nome + ".", formula, unidade, populacao, exclusoes,
                denominador, marcoInicial, marcoFinal, campoTemporal, abertos, ausentes, repeticoes, PERIODO, PROPOSTA));
    }

    public static List<DefinicaoIndicador> definicoes() {
        return List.copyOf(DEFINICOES.values());
    }

    public static Optional<DefinicaoIndicador> de(String codigo) {
        return Optional.ofNullable(DEFINICOES.get(codigo));
    }

    /** Seções de dados que cada relatório pode devolver (a tela mostra todas, inclusive vazias). */
    private static final Map<TipoRelatorio, List<String>> SECOES = Map.of(
            TipoRelatorio.RESUMO, List.of("ENTRADAS", "ENCERRAMENTOS", "ENCERRAMENTOS_TOTAL", "ABERTOS", "ABERTOS_ETAPA",
                    "ABERTOS_SETOR", "BLOQUEADOS_AGORA", "PENDENCIAS_ABERTAS", "CASOS_COM_VENCIDA", "CASOS_EM_ALERTA",
                    "ALERTA_REGRA"),
            TipoRelatorio.GARGALOS, List.of("ETAPA_CONCLUIDA", "ETAPA_EM_CURSO", "SETOR_TEMPO", "BLOQUEIO_CATEGORIA",
                    "BLOQUEIO_MOTIVO", "BLOQUEIO_CONCLUIDO", "BLOQUEIO_EM_CURSO", "PENDENCIA_CATEGORIA", "PENDENCIA_RESPONSAVEL"),
            TipoRelatorio.PENDENCIAS, List.of("CRIADAS_TOTAL", "CRIADAS", "ENCERRADAS", "ENCERRADAS_CATEGORIA", "ABERTAS_TOTAL",
                    "ABERTAS", "ABERTAS_CRITICIDADE", "ABERTAS_RESPONSAVEL", "VENCIDAS_ATRASO", "LISTA_PENDENCIAS"),
            TipoRelatorio.EVOLUCAO, List.of("EVOLUCAO", "ENTRADAS", "ENCERRAMENTOS", "PERMANENCIA", "PENDENCIAS_CRIADAS",
                    "PENDENCIAS_ENCERRADAS", "BLOQUEIO_MINUTOS", "BLOQUEIO_MINUTOS_CATEGORIA"),
            TipoRelatorio.QUALIDADE, List.of("ATUALIDADE", "ATUALIDADE_SETOR", "SEM_ATUALIZACAO_REGRA", "REGISTROS_RETROATIVOS",
                    "CAUSA_EM_INVESTIGACAO_AGORA", "BLOQUEIOS_INICIADOS_SEM_CAUSA", "SETOR_NAO_ATRIBUIDO",
                    "DESTINO_EM_TRANSFERENCIA", "PROTOCOLO_EM_TRANSFERENCIA", "LINHA_DO_TEMPO"));

    public static List<String> secoes(TipoRelatorio tipo) {
        return SECOES.get(tipo);
    }

    /**
     * Definições relevantes para um relatório (uma por verbete, na ordem das seções): vão DENTRO do
     * resultado, cobertas pela assinatura, para a tela, a impressão e o CSV não dependerem de outra
     * consulta ao dicionário.
     */
    public static List<DefinicaoIndicador> definicoesDe(TipoRelatorio tipo) {
        return secoes(tipo).stream().map(s -> verbeteDe(tipo, s)).distinct()
                .map(c -> de(c).orElseThrow(() -> new IllegalStateException("verbete ausente: " + c))).toList();
    }

    /** Verbete que define cada seção de dados (várias seções compartilham uma definição). */
    public static String verbeteDe(TipoRelatorio tipo, String secao) {
        if (tipo == TipoRelatorio.EVOLUCAO) {
            return switch (secao) {
                case "ENTRADAS" -> "ENTRADAS";
                case "ENCERRAMENTOS" -> "ENCERRAMENTOS";
                case "PERMANENCIA" -> "PERMANENCIA";
                case "PENDENCIAS_CRIADAS" -> "CRIADAS";
                case "PENDENCIAS_ENCERRADAS" -> "ENCERRADAS";
                case "BLOQUEIO_MINUTOS", "BLOQUEIO_MINUTOS_CATEGORIA" -> "BLOQUEIO_CATEGORIA";
                default -> "EVOLUCAO";
            };
        }
        return switch (secao) {
            case "ENCERRAMENTOS_TOTAL" -> "ENCERRAMENTOS";
            case "ALERTA_REGRA" -> "CASOS_EM_ALERTA";
            case "CRIADAS_TOTAL" -> "CRIADAS";
            case "ENCERRADAS_CATEGORIA" -> "ENCERRADAS";
            case "ABERTAS_TOTAL", "ABERTAS_CRITICIDADE", "ABERTAS_RESPONSAVEL" -> "ABERTAS";
            case "ATUALIDADE_SETOR" -> "ATUALIDADE";
            case "DESTINO_EM_TRANSFERENCIA", "PROTOCOLO_EM_TRANSFERENCIA" -> "COBERTURA";
            default -> secao;
        };
    }
}
