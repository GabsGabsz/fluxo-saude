package br.fluxosaude.plantao.dominio;

import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Conteúdo de uma passagem de plantão (RF-016, ERS §10.4): TODOS os episódios abertos da
 * unidade, cada um com as marcações de continuidade, alertas operacionais vigentes e pendências
 * abertas (responsável e prazo = ação esperada). Contém só identificadores, códigos, instantes e
 * versões — nenhum nome, CNS ou texto livre.
 *
 * <p>A forma canônica ({@link #canonico()}) é determinística (ordem fixa de casos, alertas,
 * pendências e campos) e é ao mesmo tempo o JSON gravado e a base da {@link #assinatura()}: a
 * confirmação só vale para o conteúdo exatamente igual ao que o usuário viu.
 */
public record ConteudoPassagem(List<CasoPassagem> casos) {

    public static final int VERSAO_FORMATO = 1;

    public record AlertaPassagem(UUID regraId, int regraVersao, TipoRegraAlerta tipo, Instant referenciaEm,
                                 Instant atingidoEm, UUID pendenciaId) {
        public AlertaPassagem {
            Objects.requireNonNull(regraId);
            Objects.requireNonNull(tipo);
            Objects.requireNonNull(referenciaEm);
            Objects.requireNonNull(atingidoEm);
        }
    }

    public record PendenciaPassagem(UUID id, int versao, CategoriaBloqueio categoria, CriticidadeOperacional criticidade,
                                    Instant prazo, boolean vencida, UUID responsavelUsuarioId, UUID responsavelSetorId,
                                    String responsavelPapel) {
        public PendenciaPassagem {
            Objects.requireNonNull(id);
            Objects.requireNonNull(categoria);
            Objects.requireNonNull(criticidade);
            Objects.requireNonNull(prazo);
            if (responsavelPapel != null && !responsavelPapel.matches("^[A-Z_]{1,40}$")) {
                throw new IllegalArgumentException("papel inválido");
            }
        }
    }

    /**
     * @param critico       em alerta operacional OU com pendência de criticidade operacional CRÍTICA
     *                      (proposta; nunca risco clínico — RN-007/RN-013)
     * @param transferencia protocolo/destino registrados ou etapa de natureza Aceito/Transporte (proposta)
     */
    public record CasoPassagem(UUID episodioId, int versao, UUID etapaId, UUID setorId, UUID motivoId,
                               CategoriaBloqueio categoria, Instant bloqueioDesde, Instant entradaEm, Instant etapaDesde,
                               boolean critico, boolean transferencia, List<AlertaPassagem> alertas,
                               List<PendenciaPassagem> pendencias) {
        public CasoPassagem {
            Objects.requireNonNull(episodioId);
            Objects.requireNonNull(etapaId);
            Objects.requireNonNull(setorId);
            Objects.requireNonNull(entradaEm);
            Objects.requireNonNull(etapaDesde);
            alertas = alertas.stream().sorted(Comparator.comparing((AlertaPassagem a) -> a.regraId().toString())
                    .thenComparing(AlertaPassagem::referenciaEm)
                    .thenComparing(a -> a.pendenciaId() == null ? "" : a.pendenciaId().toString())).toList();
            pendencias = pendencias.stream().sorted(Comparator.comparing(p -> p.id().toString())).toList();
        }

        public long pendenciasVencidas() {
            return pendencias.stream().filter(PendenciaPassagem::vencida).count();
        }
    }

    public ConteudoPassagem {
        casos = casos.stream().sorted(Comparator.comparing(c -> c.episodioId().toString())).toList();
    }

    public int totalCasos() {
        return casos.size();
    }

    public int totalCriticos() {
        return (int) casos.stream().filter(CasoPassagem::critico).count();
    }

    public int totalTransferencias() {
        return (int) casos.stream().filter(CasoPassagem::transferencia).count();
    }

    public int totalPendencias() {
        return casos.stream().mapToInt(c -> c.pendencias().size()).sum();
    }

    public int totalVencidas() {
        return (int) casos.stream().mapToLong(CasoPassagem::pendenciasVencidas).sum();
    }

    public String assinatura() {
        return Assinatura.de(canonico());
    }

    /** JSON canônico (ordem fixa; valores sem texto livre, logo sem necessidade de escape). */
    public String canonico() {
        return "{\"formato\":" + VERSAO_FORMATO + ",\"casos\":["
                + casos.stream().map(ConteudoPassagem::caso).collect(Collectors.joining(",")) + "]}";
    }

    private static String caso(CasoPassagem c) {
        return "{\"episodioId\":" + s(c.episodioId()) + ",\"versao\":" + c.versao()
                + ",\"etapaId\":" + s(c.etapaId()) + ",\"setorId\":" + s(c.setorId())
                + ",\"motivoId\":" + s(c.motivoId()) + ",\"categoria\":" + s(c.categoria())
                + ",\"bloqueioDesde\":" + s(c.bloqueioDesde()) + ",\"entradaEm\":" + s(c.entradaEm())
                + ",\"etapaDesde\":" + s(c.etapaDesde()) + ",\"critico\":" + c.critico()
                + ",\"transferencia\":" + c.transferencia()
                + ",\"alertas\":[" + c.alertas().stream().map(ConteudoPassagem::alerta).collect(Collectors.joining(","))
                + "],\"pendencias\":[" + c.pendencias().stream().map(ConteudoPassagem::pendencia)
                .collect(Collectors.joining(",")) + "]}";
    }

    private static String alerta(AlertaPassagem a) {
        return "{\"regraId\":" + s(a.regraId()) + ",\"regraVersao\":" + a.regraVersao() + ",\"tipo\":" + s(a.tipo())
                + ",\"referenciaEm\":" + s(a.referenciaEm()) + ",\"atingidoEm\":" + s(a.atingidoEm())
                + ",\"pendenciaId\":" + s(a.pendenciaId()) + "}";
    }

    private static String pendencia(PendenciaPassagem p) {
        return "{\"id\":" + s(p.id()) + ",\"versao\":" + p.versao() + ",\"categoria\":" + s(p.categoria())
                + ",\"criticidade\":" + s(p.criticidade()) + ",\"prazo\":" + s(p.prazo()) + ",\"vencida\":" + p.vencida()
                + ",\"responsavelUsuarioId\":" + s(p.responsavelUsuarioId())
                + ",\"responsavelSetorId\":" + s(p.responsavelSetorId())
                + ",\"responsavelPapel\":" + s(p.responsavelPapel()) + "}";
    }

    /** Só UUIDs, enums, instantes ISO e códigos [A-Z_]: aspas sem escape são suficientes. */
    private static String s(Object valor) {
        if (valor == null) {
            return "null";
        }
        String t = valor.toString();
        if (!t.matches("^[A-Za-z0-9_:.+-]{1,64}$")) {
            throw new IllegalArgumentException("valor não canônico na passagem");
        }
        return "\"" + t + "\"";
    }
}
