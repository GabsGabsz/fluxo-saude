package br.fluxosaude.compartilhado;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Gerador de UUID versão 7 (RFC 9562): 48 bits de timestamp em milissegundos +
 * 74 bits aleatórios. Ordenável no tempo, o que mantém índices B-tree compactos
 * e dá ordem natural à linha do tempo do episódio.
 *
 * <p>Monotônico dentro do processo: se dois IDs são gerados no mesmo milissegundo
 * (ou o relógio retrocede), o contador de 12 bits ("rand_a") é incrementado.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private long ultimoMillis = -1;
    private int contador;

    public UuidV7(Clock clock) {
        this.clock = clock;
    }

    private static final UuidV7 PADRAO = new UuidV7(Clock.systemUTC());

    public static UUID gerar() {
        return PADRAO.proximo();
    }

    public synchronized UUID proximo() {
        long agora = clock.millis();
        if (agora > ultimoMillis) {
            ultimoMillis = agora;
            contador = RANDOM.nextInt(1 << 11); // metade inferior: deixa folga para incrementos
        } else {
            contador++;
            if (contador >= (1 << 12)) {        // esgotou o milissegundo: avança artificialmente
                ultimoMillis++;
                contador = 0;
            }
        }
        long msb = (ultimoMillis & 0xFFFF_FFFF_FFFFL) << 16
                 | 0x7000L                       // versão 7
                 | (contador & 0x0FFF);
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)
                 | 0x8000_0000_0000_0000L;       // variante IETF (10xx)
        return new UUID(msb, lsb);
    }

    /** Milissegundos Unix embutidos em um UUIDv7. */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("não é UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
