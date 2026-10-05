package br.fluxosaude.episodio.dominio;

import br.fluxosaude.compartilhado.RegraVioladaException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Limites para instantes informados pelo usuário (registro retroativo de fatos).
 *
 * @param toleranciaFuturo   diferença de relógio aceitável entre dispositivos (banco usa 2 min)
 * @param retroatividadeMaxima até quanto tempo atrás um fato pode ser lançado
 */
public record PoliticaTempo(Duration toleranciaFuturo, Duration retroatividadeMaxima) {

    public static final PoliticaTempo PADRAO = new PoliticaTempo(Duration.ofMinutes(2), Duration.ofHours(24));

    public PoliticaTempo {
        Objects.requireNonNull(toleranciaFuturo);
        Objects.requireNonNull(retroatividadeMaxima);
        if (toleranciaFuturo.isNegative() || toleranciaFuturo.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("tolerância de futuro deve estar entre 0 e 2 minutos (limite do banco)");
        }
        if (retroatividadeMaxima.isNegative()) {
            throw new IllegalArgumentException("retroatividade negativa");
        }
    }

    /** Valida um instante informado em relação a "agora" e a um piso cronológico. */
    public void validar(Instant instante, Instant agora, Instant naoAntesDe, String oQue) {
        Objects.requireNonNull(instante, oQue + " é obrigatório");
        RegraVioladaException.exigir(!instante.isAfter(agora.plus(toleranciaFuturo)), "DATA_FUTURA",
                oQue + " não pode estar no futuro");
        RegraVioladaException.exigir(!instante.isBefore(agora.minus(retroatividadeMaxima)), "RETROATIVIDADE_EXCEDIDA",
                oQue + " excede o limite de registro retroativo (" + retroatividadeMaxima.toHours() + "h)");
        if (naoAntesDe != null) {
            RegraVioladaException.exigir(!instante.isBefore(naoAntesDe), "CRONOLOGIA",
                    oQue + " não pode ser anterior ao último registro do episódio");
        }
    }
}
