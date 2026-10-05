package br.fluxosaude.compartilhado;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonPlanoTest {

    @Test
    void serializaEmOrdemEEscapa() {
        Map<String, String> m = new HashMap<>();
        m.put("b", "linha1\nlinha2 \"aspas\" \\ barra");
        m.put("a", "ok");
        m.put("nulo", null);
        m.put("ctrl", "\u0001");
        assertEquals("{\"a\":\"ok\",\"b\":\"linha1\\nlinha2 \\\"aspas\\\" \\\\ barra\",\"ctrl\":\"\\u0001\"}", JsonPlano.de(m));
        assertEquals("{}", JsonPlano.de(Map.of()));
    }
}
