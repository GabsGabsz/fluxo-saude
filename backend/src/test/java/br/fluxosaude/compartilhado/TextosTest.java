package br.fluxosaude.compartilhado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TextosTest {

    @Test
    void normalizaEValida() {
        assertEquals("abc", Textos.obrigatorio("  abc ", "x", 3, 10));
        assertEquals("linha 1\nlinha 2", Textos.obrigatorio("linha 1\nlinha 2", "x", 3, 50));
        assertNull(Textos.opcional("   ", "x", 3, 10));
        assertEquals("CAMPO_TAMANHO",
                assertThrows(RegraVioladaException.class, () -> Textos.obrigatorio("ab", "x", 3, 10)).codigo());
        assertEquals("CAMPO_TAMANHO",
                assertThrows(RegraVioladaException.class, () -> Textos.obrigatorio("a".repeat(11), "x", 3, 10)).codigo());
    }

    @Test
    void rejeitaCaracteresDeControle() {
        assertEquals("CAMPO_INVALIDO", assertThrows(RegraVioladaException.class,
                () -> Textos.obrigatorio("abc\u001b[31mdef", "x", 3, 50)).codigo());
        assertEquals("CAMPO_INVALIDO", assertThrows(RegraVioladaException.class,
                () -> Textos.obrigatorio("abc\u0000def", "x", 3, 50)).codigo());
    }
}
