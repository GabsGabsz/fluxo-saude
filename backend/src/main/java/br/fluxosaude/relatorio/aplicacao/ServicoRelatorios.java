package br.fluxosaude.relatorio.aplicacao;

import br.fluxosaude.alerta.dominio.MotorDeAlertas;
import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.compartilhado.ConflitoDeEstadoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.relatorio.dominio.Canonico;
import br.fluxosaude.relatorio.dominio.DicionarioRelatorios;
import br.fluxosaude.relatorio.dominio.Limitacao;
import br.fluxosaude.relatorio.dominio.LinhaRelatorio;
import br.fluxosaude.relatorio.dominio.PendenciaOperacional;
import br.fluxosaude.relatorio.dominio.TipoRelatorio;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Relatórios gerenciais (extensão aprovada do projeto, issue #9 — não é requisito da ERS
 * original). Cada relatório é calculado UMA vez, num único instantâneo do banco (REPEATABLE READ
 * somente leitura), e devolvido com a assinatura do conjunto e um comprovante (HMAC). Tela,
 * impressão e CSV são gerados a partir DESSE mesmo resultado: a exportação nunca recalcula, então
 * uma gravação concorrente não pode fazer o arquivo divergir do que foi visto. A exportação é
 * registrada (auditoria, READ COMMITTED à parte) antes de o arquivo ser liberado.
 *
 * <p>Permissão: INDICADORES_VER (coordenação e direção). A lista operacional nominal de
 * pendências exige também EPISODIO_VER (a Direção nunca a recebe) e tem limite técnico — acima
 * dele não é exibida (nunca uma lista parcial). Nada aqui é causal, clínico ou ranking individual.
 */
public final class ServicoRelatorios {

    public static final int PERIODO_MAXIMO_DIAS = 366;
    public static final int LIMITE_LISTA = 2000;
    public static final int LIMITE_ALERTAS = 10_000;
    public static final Duration VALIDADE_COMPROVANTE = Duration.ofHours(2);
    public static final String CONSULTA_NOMINAL = "CONSULTA_RELATORIO_PENDENCIAS";

    public enum Formato { CSV, IMPRESSAO }

    public record Filtros(UUID setor, String setorNome, UUID etapa, String etapaNome, String categoria) {
    }

    public record Comparacao(LocalDate inicio, LocalDate fim, Instant inicioEm, Instant fimEm) {
    }

    /** @param motivo por que a lista não está disponível (perfil, limite) — nulo quando disponível */
    public record ListaPendencias(boolean disponivel, String motivo, int limite, List<PendenciaOperacional> itens) {
    }

    public record Resultado(TipoRelatorio tipo, String titulo, String versaoCalculo, UUID unidadeId, String unidadeNome,
                            String fuso, LocalDate inicio, LocalDate fim, Instant inicioEm, Instant fimEm, Instant referencia,
                            Instant geradoEm, Filtros filtros, Comparacao comparacao, List<LinhaRelatorio> linhas,
                            ListaPendencias listaPendencias, List<Limitacao> limitacoes, String cobertura,
                            Map<String, String> verbetes, String assinatura, String comprovante) {
    }

    public record Exportacao(long registro, String assinatura, Formato formato) {
    }

    private record Calculo(RepositorioRelatorios.Unidade unidade, Instant referencia, RepositorioRelatorios.Periodo periodo,
                           Filtros filtros, Comparacao comparacao, List<LinhaRelatorio> linhas, ListaPendencias lista,
                           List<Limitacao> limitacoes) {
    }

    private final TransacaoRelatorios transacao;
    private final RegistroRelatorios registro;
    private final TokenRelatorio comprovantes;
    private final Clock relogio;

    public ServicoRelatorios(TransacaoRelatorios transacao, RegistroRelatorios registro, TokenRelatorio comprovantes,
                             Clock relogio) {
        this.transacao = Objects.requireNonNull(transacao);
        this.registro = Objects.requireNonNull(registro);
        this.comprovantes = Objects.requireNonNull(comprovantes);
        this.relogio = Objects.requireNonNull(relogio);
    }

    // ------------------------------------------------------------------------------- consulta

