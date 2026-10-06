package br.fluxosaude.identidade.web;

import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Contratos JSON da sessão. Limites de tamanho barram abuso antes de qualquer lógica. */
final class SessaoDtos {

    private SessaoDtos() {
    }

    record LoginRequest(@NotBlank @Size(max = 200) String login, @NotBlank @Size(max = 1024) String senha) {
        /** Nunca imprime a senha (ex.: em log de depuração). */
        @Override
        public String toString() {
            return "LoginRequest[login=***, senha=***]";
        }
    }

    record TrocaSenhaRequest(@NotBlank @Size(max = 1024) String senhaAtual, @NotBlank @Size(max = 1024) String novaSenha) {
        @Override
        public String toString() {
            return "TrocaSenhaRequest[***]";
        }
    }

    record TrocaUnidadeRequest(@NotNull UUID unidadeId) {
    }

    record Lotacao(UUID unidadeId, Set<Papel> papeis) {
    }

    record SessaoResponse(UUID usuarioId, String login, String nome, UUID unidadeAtiva, List<Lotacao> lotacoes,
                          Set<Permissao> permissoes, boolean deveTrocarSenha) {

        static SessaoResponse de(UsuarioAutenticado u) {
            List<Lotacao> lotacoes = u.lotacoes().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .map(e -> new Lotacao(e.getKey(), new TreeSet<>(e.getValue())))
                    .toList();
            return new SessaoResponse(u.usuarioId(), u.login(), u.nome(), u.unidadeAtiva(), lotacoes,
                    u.deveTrocarSenha() ? Set.of() : new TreeSet<>(u.permissoes()), u.deveTrocarSenha());
        }
    }

    record CsrfResponse(String cabecalho) {
    }

    record UnidadeResponse(java.util.UUID id, String codigo, String nome, String fusoHorario) {
    }
}
