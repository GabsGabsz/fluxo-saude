package br.fluxosaude.alerta.aplicacao;

import static br.fluxosaude.compartilhado.RegraVioladaException.exigir;

import br.fluxosaude.alerta.dominio.Alerta;
import br.fluxosaude.alerta.dominio.MotorDeAlertas;
import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Alertas operacionais e "Pacientes travados" (M04, RF-011, RF-018, RF-022, RF-027).
 *
 * <p>Os alertas são CALCULADOS a cada leitura, pelo relógio do servidor, a partir das regras
 * ativas da unidade (parâmetros institucionais, RN-006/RN-014) e do estado dos episódios
 * ABERTOS (RN-008). Não alteram prioridade clínica, classificação de risco nem o episódio
 * (RN-007/RN-013). A ciência registra "estou ciente" de uma ocorrência e não encerra pendência.
 * Sem regras ativas (situação inicial), nada é lido nem calculado.
 */
public final class ServicoAlertas {

    public static final int LIMITE_MONITORADOS = 2000;

    /** Alerta com a ciência (se houver). */
    public record AlertaVisto(Alerta alerta, RepositorioAlertas.Ciencia ciencia) {
    }

    /** Caso travado: episódio aberto com ao menos um alerta. */
    public record CasoTravado(RepositorioAlertas.EpisodioMonitorado episodio, List<AlertaVisto> alertas) {
        /** Primeiro limite atingido — ordena os casos pelo tempo além do limite (não é prioridade clínica). */
        public Instant primeiroAtingidoEm() {
            return alertas.get(0).alerta().atingidoEm();
        }
    }

    public record Travados(Instant agora, List<CasoTravado> itens, boolean truncado) {
    }

    public record AlertasDosEpisodios(Instant agora, Map<UUID, List<AlertaVisto>> porEpisodio) {
    }

    public record NovaRegra(String nome, TipoRegraAlerta tipo, UUID etapaId, CategoriaBloqueio categoria,
                            Duration limite, String acaoEsperada) {
    }

    public record AlteracaoRegra(String nome, UUID etapaId, CategoriaBloqueio categoria, Duration limite,
                                 String acaoEsperada, boolean ativa) {
    }

    /** Ocorrência indicada pelo cliente (a versão da regra é a vigente, conferida no servidor). */
    public record PedidoCiencia(UUID episodioId, UUID regraId, Instant referenciaEm, UUID pendenciaId) {
    }

    private final TransacaoAlertas transacao;
    private final Clock relogio;
    private final Supplier<UUID> ids;

    public ServicoAlertas(TransacaoAlertas transacao, Clock relogio, Supplier<UUID> ids) {
        this.transacao = Objects.requireNonNull(transacao);
        this.relogio = Objects.requireNonNull(relogio);
        this.ids = Objects.requireNonNull(ids);
    }

    // ------------------------------------------------------------------- leitura

