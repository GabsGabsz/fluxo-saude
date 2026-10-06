package br.fluxosaude.identidade.infra;

import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.aplicacao.RepositorioUsuarios;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.Permissao;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Gestão de usuários sobre as funções da V11 (a aplicação não tem DML direto em usuário e
 * lotação). SQL sempre parametrizado; os SQLSTATEs das funções viram erros de aplicação.
 */
final class RepositorioUsuariosJdbc implements RepositorioUsuarios {

    private static final String SELECT_USUARIO = """
            SELECT u.id, u.login::text, u.nome, u.email::text, u.registro_profissional, u.ativo, u.deve_trocar_senha,
                   u.versao, array_agg(l.papel::text ORDER BY l.papel::text),
                   coalesce(fluxo.possui_outras_unidades(u.id), false), fluxo.pode_administrar_conta(u.id)
              FROM fluxo.usuario u
              JOIN fluxo.lotacao l ON l.usuario_id = u.id AND l.unidade_id = ANY (fluxo.ctx_unidades())
            """;

    private final JdbcClient jdbc;

    RepositorioUsuariosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Usuario> listar(int limite) {
        return jdbc.sql(SELECT_USUARIO + " GROUP BY u.id ORDER BY u.nome, u.id LIMIT ?")
            .param(limite)
            .query(RepositorioUsuariosJdbc::usuario)
            .list();
    }

    @Override
    public Optional<Usuario> obter(UUID id) {
        return jdbc.sql(SELECT_USUARIO + " WHERE u.id = ? GROUP BY u.id")
            .param(id)
            .query(RepositorioUsuariosJdbc::usuario)
            .optional();
    }

    @Override
    public Optional<Localizado> localizarPorLogin(String login) {
        return traduzir(() -> jdbc.sql("""
                    SELECT usuario_id, versao, lotado_na_unidade, vinculavel FROM fluxo.admin_localizar_por_login(?)
                    """)
                .param(login)
                .query((rs, n) -> new Localizado(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBoolean(3),
                        rs.getBoolean(4)))
                .optional());
    }

    @Override
    public int criar(UUID id, String login, String nome, String email, String registroProfissional, String hashSenha,
                     Set<Papel> papeis) {
        return traduzir(() -> jdbc.sql("""
                    SELECT fluxo.admin_criar_usuario(?, ?, ?, CAST(? AS text), CAST(? AS text), ?, CAST(? AS fluxo.papel[]))
                    """)
                .param(id).param(login).param(nome).param(email).param(registroProfissional).param(hashSenha)
                .param(arrayDePapeis(papeis))
                .query(Integer.class)
                .single());
    }

    @Override
    public int definirPapeis(UUID id, int versaoLida, Set<Papel> papeis) {
        return traduzir(() -> jdbc.sql("SELECT fluxo.admin_definir_papeis(?, ?, CAST(? AS fluxo.papel[]))")
                .param(id).param(versaoLida).param(arrayDePapeis(papeis))
                .query(Integer.class)
                .single());
    }

    @Override
    public int alterarConta(UUID id, int versaoLida, String nome, String email, String registroProfissional) {
        return traduzir(() -> jdbc.sql("SELECT fluxo.admin_alterar_conta(?, ?, ?, CAST(? AS text), CAST(? AS text))")
                .param(id).param(versaoLida).param(nome).param(email).param(registroProfissional)
                .query(Integer.class)
                .single());
    }

    @Override
    public int definirSituacao(UUID id, int versaoLida, boolean ativo) {
        return traduzir(() -> jdbc.sql("SELECT fluxo.admin_definir_situacao(?, ?, ?)")
                .param(id).param(versaoLida).param(ativo)
                .query(Integer.class)
                .single());
    }

    @Override
    public int definirSenhaProvisoria(UUID id, int versaoLida, String hashSenha) {
        return traduzir(() -> jdbc.sql("SELECT fluxo.admin_definir_senha_provisoria(?, ?, ?)")
                .param(id).param(versaoLida).param(hashSenha)
                .query(Integer.class)
                .single());
    }

    // ----------------------------------------------------------------------------

    /** Literal de array só com nomes do enum (lista fechada), sempre passado como parâmetro. */
    private static String arrayDePapeis(Set<Papel> papeis) {
        return papeis.stream().map(Enum::name).sorted().collect(Collectors.joining(",", "{", "}"));
    }

    /** SQLSTATEs das funções/políticas da V11 → erros de aplicação. */
    private static <T> T traduzir(Supplier<T> chamada) {
        try {
            return chamada.get();
        } catch (DataAccessException e) {
            SQLException sql = e.getMostSpecificCause() instanceof SQLException s ? s : null;
            String estado = sql == null ? null : sql.getSQLState();
            String mensagem = sql == null || sql.getMessage() == null ? "" : sql.getMessage();
            if (estado != null) {
                switch (estado) {
                    case "40001" -> throw new ConflitoDeVersaoException();
                    case "P0002" -> throw new RecursoNaoEncontradoException("Usuário");
                    case "55000" -> throw new RegraVioladaException("ULTIMO_ADMINISTRADOR",
                            "A unidade precisa manter ao menos um administrador ativo");
                    case "22023" -> throw new RegraVioladaException("PAPEL_OBRIGATORIO",
                            "Informe ao menos um papel na unidade");
                    // Recusa administrativa das funções da V11 (código próprio; 42501 de GRANT
                    // ausente continua como erro interno — é defeito, não decisão de alcance).
                    case "FX403" -> throw new AcessoNegadoException(Permissao.USUARIO_GERENCIAR);
                    case "23505" -> {
                        if (mensagem.contains("usuario_login_key")) {
                            throw new RegraVioladaException("LOGIN_EM_USO", "Já existe uma conta com este login; "
                                    + "para dar acesso nesta unidade, vincule a conta existente");
                        }
                        if (mensagem.contains("usuario_email_key")) {
                            throw new RegraVioladaException("EMAIL_EM_USO", "E-mail já cadastrado em outra conta");
                        }
                    }
                    default -> { }
                }
            }
            throw e;
        }
    }

    private static Usuario usuario(ResultSet rs, int n) throws SQLException {
        Array a = rs.getArray(9);
        Set<Papel> papeis = EnumSet.noneOf(Papel.class);
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                papeis.add(Papel.valueOf(o.toString()));
            }
        }
        return new Usuario(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getInt(8), papeis, rs.getBoolean(10),
                rs.getBoolean(11));
    }
}
