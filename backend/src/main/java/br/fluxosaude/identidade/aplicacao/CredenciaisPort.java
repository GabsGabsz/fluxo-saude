package br.fluxosaude.identidade.aplicacao;

import br.fluxosaude.identidade.dominio.Papel;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Porta de persistência das credenciais (implementada sobre as funções do banco, V6). */
public interface CredenciaisPort {

    /** {@code credencialVersao} vem na MESMA leitura do hash (V12). */
    record Credencial(UUID usuarioId, String senhaHash, boolean ativo, boolean deveTrocarSenha, Instant bloqueadoAte,
                      int credencialVersao) {
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
    void registrarFalhaNaTrocaDeSenha(UUID usuarioId, int credencialVersao, ContextoOrigem origem);

    /** Lotações em unidades ativas. */
    Map<UUID, Set<Papel>> lotacoes(UUID usuarioId, ContextoOrigem origem);

    /**
     * Dados de exibição do próprio usuário (executa com o usuário no contexto). Lança
     * {@code SessaoRevogadaException} se a versão de credencial não for mais a vigente.
     */
    Perfil perfil(UUID usuarioId, int credencialVersao, ContextoOrigem origem);

    /** Hash atual do próprio usuário (para confirmar a senha atual na troca). */
    String hashAtual(UUID usuarioId, int credencialVersao, ContextoOrigem origem);

    /**
     * Grava novo hash do próprio usuário e retira a exigência de troca, SÓ se a versão de
     * credencial da sessão ainda for a vigente (senão {@code SessaoRevogadaException}). Devolve
     * a nova versão (as demais sessões do usuário passam a ser recusadas pelo banco).
     */
    int gravarNovaSenha(UUID usuarioId, int credencialVersao, String novoHash, ContextoOrigem origem);
}