    public Resultado consultar(UsuarioAutenticado u, ContextoOrigem origem, TipoRelatorio tipo, LocalDate inicio,
                               LocalDate fim, UUID setor, UUID etapa, String categoria) {
        AcessoNegadoException.exigir(u, Permissao.INDICADORES_VER);
        Objects.requireNonNull(tipo);
        RegraVioladaException.exigir(inicio != null && fim != null, "PERIODO_OBRIGATORIO", "Informe o início e o fim do período");
        RegraVioladaException.exigir(!fim.isBefore(inicio), "PERIODO_INVALIDO", "O fim do período é anterior ao início");
        RegraVioladaException.exigir(ChronoUnit.DAYS.between(inicio, fim) < PERIODO_MAXIMO_DIAS, "PERIODO_LONGO",
                "O período pode ter no máximo " + PERIODO_MAXIMO_DIAS + " dias");
        RegraVioladaException.exigir(etapa == null || tipo.aceitaEtapa(), "FILTRO_NAO_SUPORTADO",
                "Este relatório não tem filtro de etapa");
        RegraVioladaException.exigir(categoria == null || tipo.aceitaCategoria(), "FILTRO_NAO_SUPORTADO",
                "Este relatório não tem filtro de categoria");
        RegraVioladaException.exigir(categoria == null || categoriaValida(categoria), "CATEGORIA_INVALIDA",
                "Categoria de bloqueio inválida");
        boolean nominal = tipo == TipoRelatorio.PENDENCIAS && u.pode(Permissao.EPISODIO_VER);
        UUID unidadeId = u.unidadeAtiva();

        Calculo c = transacao.executar(u, origem, r -> calcular(r, tipo, unidadeId, inicio, fim, setor, etapa, categoria,
                nominal));
        Instant geradoEm = relogio.instant();
        String assinatura = Canonico.sha256(canonico(tipo, c, inicio, fim, geradoEm));
        if (!c.lista().itens().isEmpty()) {
            // Leitura nominal registrada (RNF-002) ANTES de devolver; transação READ COMMITTED própria.
            Set<UUID> episodios = new LinkedHashSet<>();
            c.lista().itens().forEach(i -> episodios.add(i.episodioId()));
            Map<String, Object> dados = new LinkedHashMap<>();
            dados.put("assinatura", assinatura);
            dados.put("pendencias", c.lista().itens().size());
            registro.registrarConsultaNominal(u, origem, tipo.name(), episodios, dados);
        }
        String comprovante = comprovantes.emitir(new TokenRelatorio.Dados(assinatura, tipo.name(), unidadeId, u.usuarioId(),
                inicio, fim, c.filtros().setor(), c.filtros().etapa(), c.filtros().categoria(), c.referencia(), geradoEm,
                !c.lista().itens().isEmpty(), c.linhas().size()));
        return new Resultado(tipo, tipo.titulo(), DicionarioRelatorios.VERSAO, unidadeId, c.unidade().nome(),
                c.unidade().fuso(), inicio, fim, c.periodo().inicio(), c.periodo().fim(), c.referencia(), geradoEm, c.filtros(),
                c.comparacao(), c.linhas(), c.lista(), c.limitacoes(), cobertura(c.unidade().nome()), verbetes(tipo, c),
                assinatura, comprovante);
    }

