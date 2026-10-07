package br.fluxosaude.indicador.aplicacao;

import br.fluxosaude.alerta.dominio.MotorDeAlertas;
import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.indicador.dominio.DefinicaoIndicador;
import br.fluxosaude.indicador.dominio.DicionarioIndicadores;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Indicadores (M07: RF-019, RF-020, CA-09) e dicionário (RF-039). Separa o RETRATO ATUAL (agora)
 * das métricas HISTÓRICAS de um período. Tudo é agregado no banco sobre todos os registros da
 * unidade — nunca a partir de uma lista paginada. Exige INDICADORES_VER; a resposta não contém
 * nome, CNS ou identificador de paciente/episódio (perfil Direção).
 */
public final class ServicoIndicadores {

    public static final int PERIODO_MAXIMO_DIAS = 366;
    /** Limite técnico do cálculo de "acima dos limites agora"; acima disso o item fica indisponível. */
    public static final int LIMITE_RETRATO = 10_000;

    public record Percentual(Double valor) {
        static Percentual de(long parte, long todo) {
            return new Percentual(todo == 0 ? null : 100.0 * parte / todo);
        }
    }

    public record LimiteNoPeriodo(RepositorioIndicadores.AcimaDoLimite regra, Percentual percentual) {
    }

    public record MotivoNoPeriodo(RepositorioIndicadores.Motivo motivo, Percentual percentualDoTempo) {
    }

    public record Historico(RepositorioIndicadores.Duracoes permanencia, List<LimiteNoPeriodo> acimaDosLimites,
                            List<RepositorioIndicadores.Desfecho> desfechos, long transferencias,
                            RepositorioIndicadores.Duracoes solicitacaoAceite, RepositorioIndicadores.Duracoes aceiteSaida,
                            List<MotivoNoPeriodo> motivos, double minutosBloqueadosTotal,
                            List<RepositorioIndicadores.Dia> volumeDiario) {
    }

    public record AcimaAgora(UUID regraId, String regraNome, int regraVersao, String tipo, long episodios) {
    }

    /** @param acimaDosLimitesDisponivel falso quando há mais abertos que o limite técnico (nunca parcial) */
    public record Retrato(Instant agora, List<RepositorioIndicadores.ItemRetrato> itens, boolean regrasConfiguradas,
                          boolean acimaDosLimitesDisponivel, List<AcimaAgora> acimaDosLimites) {
    }

    public record Resultado(Instant agora, String fuso, LocalDate inicio, LocalDate fim, Instant inicioEm, Instant fimEm,
                            UUID setor, Retrato retrato, Historico historico) {
    }

    private final TransacaoIndicadores transacao;
    private final Clock relogio;

    public ServicoIndicadores(TransacaoIndicadores transacao, Clock relogio) {
        this.transacao = Objects.requireNonNull(transacao);
        this.relogio = Objects.requireNonNull(relogio);
    }

    public List<DefinicaoIndicador> dicionario(UsuarioAutenticado u) {
        AcessoNegadoException.exigir(u, Permissao.INDICADORES_VER);
        return DicionarioIndicadores.definicoes();
    }

    public Resultado consultar(UsuarioAutenticado u, ContextoOrigem origem, LocalDate inicio, LocalDate fim, UUID setor) {
        AcessoNegadoException.exigir(u, Permissao.INDICADORES_VER);
        RegraVioladaException.exigir(inicio != null && fim != null, "PERIODO_OBRIGATORIO", "Informe o início e o fim do período");
        RegraVioladaException.exigir(!fim.isBefore(inicio), "PERIODO_INVALIDO", "O fim do período é anterior ao início");
        RegraVioladaException.exigir(ChronoUnit.DAYS.between(inicio, fim) < PERIODO_MAXIMO_DIAS, "PERIODO_LONGO",
                "O período pode ter no máximo " + PERIODO_MAXIMO_DIAS + " dias");
        UUID unidade = u.unidadeAtiva();
        return transacao.executar(u, origem, r -> {
            Instant agora = relogio.instant();
            String fuso = r.fusoDaUnidade(unidade);
            LocalDate hoje = LocalDate.ofInstant(agora, ZoneId.of(fuso));
            RegraVioladaException.exigir(!fim.isAfter(hoje), "PERIODO_FUTURO", "O período não pode terminar depois de hoje");
            if (setor != null) {
                RegraVioladaException.exigir(r.setorDaUnidade(setor), "SETOR_INVALIDO", "Setor inválido para a unidade ativa");
            }
            RepositorioIndicadores.Periodo p = r.periodo(fuso, inicio, fim);
            return new Resultado(agora, fuso, inicio, fim, p.inicio(), p.fim(), setor,
                    retrato(r, unidade, setor, agora), historico(r, unidade, fuso, inicio, fim, p, setor, agora));
        });
    }

    private static Historico historico(RepositorioIndicadores r, UUID unidade, String fuso, LocalDate inicio, LocalDate fim,
                                       RepositorioIndicadores.Periodo p, UUID setor, Instant agora) {
        List<RepositorioIndicadores.Desfecho> desfechos = r.desfechos(unidade, p, setor);
        long transferencias = desfechos.stream().filter(d -> "TRANSFERENCIA".equals(d.desfecho()))
                .mapToLong(RepositorioIndicadores.Desfecho::quantidade).sum();
        List<RepositorioIndicadores.Motivo> motivos = r.motivos(unidade, p, setor, agora);
        double total = motivos.stream().mapToDouble(RepositorioIndicadores.Motivo::minutos).sum();
        return new Historico(r.permanencia(unidade, p, setor),
                r.acimaDosLimites(unidade, p, setor).stream()
                        .map(a -> new LimiteNoPeriodo(a, Percentual.de(a.acima(), a.populacao()))).toList(),
                desfechos, transferencias, r.solicitacaoAceite(unidade, p, setor), r.aceiteSaida(unidade, p, setor),
                motivos.stream().map(m -> new MotivoNoPeriodo(m, total > 0 ? new Percentual(100.0 * m.minutos() / total)
                        : new Percentual(null))).toList(),
                total, r.volumeDiario(unidade, fuso, inicio, fim, setor));
    }

    private static Retrato retrato(RepositorioIndicadores r, UUID unidade, UUID setor, Instant agora) {
        List<RegraAlerta> regras = r.regrasAtivas();
        List<RepositorioIndicadores.ItemRetrato> itens = r.retrato(unidade, setor, agora);
        if (regras.isEmpty()) {
            return new Retrato(agora, itens, false, false, List.of());
        }
        List<SituacaoEpisodio> abertos = r.situacoesAbertas(setor, LIMITE_RETRATO + 1);
        if (abertos.size() > LIMITE_RETRATO) {
            return new Retrato(agora, itens, true, false, List.of());
        }
        Map<UUID, Set<UUID>> episodiosPorRegra = new HashMap<>();
        for (SituacaoEpisodio s : abertos) {
            MotorDeAlertas.avaliar(s, regras, agora).forEach(a ->
                    episodiosPorRegra.computeIfAbsent(a.regraId(), k -> new HashSet<>()).add(s.episodioId()));
        }
        return new Retrato(agora, itens, true, true, regras.stream().map(g -> new AcimaAgora(g.id(), g.nome(), g.versao(),
                g.tipo().name(), episodiosPorRegra.getOrDefault(g.id(), Set.of()).size())).toList());
    }

    /** Tipos de regra cujo limite se aplica à permanência (usado no dicionário/tela). */
    public static boolean regraDePermanencia(RegraAlerta r) {
        return r.tipo() == TipoRegraAlerta.TEMPO_TOTAL && r.etapaId() == null;
    }
}
