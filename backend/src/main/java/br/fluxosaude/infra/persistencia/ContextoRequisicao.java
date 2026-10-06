package br.fluxosaude.infra.persistencia;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Contexto aplicado a CADA transação (ADR-0002 §7, ADR-0004): quem é o usuário, em qual
 * unidade atua (só a ativa — mínimo privilégio), quais papéis a sessão supõe que ele tem
 * (o banco confere) e a origem para auditoria.
 *
 * @param usuarioId       nulo antes do login
 * @param unidadeAtiva    nula em operações sobre o próprio cadastro
 * @param papeisEsperados papéis na unidade ativa segundo a sessão (vazio sem unidade; nulo se anônimo)
 * @param credencialVersao versão de credencial da sessão (V12); obrigatória com usuário
 */
public record ContextoRequisicao(UUID usuarioId, UUID unidadeAtiva, Set<Papel> papeisEsperados,
                                 Integer credencialVersao, ContextoOrigem origem) {

    public ContextoRequisicao {
        Objects.requireNonNull(origem);
        papeisEsperados = papeisEsperados == null ? null : Set.copyOf(papeisEsperados);
        if (usuarioId == null && (unidadeAtiva != null || papeisEsperados != null || credencialVersao != null)) {
            throw new IllegalArgumentException("contexto anônimo não tem unidade, papéis nem credencial");
        }
        if (usuarioId != null && credencialVersao == null) {
            throw new IllegalArgumentException("contexto de usuário exige a versão de credencial da sessão");
        }
    }

    /** Antes do login: sem usuário e sem unidade (o RLS não mostra nada). */
    public static ContextoRequisicao anonimo(ContextoOrigem origem) {
        return new ContextoRequisicao(null, null, null, null, origem);
    }

    /** Operações sobre o próprio cadastro (perfil, senha): usuário, sem unidade. */
    public static ContextoRequisicao proprioUsuario(UUID usuarioId, int credencialVersao, ContextoOrigem origem) {
        return new ContextoRequisicao(Objects.requireNonNull(usuarioId), null, Set.of(), credencialVersao, origem);
    }

    public static ContextoRequisicao de(UsuarioAutenticado usuario, ContextoOrigem origem) {
        return new ContextoRequisicao(usuario.usuarioId(), usuario.unidadeAtiva(), usuario.papeisNaUnidadeAtiva(),
                usuario.credencialVersao(), origem);
    }
}