    private Calculo calcular(RepositorioRelatorios r, TipoRelatorio tipo, UUID unidadeId, LocalDate inicio, LocalDate fim,
                             UUID setor, UUID etapa, String categoria, boolean nominal) {
        Instant agora = relogio.instant();
        RepositorioRelatorios.Unidade unidade = r.unidade(unidadeId);
        LocalDate hoje = LocalDate.ofInstant(agora, ZoneId.of(unidade.fuso()));
        RegraVioladaException.exigir(!fim.isAfter(hoje), "PERIODO_FUTURO", "O período não pode terminar depois de hoje");
        String setorNome = null;
        String etapaNome = null;
        if (setor != null) {
            setorNome = r.nomeSetor(setor).orElseThrow(
                    () -> new RegraVioladaException("SETOR_INVALIDO", "Setor inválido para a unidade ativa"));
        }
        if (etapa != null) {
            etapaNome = r.nomeEtapa(etapa).orElseThrow(
                    () -> new RegraVioladaException("ETAPA_INVALIDA", "Etapa inválida para a unidade ativa"));
        }
        Filtros filtros = new Filtros(setor, setorNome, etapa, etapaNome, categoria);
        RepositorioRelatorios.Periodo p = r.periodo(unidade.fuso(), inicio, fim);
        List<LinhaRelatorio> linhas = new ArrayList<>();
        List<Limitacao> lim = new ArrayList<>(comuns(setor != null));
        ListaPendencias lista = new ListaPendencias(false, tipo == TipoRelatorio.PENDENCIAS
                ? "Perfil sem acesso nominal: só agregados." : null, LIMITE_LISTA, List.of());
        Comparacao comparacao = null;
        switch (tipo) {
            case RESUMO -> {
                linhas.addAll(r.resumo(unidadeId, p, agora, setor));
                alertasAgora(r, setor, agora, linhas, lim);
                long semSetor = valor(linhas, "ENTRADAS", LinhaRelatorio::parte);
                if (setor != null && semSetor > 0) {
                    lim.add(new Limitacao("ENTRADAS_SEM_SETOR", "ENTRADAS", semSetor + " entrada(s) no período sem setor de "
                            + "entrada na linha do tempo não podem ser atribuídas a um setor e ficaram fora do filtro."));
                }
            }
            case GARGALOS -> {
                linhas.addAll(r.gargalos(unidadeId, p, agora, setor, etapa, categoria));
                lim.add(new Limitacao("CONCLUIDO_X_EM_CURSO", "ETAPA_CONCLUIDA", "Durações de etapas e bloqueios CONCLUÍDOS no "
                        + "período ficam separadas da idade das esperas ainda EM CURSO (que não terminaram)."));
                lim.add(new Limitacao("ATRIBUICAO_TEMPORAL", "SETOR_TEMPO", "Tempos atribuídos pela linha do tempo ao setor e "
                        + "à etapa em que ocorreram (não ao setor atual). Com filtro de setor, etapas e bloqueios concluídos "
                        + "contam pelo setor em que começaram; os minutos bloqueados contam só o trecho vivido no setor."));
                lim.add(new Limitacao("SEM_LINHA_DO_TEMPO", "ETAPA_EM_CURSO", "Episódios sem evento de abertura na linha do tempo "
                        + "não entram nos tempos reconstruídos (veja o relatório de Qualidade)."));
                lim.add(new Limitacao("RESPONSAVEL_ATUAL", "PENDENCIA_RESPONSAVEL", "Tipo de responsável ATUAL da pendência: "
                        + "trocas anteriores não guardam o responsável anterior."));
            }
            case PENDENCIAS -> {
                linhas.addAll(r.pendencias(unidadeId, p, agora, setor, categoria));
                lim.add(new Limitacao("PRAZO_ULTIMO", "ENCERRADAS", "\"No prazo\" compara o encerramento com o ÚLTIMO prazo "
                        + "registrado; as alterações de prazo aparecem em \"criadas com prazo alterado\"."));
                lim.add(new Limitacao("RESPONSAVEL_ATUAL", "ABERTAS_RESPONSAVEL", "Responsável ATUAL: trocas anteriores não "
                        + "guardam o responsável anterior."));
                lim.add(new Limitacao("ACAO_REGISTRADA", "LISTA_PENDENCIAS", "A ação é a descrição registrada pela equipe; "
                        + "nenhuma ação clínica é inferida."));
                if (nominal) {
                    List<PendenciaOperacional> itens = r.listaPendencias(unidadeId, agora, setor, categoria, LIMITE_LISTA + 1);
                    lista = itens.size() > LIMITE_LISTA
                            ? new ListaPendencias(false, "Mais de " + LIMITE_LISTA + " pendências abertas no filtro: a lista "
                                    + "não é exibida (nunca parcial). Refine os filtros; os totais acima estão completos.",
                                    LIMITE_LISTA, List.of())
                            : new ListaPendencias(true, null, LIMITE_LISTA, List.copyOf(itens));
                }
            }
            case EVOLUCAO -> {
                long dias = ChronoUnit.DAYS.between(inicio, fim) + 1;
                LocalDate antInicio = inicio.minusDays(dias);
                LocalDate antFim = inicio.minusDays(1);
                RepositorioRelatorios.Periodo pa = r.periodo(unidade.fuso(), antInicio, antFim);
                comparacao = new Comparacao(antInicio, antFim, pa.inicio(), pa.fim());
                r.metricasPeriodo(unidadeId, p, agora, setor).forEach(l -> linhas.add(l.comPeriodo("ATUAL")));
                r.metricasPeriodo(unidadeId, pa, agora, setor).forEach(l -> linhas.add(l.comPeriodo("ANTERIOR")));
                RepositorioRelatorios.MudancasRegras m = r.mudancasRegras(unidadeId, pa.inicio(), p.fim());
                lim.add(new Limitacao("ALERTAS_NAO_COMPARADOS", null, "Alertas operacionais não são comparados entre "
                        + "períodos: não há histórico de alertas e eles não são reconstruídos com as regras atuais."));
                if (m.alteracoes() > 0) {
                    lim.add(new Limitacao("REGRAS_ALTERADAS", null, m.alteracoes() + " alteração(ões) de regras de alerta "
                            + "registradas entre o início do período anterior e o fim do atual."));
                }
                lim.add(new Limitacao("HISTORICO_DE_REGRAS", null, m.historicoDesde() == null
                        ? "Sem histórico de versões de regras de alerta nesta unidade."
                        : "O histórico de versões das regras de alerta começa em " + m.historicoDesde()
                                + "; alterações anteriores não são conhecidas."));
                lim.add(new Limitacao("PERIODOS_EQUIVALENTES", null, "Os dois períodos têm o mesmo número de dias locais ("
                        + dias + "); com horário de verão, a duração em horas pode diferir em uma hora."));
            }
            case QUALIDADE -> {
                linhas.addAll(r.qualidade(unidadeId, p, agora, setor));
                lim.add(new Limitacao("OPCIONAL", "DESTINO_EM_TRANSFERENCIA", "Destino e protocolo são campos OPCIONAIS fora das "
                        + "etapas que os exigem: a ausência é falta de informação, não falha do registro."));
                lim.add(new Limitacao("FALTA_DE_INFORMACAO", null, "Ausência de registro (por exemplo, nenhum bloqueio ou "
                        + "nenhuma pendência) não significa ausência de problema."));
                if (linhas.stream().noneMatch(l -> "SEM_ATUALIZACAO_REGRA".equals(l.secao()))) {
                    lim.add(new Limitacao("SEM_CRITERIO_CONFIGURADO", "SEM_ATUALIZACAO_REGRA", "Nenhuma regra \"sem "
                            + "atualização\" ativa: não há critério configurado para contar registros desatualizados (só "
                            + "os tempos desde o último registro são mostrados)."));
                }
            }
            default -> throw new IllegalStateException(tipo.name());
        }
        linhas.sort(ORDEM);
        return new Calculo(unidade, agora, p, filtros, comparacao, List.copyOf(linhas), lista, List.copyOf(lim));
    }

