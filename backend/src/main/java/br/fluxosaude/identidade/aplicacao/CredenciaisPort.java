package br.fluxosaude.identidade.aplicacao;

import br.fluxosaude.identidade.dominio.Papel;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Porta de persistência das credenciais (implementada sobre as funções do banco, V6). */
public interface CredenciaisPort {

    record Credencial(UUID usuarioId, String senhaHash, boolean ativo, boolean deveTrocarSenha, Instant bloqueadoAte) {
    }

    record Perfil(String login, String nome) {
    }

    /** Busca sem contexto de usuário (função controlada {@code fluxo.credencial_para_login}). */
    Optional<Credencial> buscarPorLogin(String login, ContextoOrigem origem);

    /** Registra tentativa (sucesso/falha), aplica bloqueio progressivo e audita. */
    Optional<Instant> registrarTentativa(UUID usuarioIdOuNulo, boolean sucesso, ContextoOrigem origem);

    /** Audita tentativa durante bloqueio, sem incrementar o contador de falhas. */
    void registrarTentativaDuranteBloqueio(UUID usuarioId, ContextoOrigem origem);

    /** Audita senha atual incorreta na troca de senha (executa como o próprio usuário). */
    void registrarFalhaNaTrocaDeSenha(UUID usuarioId, ContextoOrigem origem);

    /** Lotações em unidades ativas. */
    Map<UUID, Set<Papel>> lotacoes(UUID usuarioId, ContextoOrigem origem);

    /** Dados de exibição do próprio usuário (executa com o usuário no contexto). */
    Perfil perfil(UUID usuarioId, ContextoOrigem origem);

    /** Hash atual do próprio usuário (para confirmar a senha atual na troca). */
    String hashAtual(UUID usuarioId, ContextoOrigem origem);

    /** Grava novo hash do próprio usuário e retira a exigência de troca. */
    void gravarNovaSenha(UUID usuarioId, String novoHash, ContextoOrigem origem);
}
