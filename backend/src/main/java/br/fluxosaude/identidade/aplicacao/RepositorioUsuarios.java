package br.fluxosaude.identidade.aplicacao;

import br.fluxosaude.identidade.dominio.Papel;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Porta de persistência da gestão de usuários. Toda operação roda numa transação com o
 * contexto do administrador validado pelo banco; as escritas são funções do banco que
 * repetem as regras de alcance, controlam a versão e registram a auditoria (V11).
 * Erros do adaptador: {@code RecursoNaoEncontradoException}, {@code ConflitoDeVersaoException}
 * e {@code AcessoNegadoException} (alcance recusado pelo banco).
 */
public interface RepositorioUsuarios {

    /**
     * Usuário lotado na unidade ativa.
     *
     * @param papeis               papéis NESTA unidade
     * @param possuiOutrasUnidades se também está lotado em outra unidade
     * @param contaGerenciavel     se quem consulta administra TODAS as unidades do usuário
     *                             (pode alterar dados da conta, desativar e redefinir senha)
     */
    record Usuario(UUID id, String login, String nome, String email, String registroProfissional, boolean ativo,
                   boolean deveTrocarSenha, int versao, Set<Papel> papeis, boolean possuiOutrasUnidades,
                   boolean contaGerenciavel) {
        public Usuario {
            papeis = Set.copyOf(papeis);
        }
    }

    /**
     * Conta existente localizada pelo login (para vincular à unidade). Sem nome/e-mail:
     * só o necessário para vincular. {@code vinculavel} = ativa e lotada em outra unidade.
     */
    record Localizado(UUID id, int versao, boolean lotadoNaUnidade, boolean vinculavel) {
    }

    /** Até {@code limite} usuários da unidade ativa, por nome. */
    List<Usuario> listar(int limite);

    Optional<Usuario> obter(UUID id);

    Optional<Localizado> localizarPorLogin(String login);

    /** Cria a conta já lotada na unidade ativa; o banco força ativo + troca de senha. Devolve a versão. */
    int criar(UUID id, String login, String nome, String email, String registroProfissional, String hashSenha,
              Set<Papel> papeis);

    /** Conjunto de papéis na unidade ativa (vazio = revoga). Devolve a nova versão do usuário. */
    int definirPapeis(UUID id, int versaoLida, Set<Papel> papeis);

    int alterarConta(UUID id, int versaoLida, String nome, String email, String registroProfissional);

    int definirSituacao(UUID id, int versaoLida, boolean ativo);

    int definirSenhaProvisoria(UUID id, int versaoLida, String hashSenha);
}
