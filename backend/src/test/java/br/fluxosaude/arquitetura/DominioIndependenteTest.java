package br.fluxosaude.arquitetura;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Regra arquitetural (ADR-0001): o domínio não depende de framework, banco ou web.
 * Isso mantém as regras de negócio testáveis isoladamente e portáveis.
 */
class DominioIndependenteTest {

    private static final Pattern PROIBIDO = Pattern.compile(
            "^import\\s+(static\\s+)?(org\\.springframework|jakarta\\.|javax\\.persistence|java\\.sql|org\\.postgresql"
            + "|com\\.fasterxml)", Pattern.MULTILINE);

    @Test
    void dominioNaoImportaFrameworks() throws IOException {
        Path raiz = Path.of("src", "main", "java", "br", "fluxosaude");
        List<Path> dominios;
        try (Stream<Path> s = Files.walk(raiz)) {
            dominios = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("dominio") || p.toString().contains("compartilhado"))
                    .collect(Collectors.toList());
        }
        assertTrue(!dominios.isEmpty(), "nenhum arquivo de domínio encontrado em " + raiz.toAbsolutePath());
        List<String> violacoes = new java.util.ArrayList<>();
        for (Path p : dominios) {
            String conteudo = Files.readString(p, StandardCharsets.UTF_8);
            if (PROIBIDO.matcher(conteudo).find()) {
                violacoes.add(p.toString());
            }
        }
        assertTrue(violacoes.isEmpty(), "domínio importando framework/infra: " + violacoes);
    }
}
