package br.fluxosaude.identidade.dominio;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conta FALHAS por chave (ex.: IP; IP+login; usuário) em janela deslizante.
 * Sucessos não zeram o contador — senão quem tem uma conta válida poderia "limpar"
 * o limite entre rajadas de tentativas contra outras contas.
 * Memória limitada a {@code maxChaves} (as menos usadas são descartadas), para que
 * variar chaves não esgote a memória. Estado por instância (ver ADR-0002).
 */
public final class LimitadorDeTentativas {

    private final int maxFalhas;
    private final Duration janela;
    private final int maxChaves;
    private final Clock clock;
    private final LinkedHashMap<String, Deque<Instant>> registros;

    public LimitadorDeTentativas(int maxFalhas, Duration janela, int maxChaves, Clock clock) {
        if (maxFalhas < 1 || maxChaves < 1 || janela.isNegative() || janela.isZero()) {
            throw new IllegalArgumentException("parâmetros inválidos");
        }
        this.maxFalhas = maxFalhas;
        this.janela = janela;
        this.maxChaves = maxChaves;
        this.clock = clock;
        this.registros = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> maisAntiga) {
                return size() > LimitadorDeTentativas.this.maxChaves;
            }
        };
    }

    /** {@code true} se a chave ainda não atingiu o limite de falhas na janela. */
    public synchronized boolean permitido(String chave) {
        Deque<Instant> fila = registros.get(normalizar(chave));
        if (fila == null) {
            return true;
        }
        descartarAntigas(fila);
        return fila.size() < maxFalhas;
    }

    public synchronized void registrarFalha(String chave) {
        Deque<Instant> fila = registros.computeIfAbsent(normalizar(chave), k -> new ArrayDeque<>());
        descartarAntigas(fila);
        fila.addLast(clock.instant());
    }

    private void descartarAntigas(Deque<Instant> fila) {
        Instant limite = clock.instant().minus(janela);
        Iterator<Instant> it = fila.iterator();
        while (it.hasNext() && it.next().isBefore(limite)) {
            it.remove();
        }
    }

    private static String normalizar(String chave) {
        return chave == null ? "?" : chave;
    }
}