    private static final Comparator<LinhaRelatorio> ORDEM = Comparator
            .comparing((LinhaRelatorio l) -> l.periodo() == null ? "" : l.periodo())
            .thenComparing(LinhaRelatorio::secao)
            .thenComparing(l -> l.chave() == null ? "" : l.chave());

    /** Casos em alerta AGORA (mesmo motor da Torre, regras ativas); nunca reconstrói alertas passados. */
    private static void alertasAgora(RepositorioRelatorios r, UUID setor, Instant agora, List<LinhaRelatorio> linhas,
                                     List<Limitacao> lim) {
        List<RegraAlerta> regras = r.regrasAtivas();
        if (regras.isEmpty()) {
            lim.add(new Limitacao("SEM_REGRAS", "CASOS_EM_ALERTA", "Nenhuma regra de alerta ativa: \"casos em alerta\" não é "
                    + "calculado (não significa zero)."));
            return;
        }
        List<SituacaoEpisodio> abertos = r.situacoesAbertas(setor, LIMITE_ALERTAS + 1);
        if (abertos.size() > LIMITE_ALERTAS) {
            lim.add(new Limitacao("ALERTAS_INDISPONIVEIS", "CASOS_EM_ALERTA", "Mais episódios abertos que o limite técnico do "
                    + "cálculo de alertas: indicador indisponível (nenhum valor parcial)."));
            return;
        }
        Map<UUID, Set<UUID>> porRegra = new HashMap<>();
        Set<UUID> algum = new HashSet<>();
        for (SituacaoEpisodio s : abertos) {
            MotorDeAlertas.avaliar(s, regras, agora).forEach(a -> {
                porRegra.computeIfAbsent(a.regraId(), k -> new HashSet<>()).add(s.episodioId());
                algum.add(s.episodioId());
            });
        }
        linhas.add(new LinhaRelatorio(null, "CASOS_EM_ALERTA", null, null, null, (long) algum.size(), null,
                (long) abertos.size(), null, null, null, null, null, null));
        for (RegraAlerta g : regras) {
            linhas.add(new LinhaRelatorio(null, "ALERTA_REGRA", g.id().toString(), g.nome(), "v" + g.versao(),
                    (long) porRegra.getOrDefault(g.id(), Set.of()).size(), null, (long) abertos.size(), null, null, null,
                    null, null, null));
        }
        lim.add(new Limitacao("ALERTAS_ATUAIS", "CASOS_EM_ALERTA", "Alertas só no instante de referência, pelas regras ativas "
                + "agora; alertas passados não são reconstruídos."));
    }

