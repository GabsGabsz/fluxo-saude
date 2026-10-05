package br.fluxosaude.identidade.infra;

import br.fluxosaude.compartilhado.SobrecargaException;
import br.fluxosaude.identidade.aplicacao.HashDeSenha;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id com parâmetros mínimos recomendados pela OWASP (m=19 MiB, t=2, p=1).
 * O formato armazenado carrega os parâmetros, então endurecê-los no futuro não invalida
 * hashes antigos. Um semáforo limita verificações simultâneas: cada uma usa ~19 MiB,
 * e uma rajada de logins não pode esgotar a memória do servidor.
 */
public final class HashDeSenhaArgon2 implements HashDeSenha {

    static final String ID = "argon2@SpringSecurity_v5_8";

    private final PasswordEncoder codificador;
    private final Semaphore simultaneos;
    private final String ficticio;

    public HashDeSenhaArgon2(int maxSimultaneos) {
        this.codificador = new DelegatingPasswordEncoder(ID,
                Map.of(ID, new Argon2PasswordEncoder(16, 32, 1, 19_456, 2)));
        this.simultaneos = new Semaphore(Math.max(1, maxSimultaneos), true);
        this.ficticio = codificador.encode(UUID.randomUUID().toString());
    }

    @Override
    public String gerar(String senha) {
        return comPermissao(() -> codificador.encode(senha));
    }

    @Override
    public boolean confere(String senha, String hash) {
        if (senha == null || hash == null) {
            return false;
        }
        return comPermissao(() -> {
            try {
                return codificador.matches(senha, hash);
            } catch (IllegalArgumentException hashMalformado) {
                return false;
            }
        });
    }

    @Override
    public String hashFicticio() {
        return ficticio;
    }

    private <T> T comPermissao(java.util.function.Supplier<T> operacao) {
        try {
            // Espera limitada: numa rajada, melhor responder 503 do que acumular threads presas.
            if (!simultaneos.tryAcquire(3, TimeUnit.SECONDS)) {
                throw new SobrecargaException("Muitas autenticações simultâneas. Tente novamente em instantes.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SobrecargaException("Verificação de senha interrompida.");
        }
        try {
            return operacao.get();
        } finally {
            simultaneos.release();
        }
    }
}
