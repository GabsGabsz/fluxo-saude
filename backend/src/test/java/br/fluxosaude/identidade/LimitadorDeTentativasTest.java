package br.fluxosaude.identidade;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.dominio.LimitadorDeTentativas;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LimitadorDeTentativasTest {

    private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-10-05T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return agora.get(); }
    };

    @Test
    void contaSoFalhasEmJanelaDeslizante() {
        LimitadorDeTentativas l = new LimitadorDeTentativas(3, Duration.ofMinutes(5), 100, clock);
        for (int i = 0; i < 3; i++) {
            assertTrue(l.permitido("1.1.1.1"));
            l.registrarFalha("1.1.1.1");
        }
        assertFalse(l.permitido("1.1.1.1"));
        assertTrue(l.permitido("2.2.2.2"), "outra chave não é afetada");
        agora.set(agora.get().plus(Duration.ofMinutes(6)));
        assertTrue(l.permitido("1.1.1.1"), "janela expirou");
    }

    @Test
    void memoriaLimitada() {
        LimitadorDeTentativas l = new LimitadorDeTentativas(1, Duration.ofMinutes(5), 2, clock);
        l.registrarFalha("a");
        l.registrarFalha("b");
        l.registrarFalha("c");             // descarta "a" (menos usada)
        assertTrue(l.permitido("a"));      // memória não cresce sem limite
        assertFalse(l.permitido("c"));
    }
}