    /** Seção → verbete do dicionário que a define (para a tela, a impressão e o CSV). */
    private static Map<String, String> verbetes(TipoRelatorio tipo, Calculo c) {
        Map<String, String> m = new java.util.TreeMap<>();
        c.linhas().forEach(l -> m.put(l.secao(), DicionarioRelatorios.verbeteDe(tipo, l.secao())));
        if (tipo == TipoRelatorio.PENDENCIAS) {
            m.put("LISTA_PENDENCIAS", "LISTA_PENDENCIAS");
        }
        return m;
    }

    private static List<Limitacao> comuns(boolean comSetor) {
        List<Limitacao> l = new ArrayList<>();
        l.add(new Limitacao("MOTIVO_NAO_E_CAUSA", null, "Motivos, categorias e associações são registros da equipe, não "
                + "causa comprovada."));
        l.add(new Limitacao("SEM_RANKING", null, "Sem ranking individual ou avaliação de produtividade; indicadores "
                + "operacionais, não clínicos."));
        l.add(new Limitacao("FORMULAS_PROPOSTAS", null, "Fórmulas propostas, pendentes de validação institucional (V-09)."));
        l.add(new Limitacao("GRUPOS_PEQUENOS", null, "Contagens pequenas (sobretudo com filtros combinados) podem permitir "
                + "reidentificação; a política institucional de supressão está pendente (V-08) — nenhum limite é aplicado."));
        if (comSetor) {
            l.add(new Limitacao("FILTRO_DE_SETOR", null, "Filtro de setor: eventos pelo setor do episódio no instante do fato "
                    + "(linha do tempo); estoque pelo setor atual; encerramentos pelo setor final."));
        }
        return l;
    }

    private static String cobertura(String unidade) {
        return "Operação registrada pela equipe na unidade " + unidade + " e no período selecionados, com referências aos "
                + "sistemas existentes informadas manualmente. Não representa toda a rede de saúde nem, automaticamente, "
                + "os dados dos sistemas externos (não há integração automática).";
    }

    private static long valor(List<LinhaRelatorio> linhas, String secao, java.util.function.Function<LinhaRelatorio, Long> f) {
        return linhas.stream().filter(l -> secao.equals(l.secao()) && l.chave() == null).map(f).filter(Objects::nonNull)
                .findFirst().orElse(0L);
    }

