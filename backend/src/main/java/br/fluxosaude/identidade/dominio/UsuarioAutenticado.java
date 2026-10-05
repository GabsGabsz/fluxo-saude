package br.fluxosaude.identidade.dominio;

import java.io.Serializable;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Principal da sessão. Guarda só o necessário (sem senha, sem dado de paciente).
 * {@link #getName()} devolve o ID (não o login nem o nome): é o valor indexado nas
 * sessões persistidas e em logs, e não é dado pessoal legível.
 *
 * <p>O usuário atua em UMA unidade por vez ({@code unidadeAtiva}); o contexto do banco
 * (RLS) recebe só essa unidade — mínimo privilégio mesmo para quem tem várias lotações.
 */
public record UsuarioAutenticado(UUID usuarioId, String login, String nome,
                                 Map<UUID, Set<Papel>> lotacoes, UUID unidadeAtiva,
                                 boolean deveTrocarSenha) implements Principal, Serializable {

    private static final long serialVersionUID = 1L;

    public UsuarioAutenticado {
        Objects.requireNonNull(usuarioId);
        Objects.requireNonNull(login);
        Objects.requireNonNull(nome);
        Map<UUID, Set<Papel>> copia = new HashMap<>();
        Objects.requireNonNull(lotacoes).forEach((unidade, papeis) -> copia.put(unidade, Set.copyOf(papeis)));
        lotacoes = Map.copyOf(copia);
        if (lotacoes.isEmpty()) {
            throw new IllegalArgumentException("usuário sem lotação não pode ser autenticado");
        }
        if (unidadeAtiva == null || !lotacoes.containsKey(unidadeAtiva)) {
            throw new IllegalArgumentException("unidade ativa deve ser uma das lotações do usuário");
        }
    }

    @Override
    public String getName() {
        return usuarioId.toString();
    }

    public Set<Papel> papeisNaUnidadeAtiva() {
        return lotacoes.get(unidadeAtiva);
    }

    public Set<Permissao> permissoes() {
        return MatrizPermissoes.permissoes(papeisNaUnidadeAtiva());
    }

    public boolean pode(Permissao p) {
        return !deveTrocarSenha && permissoes().contains(p);
    }

    public UsuarioAutenticado comUnidadeAtiva(UUID unidade) {
        return new UsuarioAutenticado(usuarioId, login, nome, lotacoes, unidade, deveTrocarSenha);
    }

    public UsuarioAutenticado senhaTrocada() {
        return new UsuarioAutenticado(usuarioId, login, nome, lotacoes, unidadeAtiva, false);
    }

    /** Evita vazar nome/login em logs por acidente (toString padrão de record). */
    @Override
    public String toString() {
        return "UsuarioAutenticado[" + usuarioId + "]";
    }
}
