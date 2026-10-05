package br.fluxosaude.compartilhado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void versaoVarianteETimestamp() {
        Instant t = Instant.parse("2026-10-05T12:00:00Z");
        UUID u = new UuidV7(Clock.fixed(t, ZoneOffset.UTC)).proximo();
        assertEquals(7, u.version());
        assertEquals(2, u.variant());
        assertEquals(t.toEpochMilli(), UuidV7.timestampMillis(u));
    }

    @Test
    void monotonicoEUnicoMesmoComRelogioParado() {
        UuidV7 g = new UuidV7(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
        Set<UUID> vistos = new HashSet<>();
        UUID anterior = g.proximo();
        vistos.add(anterior);
        for (int i = 0; i < 20_000; i++) {   // > 4096: força avanço artificial do milissegundo
            UUID atual = g.proximo();
            assertTrue(compararSemSinal(anterior, atual) < 0, "ordem crescente");
            assertTrue(vistos.add(atual), "sem repetição");
            anterior = atual;
        }
    }

    /** Ordem do PostgreSQL para uuid (bytes sem sinal). */
    private static int compararSemSinal(UUID a, UUID b) {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
