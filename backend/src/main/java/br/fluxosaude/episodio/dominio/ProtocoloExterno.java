package br.fluxosaude.episodio.dominio;

import br.fluxosaude.compartilhado.RegraVioladaException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Referência ao processo no sistema oficial (RF-009, RN-002). Guarda apenas o
 * identificador; o sistema oficial continua sendo a fonte da verdade.
 */
public record ProtocoloExterno(String sistema, String numero) {

    private static final Pattern SISTEMA = Pattern.compile("^[A-Z0-9_]{2,32}$");
    private static final Pattern NUMERO = Pattern.compile("^[A-Za-z0-9./-]{1,60}$");

    public ProtocoloExterno {
        Objects.requireNonNull(sistema);
        Objects.requireNonNull(numero);
        numero = numero.strip();
        RegraVioladaException.exigir(SISTEMA.matcher(sistema).matches(), "PROTOCOLO_INVALIDO",
                "Sistema do protocolo inválido");
        RegraVioladaException.exigir(NUMERO.matcher(numero).matches(), "PROTOCOLO_INVALIDO",
                "Número de protocolo inválido (use letras, dígitos, '.', '/' ou '-', até 60)");
    }
}
