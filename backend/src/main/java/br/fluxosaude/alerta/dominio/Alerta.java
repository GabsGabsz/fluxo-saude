package br.fluxosaude.alerta.dominio;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ocorrência de alerta OPERACIONAL (RN-007/013: não é risco clínico nem prioridade médica).
 * Identificada por (episódio, regra, versão da regra, {@code referenciaEm}, {@code pendenciaId}):
 * quando a situação muda (nova etapa, novo bloqueio, novo prazo...) ou a regra é alterada, é
 * outra ocorrência (uma ciência anterior não vale para ela).
 *
 * @param referenciaEm início da contagem (entrada, início da etapa/bloqueio, último registro ou prazo)
 * @param atingidoEm   instante em que o limite foi atingido (referência + limite; ou o prazo)
 */
public record Alerta(UUID episodioId, UUID regraId, int regraVersao, String regraNome, TipoRegraAlerta tipo,
                     Instant referenciaEm,
                     Instant atingidoEm, UUID pendenciaId, Duration limite, String acaoEsperada) {

    public Alerta {
        Objects.requireNonNull(episodioId);
        Objects.requireNonNull(regraId);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(referenciaEm);
        Objects.requireNonNull(atingidoEm);
    }

    /** Há quanto tempo o limite foi atingido (zero na fronteira). */
    public Duration tempoAlemDoLimite(Instant agora) {
        Duration d = Duration.between(atingidoEm, agora);
        return d.isNegative() ? Duration.ZERO : d;
    }
}