    /** RF-018 / CA-06: só episódios abertos que violam ao menos uma regra ativa. */
    public Travados travados(UsuarioAutenticado u, ContextoOrigem origem) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_VER);
        Instant agora = relogio.instant();
        return transacao.executar(u, origem, r -> {
            List<RegraAlerta> regras = r.regras(true);
            if (regras.isEmpty()) {
                return new Travados(agora, List.of(), false);
            }
            List<RepositorioAlertas.EpisodioMonitorado> lidos = r.episodiosAbertos(LIMITE_MONITORADOS + 1);
            boolean truncado = lidos.size() > LIMITE_MONITORADOS;
            List<RepositorioAlertas.EpisodioMonitorado> episodios = truncado ? lidos.subList(0, LIMITE_MONITORADOS) : lidos;
            Map<UUID, List<AlertaVisto>> porEpisodio = avaliar(r, regras, episodios, agora);
            List<CasoTravado> casos = new ArrayList<>();
            for (RepositorioAlertas.EpisodioMonitorado e : episodios) {
                List<AlertaVisto> alertas = porEpisodio.get(e.situacao().episodioId());
                if (alertas != null) {
                    casos.add(new CasoTravado(e, alertas));
                }
            }
            casos.sort(Comparator.comparing(CasoTravado::primeiroAtingidoEm)
                    .thenComparing(c -> c.episodio().situacao().episodioId()));
            return new Travados(agora, List.copyOf(casos), truncado);
        });
    }

    /** RF-011 / CA-05: alertas dos episódios listados (ex.: os da Torre), nominal. */
    public AlertasDosEpisodios alertasDe(UsuarioAutenticado u, ContextoOrigem origem, Collection<UUID> episodioIds) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_VER);
        return calcular(u, origem, episodioIds);
    }

    /** Painel coletivo (pseudonimizado): só QUAIS dos episódios listados estão em alerta. */
    public Set<UUID> episodiosEmAlerta(UsuarioAutenticado u, ContextoOrigem origem, Collection<UUID> episodioIds) {
        AcessoNegadoException.exigir(u, Permissao.PAINEL_COLETIVO_VER);
        return Set.copyOf(calcular(u, origem, episodioIds).porEpisodio().keySet());
    }

    /** Regras da unidade: quem configura e quem acompanha os casos (para entender o alerta). */
    public List<RegraAlerta> regras(UsuarioAutenticado u, ContextoOrigem origem) {
        if (!u.pode(Permissao.CONFIGURACAO_GERENCIAR)) {
            AcessoNegadoException.exigir(u, Permissao.EPISODIO_VER);
        }
        return transacao.executar(u, origem, r -> r.regras(false));
    }

    // ------------------------------------------------------------------- configuração (RF-027)

    public RegraAlerta criarRegra(UsuarioAutenticado u, ContextoOrigem origem, NovaRegra n) {
        AcessoNegadoException.exigir(u, Permissao.CONFIGURACAO_GERENCIAR);
        Objects.requireNonNull(n, "regra");
        RegraAlerta regra = new RegraAlerta(ids.get(), n.nome(), n.tipo(), n.etapaId(), n.categoria(), n.limite(),
                n.acaoEsperada(), true, 0);
        return transacao.executar(u, origem, r -> {
            exigirEtapa(r, regra.etapaId());
            r.inserirRegra(regra);
            return regra;
        });
    }

    /** Tipo e unidade não mudam; desativar é {@code ativa = false} (não há exclusão). */
    public RegraAlerta alterarRegra(UsuarioAutenticado u, ContextoOrigem origem, UUID id, int versaoLida,
                                    AlteracaoRegra a) {
        AcessoNegadoException.exigir(u, Permissao.CONFIGURACAO_GERENCIAR);
        Objects.requireNonNull(a, "alteração");
        return transacao.executar(u, origem, r -> {
            RegraAlerta atual = r.regra(id).orElseThrow(() -> new RecursoNaoEncontradoException("Regra de alerta"));
            if (atual.versao() != versaoLida) {
                throw new ConflitoDeVersaoException();
            }
            RegraAlerta nova = new RegraAlerta(id, a.nome(), atual.tipo(), a.etapaId(), a.categoria(), a.limite(),
                    a.acaoEsperada(), a.ativa(), versaoLida + 1);
            if (nova.equals(new RegraAlerta(id, atual.nome(), atual.tipo(), atual.etapaId(), atual.categoria(),
                    atual.limite(), atual.acaoEsperada(), atual.ativa(), versaoLida + 1))) {
                return atual; // nada mudou: não incrementa a versão
            }
            exigirEtapa(r, nova.etapaId());
            r.atualizarRegra(nova, versaoLida);
            return nova;
        });
    }

    // ------------------------------------------------------------------- ciência (RF-022)

    /**
     * Registra a ciência de uma ocorrência que está EM ALERTA AGORA (recalculada no servidor;
     * não se registra ciência de alerta inexistente ou já resolvido). Idempotente.
     *
     * @return {@code true} se registrou; {@code false} se já havia ciência
     */
    public boolean registrarCiencia(UsuarioAutenticado u, ContextoOrigem origem, PedidoCiencia p) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_ALTERAR);
        Objects.requireNonNull(p, "ocorrência");
        Objects.requireNonNull(p.episodioId());
        Instant agora = relogio.instant();
        return transacao.executar(u, origem, r -> {
            RepositorioAlertas.EpisodioMonitorado e = r.episodiosAbertos(List.of(p.episodioId())).stream().findFirst()
                    .orElseThrow(() -> new RecursoNaoEncontradoException("Episódio aberto"));
            Alerta alerta = MotorDeAlertas.avaliar(e.situacao(), r.regras(true), agora).stream()
                    .filter(a -> a.regraId().equals(p.regraId()) && a.referenciaEm().equals(p.referenciaEm())
                            && Objects.equals(a.pendenciaId(), p.pendenciaId()))
                    .findFirst()
                    .orElse(null);
            exigir(alerta != null, "ALERTA_INEXISTENTE",
                    "Não há alerta ativo para esta ocorrência (pode já ter sido resolvido)");
            return r.registrarCiencia(ids.get(), chave(alerta));
        });
    }

    // ----------------------------------------------------------------------------

    private AlertasDosEpisodios calcular(UsuarioAutenticado u, ContextoOrigem origem, Collection<UUID> episodioIds) {
        Objects.requireNonNull(episodioIds);
        Instant agora = relogio.instant();
        if (episodioIds.isEmpty()) {
            return new AlertasDosEpisodios(agora, Map.of());
        }
        return transacao.executar(u, origem, r -> {
            List<RegraAlerta> regras = r.regras(true);
            if (regras.isEmpty()) {
                return new AlertasDosEpisodios(agora, Map.of());
            }
            return new AlertasDosEpisodios(agora, Map.copyOf(avaliar(r, regras, r.episodiosAbertos(episodioIds), agora)));
        });
    }

    private static Map<UUID, List<AlertaVisto>> avaliar(RepositorioAlertas r, List<RegraAlerta> regras,
                                                        List<RepositorioAlertas.EpisodioMonitorado> episodios,
                                                        Instant agora) {
        Map<UUID, List<Alerta>> alertas = new HashMap<>();
        for (RepositorioAlertas.EpisodioMonitorado e : episodios) {
            List<Alerta> doEpisodio = MotorDeAlertas.avaliar(e.situacao(), regras, agora);
            if (!doEpisodio.isEmpty()) {
                alertas.put(e.situacao().episodioId(), doEpisodio);
            }
        }
        Map<RepositorioAlertas.Ocorrencia, RepositorioAlertas.Ciencia> ciencias =
                alertas.isEmpty() ? Map.of() : r.ciencias(List.copyOf(alertas.keySet()));
        Map<UUID, List<AlertaVisto>> vistos = new HashMap<>();
        alertas.forEach((id, lista) -> vistos.put(id,
                lista.stream().map(a -> new AlertaVisto(a, ciencias.get(chave(a)))).toList()));
        return vistos;
    }

    static RepositorioAlertas.Ocorrencia chave(Alerta a) {
        return new RepositorioAlertas.Ocorrencia(a.episodioId(), a.regraId(), a.regraVersao(), a.referenciaEm(),
                a.pendenciaId());
    }

    private static void exigirEtapa(RepositorioAlertas r, UUID etapaId) {
        exigir(etapaId == null || r.etapaDaUnidade(etapaId), "ETAPA_INVALIDA", "Etapa não pertence à unidade");
    }
}
