package br.fluxosaude.identidade.dominio;

import br.fluxosaude.compartilhado.RegraVioladaException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Política de senha alinhada ao NIST SP 800-63B (RNF-013): comprimento mínimo alto,
 * sem regras de composição arbitrárias, bloqueio de senhas comuns/óbvias e de senhas
 * derivadas do login ou do nome. Limite máximo evita abuso de custo do Argon2.
 */
public final class PoliticaSenha {

    public static final int MINIMO = 12;
    public static final int MAXIMO = 128;

    /** Amostra de senhas mais comuns (pt-BR e globais) com 12+ caracteres. */
    private static final Set<String> COMUNS = Set.of(
            "123456789012", "1234567890123", "12345678910111", "qwertyuiopas", "qwerty123456",
            "senha1234567", "senha12345678", "minhasenha123", "password1234", "password12345",
            "passwordpassword", "123456123456", "abc123456789", "aaaaaaaaaaaa", "iloveyou1234",
            "brasil123456", "brasil2024!!", "admin1234567", "administrador", "mudar1234567",
            "trocar123456", "fluxosaude123", "fluxosaude2026", "upabomjesus123", "bomjesus1234",
            "piaui1234567", "teresina1234", "saude1234567", "hospital1234", "enfermagem123");

    private PoliticaSenha() {
    }

    /** Normalização Unicode (NFKC) aplicada antes de validar e de gerar o hash. */
    public static String normalizar(String senha) {
        return senha == null ? null : Normalizer.normalize(senha, Normalizer.Form.NFKC);
    }

    public static void validar(String senhaBruta, String login, String nome) {
        String senha = normalizar(senhaBruta);
        RegraVioladaException.exigir(senha != null && senha.codePointCount(0, senha.length()) >= MINIMO,
                "SENHA_CURTA", "A senha deve ter pelo menos " + MINIMO + " caracteres");
        RegraVioladaException.exigir(senha.codePointCount(0, senha.length()) <= MAXIMO,
                "SENHA_LONGA", "A senha deve ter no máximo " + MAXIMO + " caracteres");
        String minuscula = senha.toLowerCase(Locale.ROOT);
        RegraVioladaException.exigir(!COMUNS.contains(minuscula), "SENHA_COMUM",
                "Esta senha é muito comum; escolha outra (uma frase longa é uma boa opção)");
        RegraVioladaException.exigir(minuscula.codePoints().distinct().count() >= 5, "SENHA_REPETITIVA",
                "A senha tem pouca variação de caracteres");
        if (login != null && login.length() >= 3) {
            RegraVioladaException.exigir(!minuscula.contains(login.toLowerCase(Locale.ROOT)), "SENHA_CONTEM_LOGIN",
                    "A senha não pode conter o login");
        }
        if (nome != null) {
            String nomeSemAcento = Normalizer.normalize(nome, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT);
            String senhaSemAcento = Normalizer.normalize(minuscula, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            for (String parte : nomeSemAcento.split("\\s+")) {
                if (parte.length() >= 4) {
                    RegraVioladaException.exigir(!senhaSemAcento.contains(parte), "SENHA_CONTEM_NOME",
                            "A senha não pode conter partes do seu nome");
                }
            }
        }
    }
}
