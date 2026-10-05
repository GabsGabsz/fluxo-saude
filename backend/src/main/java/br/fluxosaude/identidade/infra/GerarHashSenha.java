package br.fluxosaude.identidade.infra;

import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.dominio.PoliticaSenha;
import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Utilitário de implantação: gera o hash Argon2id da senha inicial do administrador
 * sem que ela apareça em argumentos de linha de comando ou histórico do shell.
 * Uso: {@code mvn -q spring-boot:run -Dspring-boot.run.main-class=br.fluxosaude.identidade.infra.GerarHashSenha}
 */
public final class GerarHashSenha {

    private GerarHashSenha() {
    }

    public static void main(String[] args) throws Exception {
        String senha;
        Console console = System.console();
        if (console != null) {
            char[] a = console.readPassword("Senha inicial (mín. %d caracteres): ", PoliticaSenha.MINIMO);
            char[] b = console.readPassword("Repita a senha: ");
            if (a == null || b == null || !Arrays.equals(a, b)) {
                System.err.println("As senhas não conferem.");
                System.exit(2);
                return;
            }
            senha = new String(a);
            Arrays.fill(a, ' ');
            Arrays.fill(b, ' ');
        } else {
            System.err.println("Sem terminal interativo: lendo a senha da entrada padrão (uma linha).");
            senha = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
        try {
            PoliticaSenha.validar(senha, null, null);
        } catch (RegraVioladaException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(new HashDeSenhaArgon2(1).gerar(PoliticaSenha.normalizar(senha)));
    }
}
