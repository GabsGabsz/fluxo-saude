package br.fluxosaude.episodio.dominio;

import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.compartilhado.Textos;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Regras para instantes informados (RNF-017, RNF-018, RF-040). Parâmetros vêm da
 * unidade (fluxo.unidade) — nada aqui é regra institucional "hardcoded" (RN-014).
 *
 * @param toleranciaFuturo     diferença de relógio aceitável entre dispositivos (banco: 2 min)
 * @param retroatividadeMaxima até quanto tempo atrás um fato pode ser lançado (por unidade)
 * @param limiarAjusteManual   a partir de quanto atraso o horário informado é ajuste manual
 */
public record PoliticaTempo(Duration toleranciaFuturo, Duration retroatividadeMaxima, Duration limiarAjusteManual) {

    /** Valores técnicos iniciais, idênticos aos defaults do banco; devem ser validados (V-10). */
    public static final PoliticaTempo PADRAO =
            new PoliticaTempo(Duration.ofMinutes(2), Duration.ofHours(24), Duration.ofMinutes(5));

    /** Resultado da validação: o instante e, se for ajuste manual, a justificativa normalizada. */
    public record Validado(Instant instante, String justificativaAjuste) {
        public boolean ajusteManual() {
            return justificativaAjuste != null;
        }
    }

    public PoliticaTempo {
        Objects.requireNonNull(toleranciaFuturo);
        Objects.requireNonNull(retroatividadeMaxima);
        Objects.requireNonNull(limiarAjusteManual);
        if (toleranciaFuturo.isNegative() || toleranciaFuturo.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("tolerância de futuro deve estar entre 0 e 2 minutos (limite do banco)");
        }
        if (retroatividadeMaxima.isNegative() || retroatividadeMaxima.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("retroatividade deve estar entre 0 e 7 dias (limite do banco)");
        }
        if (limiarAjusteManual.compareTo(Duration.ofMinutes(1)) < 0 || limiarAjusteManual.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("limiar de ajuste manual deve estar entre 1 min e 1 h (limite do banco)");
        }
    }

    /**
     * Valida o momento informado contra o relógio do servidor e um piso cronológico.
     * Horário anterior ao limiar exige justificativa (ajuste manual, RNF-017).
     */
    public Validado validar(MomentoInformado momento, Instant agoraServidor, Instant naoAntesDe, String oQue) {
        Objects.requireNonNull(momento, oQue + " é obrigatório");
        Instant instante = momento.instante();
        RegraVioladaException.exigir(!instante.isAfter(agoraServidor.plus(toleranciaFuturo)), "DATA_FUTURA",
                oQue + " não pode estar no futuro");
        RegraVioladaException.exigir(!instante.isBefore(agoraServidor.minus(retroatividadeMaxima)), "RETROATIVIDADE_EXCEDIDA",
                oQue + " excede o limite de registro retroativo da unidade");
        if (naoAntesDe != null) {
            RegraVioladaException.exigir(!instante.isBefore(naoAntesDe), "CRONOLOGIA",
                    oQue + " não pode ser anterior ao último registro do episódio");
        }
        boolean ajuste = Duration.between(instante, agoraServidor).compareTo(limiarAjusteManual) > 0;
        if (!ajuste) {
            return new Validado(instante, null);
        }
        String justificativa = Textos.opcional(momento.justificativaAjuste(), "Justificativa do ajuste de horário", 3, 120);
        RegraVioladaException.exigir(justificativa != null, "AJUSTE_SEM_JUSTIFICATIVA",
                oQue + " anterior ao horário do servidor é ajuste manual e exige justificativa");
        return new Validado(instante, justificativa);
    }
}