    private static boolean categoriaValida(String c) {
        try {
            CategoriaBloqueio.valueOf(c);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Forma canônica do conjunto (tudo o que a tela, a impressão e o CSV mostram). */
    private static String canonico(TipoRelatorio tipo, Calculo c, LocalDate inicio, LocalDate fim, Instant geradoEm) {
        List<String> l = new ArrayList<>();
        l.add(Canonico.linha("relatorio", tipo, DicionarioRelatorios.VERSAO, c.unidade().id(), c.unidade().nome(),
                c.unidade().fuso(), inicio, fim, c.periodo().inicio(), c.periodo().fim(), c.referencia(), geradoEm));
        l.add(Canonico.linha("filtros", c.filtros().setor(), c.filtros().setorNome(), c.filtros().etapa(),
                c.filtros().etapaNome(), c.filtros().categoria()));
        if (c.comparacao() != null) {
            l.add(Canonico.linha("comparacao", c.comparacao().inicio(), c.comparacao().fim(), c.comparacao().inicioEm(),
                    c.comparacao().fimEm()));
        }
        for (LinhaRelatorio x : c.linhas()) {
            l.add(Canonico.linha("linha", x.periodo(), x.secao(), x.chave(), x.rotulo(), x.grupo(), x.quantidade(), x.parte(),
                    x.base(), x.episodios(), x.minutos(), x.media(), x.mediana(), x.p90(), x.maximo()));
        }
        l.add(Canonico.linha("lista", c.lista().disponivel(), c.lista().motivo(), c.lista().limite()));
        for (PendenciaOperacional p : c.lista().itens()) {
            l.add(Canonico.linha("pendencia", p.pendenciaId(), p.episodioId(), p.pacienteNome(), p.setorNome(), p.etapaNome(),
                    p.motivoDescricao(), p.descricao(), p.categoria(), p.criticidade(), p.responsavelTipo(),
                    p.responsavelNome(), p.prazo(), p.criadaEm(), p.vencida()));
        }
        for (Limitacao x : c.limitacoes()) {
            l.add(Canonico.linha("limitacao", x.codigo(), x.secao(), x.texto()));
        }
        return Canonico.juntar(l);
    }

    // ------------------------------------------------------------------------------- exportação

    /**
     * Registra a exportação (CSV) ou a impressão SOLICITADA do conjunto identificado pelo
     * comprovante, depois de conferi-lo (autêntico, do mesmo usuário e unidade, dentro da
     * validade) e as permissões VIGENTES. Só depois disso a tela libera o arquivo ou a impressão.
     */
    public Exportacao registrarExportacao(UsuarioAutenticado u, ContextoOrigem origem, String comprovante, Formato formato) {
        AcessoNegadoException.exigir(u, Permissao.INDICADORES_VER);
        Objects.requireNonNull(formato);
        TokenRelatorio.Dados d = comprovantes.verificar(comprovante).orElseThrow(
                () -> new RegraVioladaException("COMPROVANTE_INVALIDO", "Comprovante do relatório inválido. Recalcule."));
        RegraVioladaException.exigir(d.usuarioId().equals(u.usuarioId()) && d.unidadeId().equals(u.unidadeAtiva()),
                "COMPROVANTE_INVALIDO", "Comprovante de outro usuário ou unidade. Recalcule o relatório.");
        if (relogio.instant().isAfter(d.emitidoEm().plus(VALIDADE_COMPROVANTE))) {
            throw new ConflitoDeEstadoException("RELATORIO_EXPIRADO", "O relatório foi calculado há mais de "
                    + VALIDADE_COMPROVANTE.toHours() + " h. Recalcule antes de exportar.");
        }
        if (d.nominal()) {
            AcessoNegadoException.exigir(u, Permissao.EPISODIO_VER);
        }
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("formato", formato.name());
        dados.put("assinatura", d.assinatura());
        dados.put("inicio", d.inicio().toString());
        dados.put("fim", d.fim().toString());
        dados.put("referencia", d.referencia().toString());
        dados.put("calculadoEm", d.emitidoEm().toString());
        if (d.setor() != null) {
            dados.put("setor", d.setor().toString());
        }
        if (d.etapa() != null) {
            dados.put("etapa", d.etapa().toString());
        }
        if (d.categoria() != null) {
            dados.put("categoria", d.categoria());
        }
        dados.put("linhas", d.linhas());
        dados.put("nominal", d.nominal());
        String acao = formato == Formato.CSV ? "RELATORIO_EXPORTADO" : "RELATORIO_IMPRESSAO_SOLICITADA";
        long id = registro.registrarExportacao(u, origem, acao, d.tipo(), dados);
        return new Exportacao(id, d.assinatura(), formato);
    }
}
