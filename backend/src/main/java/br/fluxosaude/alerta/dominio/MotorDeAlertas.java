package br.fluxosaude.alerta.dominio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Avalia as regras ativas da unidade sobre o estado de um episódio ABERTO, num instante do
 * servidor. Puro e determinístico (relógio controlado nos testes).
 *
 * <p>Fronteiras (ERS §2: "quando tempos configurados forem atingidos ou ultrapassados"):
 * <ul>
 *   <li>regras de tempo: em alerta quando {@code agora >= referência + limite};</li>
 *   <li>pendência vencida: em alerta quando {@code agora > prazo} — a mesma definição de
 *       "vencida" já usada pela Torre ({@code prazo < agora}).</li>
 * </ul>
 * Episódio encerrado não deve chegar aqui (RN-008: cronômetros param); a consulta só lê abertos.
 */
public final class MotorDeAlertas {

    private static final Comparator<Alerta> ORDEM = Comparator.comparing(Alerta::atingidoEm)
            .thenComparing(Alerta::regraNome)
            .thenComparing(a -> a.pendenciaId() == null ? "" : a.pendenciaId().toString());

    private MotorDeAlertas() {
    }

    public static List<Alerta> avaliar(SituacaoEpisodio s, List<RegraAlerta> regras, Instant agora) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(agora);
        List<Alerta> alertas = new ArrayList<>();
        for (RegraAlerta r : regras) {
            if (!r.ativa()) {
                continue;
            }
            switch (r.tipo()) {
                case TEMPO_NA_ETAPA -> {
                    if (naEtapa(r, s)) {
                        porTempo(r, s, s.etapaDesde(), agora, alertas);
                    }
                }
                case TEMPO_TOTAL -> {
                    if (naEtapa(r, s)) {
                        porTempo(r, s, s.entradaEm(), agora, alertas);
                    }
                }
                case TEMPO_BLOQUEADO -> {
                    if (naEtapa(r, s) && s.bloqueioDesde() != null
                            && (r.categoria() == null || r.categoria() == s.categoriaBloqueio())) {
                        porTempo(r, s, s.bloqueioDesde(), agora, alertas);
                    }
                }
                case SEM_ATUALIZACAO -> {
                    if (naEtapa(r, s)) {
                        // Sem nenhum registro (não deveria ocorrer: a abertura gera evento), conta da entrada.
                        porTempo(r, s, s.ultimoRegistroEm() != null ? s.ultimoRegistroEm() : s.entradaEm(), agora,
                                alertas);
                    }
                }
                case PENDENCIA_VENCIDA -> {
                    for (SituacaoEpisodio.PendenciaAberta p : s.pendencias()) {
                        if ((r.categoria() == null || r.categoria() == p.categoria()) && agora.isAfter(p.prazo())) {
                            alertas.add(new Alerta(s.episodioId(), r.id(), r.versao(), r.nome(), r.tipo(), p.prazo(), p.prazo(),
                                    p.id(), null, r.acaoEsperada()));
                        }
                    }
                }
                default -> throw new IllegalStateException("tipo de regra sem avaliação: " + r.tipo());
            }
        }
        alertas.sort(ORDEM);
        return List.copyOf(alertas);
    }

    private static boolean naEtapa(RegraAlerta r, SituacaoEpisodio s) {
        return r.etapaId() == null || r.etapaId().equals(s.etapaId());
    }

    private static void porTempo(RegraAlerta r, SituacaoEpisodio s, Instant referencia, Instant agora,
                                 List<Alerta> alertas) {
        Instant atingido = referencia.plus(r.limite());
        if (!agora.isBefore(atingido)) {
            alertas.add(new Alerta(s.episodioId(), r.id(), r.versao(), r.nome(), r.tipo(), referencia, atingido, null, r.limite(),
                    r.acaoEsperada()));
        }
    }
}
