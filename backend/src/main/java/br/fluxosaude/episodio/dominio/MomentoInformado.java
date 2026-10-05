package br.fluxosaude.episodio.dominio;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Instante de um fato operacional. Por padrão é o relógio do servidor (RNF-017,
 * RF-040). Quando o usuário informa um horário anterior (ex.: registro atrasado,
 * contingência — RNF-018), trata-se de AJUSTE MANUAL e a justificativa é obrigatória.
 */
public record MomentoInformado(Instant instante, String justificativaAjuste) {

    public MomentoInformado {
        Objects.requireNonNull(instante, "instante");
    }

    /** Fato acontecendo agora, pelo relógio do servidor. */
    public static MomentoInformado agora(Clock relogioDoServidor) {
        return new MomentoInformado(relogioDoServidor.instant(), null);
    }

    /** Fato ocorrido antes do registro, com justificativa do ajuste. */
    public static MomentoInformado ajustado(Instant instante, String justificativa) {
        return new MomentoInformado(instante, justificativa);
    }
}
