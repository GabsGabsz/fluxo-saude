package br.fluxosaude.identidade.aplicacao;

import java.util.UUID;

/**
 * Encerramento das sessões HTTP de um usuário (após revogação/alteração de acesso).
 * É um reforço: mesmo que falhe, o banco revalida usuário, lotação e papéis em toda
 * transação e recusa a sessão antiga na requisição seguinte (ADR-0002 §7).
 */
@FunctionalInterface
public interface SessoesPort {

    void encerrarTodas(UUID usuarioId);
}
