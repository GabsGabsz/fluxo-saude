package br.fluxosaude.episodio.dominio;

import br.fluxosaude.identidade.dominio.Papel;
import java.util.Objects;
import java.util.UUID;

/** Responsável por uma pendência: usuário, setor OU perfil (ERS §9). */
public sealed interface Responsavel {

    record Usuario(UUID usuarioId) implements Responsavel {
        public Usuario {
            Objects.requireNonNull(usuarioId);
        }
    }

    record Setor(UUID setorId) implements Responsavel {
        public Setor {
            Objects.requireNonNull(setorId);
        }
    }

    record Perfil(Papel papel) implements Responsavel {
        public Perfil {
            Objects.requireNonNull(papel);
        }
    }
}
