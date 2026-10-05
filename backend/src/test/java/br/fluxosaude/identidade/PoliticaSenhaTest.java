package br.fluxosaude.identidade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.dominio.PoliticaSenha;
import org.junit.jupiter.api.Test;

class PoliticaSenhaTest {

    private static String erro(String senha) {
        return assertThrows(RegraVioladaException.class, () -> PoliticaSenha.validar(senha, "ana.souza", "Ana Maria Souza")).codigo();
    }

    @Test
    void aceitaFraseLonga() {
        PoliticaSenha.validar("cavalo correto bateria grampo", "ana.souza", "Ana Maria Souza");
        PoliticaSenha.validar("Plantão-noturno-2026!", "ana.souza", "Ana Maria Souza");
    }

    @Test
    void rejeitaFracas() {
        assertEquals("SENHA_CURTA", erro("curta123"));
        assertEquals("SENHA_CURTA", erro(null));
        assertEquals("SENHA_LONGA", erro("a1b2c3d4e5".repeat(13)));
        assertEquals("SENHA_COMUM", erro("Senha1234567"));
        assertEquals("SENHA_REPETITIVA", erro("abababababab"));
        assertEquals("SENHA_CONTEM_LOGIN", erro("xx-ana.souza-xx"));
        assertEquals("SENHA_CONTEM_NOME", erro("meu nome é sóuza 99"));
    }

    @Test
    void normalizaUnicode() {
        // "ﬁ" (ligadura) vira "fi" em NFKC: mesma senha digitada em teclados diferentes
        assertEquals(PoliticaSenha.normalizar("ﬁ"), "fi");
    }
}
