package br.fluxosaude.identidade.aplicacao;

import static br.fluxosaude.compartilhado.RegraVioladaException.exigir;

import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.compartilhado.Textos;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.PoliticaSenha;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Gestão de usuários e lotações pelo ADMINISTRADOR da unidade ativa (M08, ADR-0006).
 *
 * <p>Dois alcances distintos:
 * <ul>
 *   <li><b>lotação</b> (papéis do usuário NA unidade ativa): conceder, alterar, revogar e
 *       vincular conta existente — basta administrar a unidade ativa;</li>
 *   <li><b>conta</b> (dados cadastrais, ativar/desativar, senha provisória): é global, então
 *       exige administrar TODAS as unidades do usuário. Assim o administrador da unidade A
 *       revoga o acesso na A, mas não desativa nem toma a conta de quem também atua na B.</li>
 * </ul>
 * Ninguém altera os próprios papéis ou a própria conta por aqui (a senha própria é trocada
 * em {@code /api/sessao/senha}). Toda unidade mantém ao menos um administrador ativo
 * (garantido pelo banco, inclusive sob concorrência). Mudanças que afetam o acesso encerram
 * as sessões do usuário após o COMMIT; o banco ainda revalida a cada transação.
 */
public final class ServicoGestaoUsuarios {

    private static final System.Logger LOG = System.getLogger(ServicoGestaoUsuarios.class.getName());

    public static final int LIMITE_LISTAGEM = 1000;
    private static final Pattern LOGIN = Pattern.compile("^[a-z0-9._-]{3,64}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int TENTATIVAS_SENHA = 10;

    public record Lista(List<RepositorioUsuarios.Usuario> itens, boolean truncado) {
    }

    public record NovoUsuario(String login, String nome, String email, String registroProfissional,
                              Set<Papel> papeis) {
    }

    public record DadosConta(String nome, String email, String registroProfissional) {
    }

    public record Resultado(UUID id, int versao) {
    }

    /** {@code senhaProvisoria} é exibida uma única vez; nunca é registrada em log ou auditoria. */
    public record Provisionado(UUID id, int versao, String senhaProvisoria) {
        @Override
        public String toString() {
            return "Provisionado[id=" + id + ", versao=" + versao + "]";
        }
    }

    private final TransacaoUsuarios transacao;
    private final HashDeSenha hash;
    private final SessoesPort sessoes;
    private final Supplier<String> geradorSenha;
    private final Supplier<UUID> ids;

    public ServicoGestaoUsuarios(TransacaoUsuarios transacao, HashDeSenha hash, SessoesPort sessoes,
                                 Supplier<String> geradorSenha, Supplier<UUID> ids) {
        this.transacao = Objects.requireNonNull(transacao);
        this.hash = Objects.requireNonNull(hash);
        this.sessoes = Objects.requireNonNull(sessoes);
        this.geradorSenha = Objects.requireNonNull(geradorSenha);
        this.ids = Objects.requireNonNull(ids);
    }

    // ------------------------------------------------------------------- leitura

    public Lista listar(UsuarioAutenticado adm, ContextoOrigem origem) {
        exigirAdministrador(adm);
        List<RepositorioUsuarios.Usuario> linhas = transacao.executar(adm, origem, r -> r.listar(LIMITE_LISTAGEM + 1));
        boolean truncado = linhas.size() > LIMITE_LISTAGEM;
        return new Lista(truncado ? List.copyOf(linhas.subList(0, LIMITE_LISTAGEM)) : List.copyOf(linhas), truncado);
    }

    public RepositorioUsuarios.Usuario obter(UsuarioAutenticado adm, ContextoOrigem origem, UUID id) {
        exigirAdministrador(adm);
        return transacao.executar(adm, origem, r -> r.obter(id))
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário"));
    }

    /** Conta existente, para vincular à unidade um profissional que já atua em outra. */
    public RepositorioUsuarios.Localizado localizarPorLogin(UsuarioAutenticado adm, ContextoOrigem origem,
                                                           String login) {
        exigirAdministrador(adm);
        String l = login(login);
        return transacao.executar(adm, origem, r -> r.localizarPorLogin(l))
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário"));
    }

    // ------------------------------------------------------------------- lotação

    /** Cria a conta e a lota na unidade ativa, com senha provisória e troca obrigatória. */
    public Provisionado criar(UsuarioAutenticado adm, ContextoOrigem origem, NovoUsuario novo) {
        exigirAdministrador(adm);
        Objects.requireNonNull(novo, "novo usuário");
        String login = login(novo.login());
        DadosConta dados = validar(new DadosConta(novo.nome(), novo.email(), novo.registroProfissional()));
        Set<Papel> papeis = papeis(novo.papeis());
        exigir(!papeis.isEmpty(), "PAPEL_OBRIGATORIO", "Informe ao menos um papel na unidade");
        String senha = senhaProvisoria(login, dados.nome());
        String hashSenha = hash.gerar(senha); // fora da transação: Argon2 é caro
        UUID id = ids.get();
        // Login ou e-mail já em uso: o banco recusa (único) e o adaptador traduz para
        // LOGIN_EM_USO / EMAIL_EM_USO — sem consulta prévia sujeita a corrida.
        int versao = transacao.executar(adm, origem, r -> r.criar(id, login, dados.nome(), dados.email(),
                dados.registroProfissional(), hashSenha, papeis));
        return new Provisionado(id, versao, senha);
    }

    /**
     * Define o conjunto de papéis do usuário NA unidade ativa. Vazio = revoga o acesso nesta
     * unidade (as demais lotações permanecem; sem nenhuma lotação, a conta é desativada).
     * Também vincula conta existente — só se ativa e lotada em outra unidade.
     */
    public Resultado definirPapeis(UsuarioAutenticado adm, ContextoOrigem origem, UUID id, int versaoLida,
                                   Set<Papel> novos) {
        exigirAdministrador(adm);
        exigirOutro(adm, id);
        Set<Papel> papeis = papeis(Objects.requireNonNull(novos, "papéis"));
        int nova = transacao.executar(adm, origem, r -> r.definirPapeis(id, versaoLida, papeis));
        if (nova != versaoLida) {
            encerrarSessoes(id);
        }
        return new Resultado(id, nova);
    }

    // ------------------------------------------------------------------- conta (global)

    public Resultado alterarConta(UsuarioAutenticado adm, ContextoOrigem origem, UUID id, int versaoLida,
                                  DadosConta dadosInformados) {
        exigirAdministrador(adm);
        exigirOutro(adm, id);
        DadosConta d = validar(dadosInformados);
        return transacao.executar(adm, origem, r -> {
            RepositorioUsuarios.Usuario alvo = carregarConta(r, id, versaoLida);
            if (Objects.equals(alvo.nome(), d.nome()) && Objects.equals(alvo.email(), d.email())
                    && Objects.equals(alvo.registroProfissional(), d.registroProfissional())) {
                return new Resultado(id, versaoLida);
            }
            return new Resultado(id, r.alterarConta(id, versaoLida, d.nome(), d.email(), d.registroProfissional()));
        });
    }

    /** Desativa/reativa a conta em TODAS as unidades (por isso exige alcance sobre a conta). */
    public Resultado definirSituacao(UsuarioAutenticado adm, ContextoOrigem origem, UUID id, int versaoLida,
                                     boolean ativo) {
        exigirAdministrador(adm);
        exigirOutro(adm, id);
        Resultado r = transacao.executar(adm, origem, repo -> {
            RepositorioUsuarios.Usuario alvo = carregarConta(repo, id, versaoLida);
            if (alvo.ativo() == ativo) {
                return new Resultado(id, versaoLida);
            }
            return new Resultado(id, repo.definirSituacao(id, versaoLida, ativo));
        });
        if (!ativo && r.versao() != versaoLida) {
            encerrarSessoes(id);
        }
        return r;
    }

    /** Nova senha provisória (ex.: esquecimento): troca obrigatória e sessões encerradas. */
    public Provisionado redefinirSenha(UsuarioAutenticado adm, ContextoOrigem origem, UUID id, int versaoLida) {
        exigirAdministrador(adm);
        exigirOutro(adm, id);
        RepositorioUsuarios.Usuario alvo = transacao.executar(adm, origem, r -> carregarConta(r, id, versaoLida));
        String senha = senhaProvisoria(alvo.login(), alvo.nome());
        String hashSenha = hash.gerar(senha);
        // O banco repete alcance e versão: nada mudou entre a leitura e a gravação.
        int v = transacao.executar(adm, origem, r -> r.definirSenhaProvisoria(id, versaoLida, hashSenha));
        encerrarSessoes(id);
        return new Provisionado(id, v, senha);
    }

    // ----------------------------------------------------------------------------

    private static void exigirAdministrador(UsuarioAutenticado adm) {
        AcessoNegadoException.exigir(adm, Permissao.USUARIO_GERENCIAR);
    }

    /** Autoalteração proibida: privilégios e situação da própria conta só por outro administrador. */
    private static void exigirOutro(UsuarioAutenticado adm, UUID alvo) {
        Objects.requireNonNull(alvo, "usuário");
        exigir(!alvo.equals(adm.usuarioId()), "AUTOALTERACAO",
                "Um administrador não altera os próprios papéis ou a própria conta; peça a outro administrador");
    }

    private static RepositorioUsuarios.Usuario carregarConta(RepositorioUsuarios r, UUID id, int versaoLida) {
        RepositorioUsuarios.Usuario alvo = r.obter(id).orElseThrow(() -> new RecursoNaoEncontradoException("Usuário"));
        if (!alvo.contaGerenciavel()) {
            // Usuário também lotado em unidade que este administrador não administra.
            throw new AcessoNegadoException(Permissao.USUARIO_GERENCIAR);
        }
        if (alvo.versao() != versaoLida) {
            throw new ConflitoDeVersaoException();
        }
        return alvo;
    }

    private static String login(String bruto) {
        exigir(bruto != null, "LOGIN_INVALIDO", "Login obrigatório");
        String l = bruto.strip().toLowerCase(Locale.ROOT);
        exigir(LOGIN.matcher(l).matches(), "LOGIN_INVALIDO",
                "Login deve ter de 3 a 64 caracteres: letras minúsculas, dígitos, '.', '_' ou '-'");
        return l;
    }

    private static DadosConta validar(DadosConta d) {
        Objects.requireNonNull(d, "dados da conta");
        String nome = Textos.obrigatorio(d.nome(), "Nome", 2, 200).replaceAll("\\s+", " ");
        String email = Textos.opcional(d.email(), "E-mail", 3, 254);
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            exigir(EMAIL.matcher(email).matches(), "EMAIL_INVALIDO", "E-mail inválido");
        }
        String registro = Textos.opcional(d.registroProfissional(), "Registro profissional", 2, 40);
        return new DadosConta(nome, email, registro);
    }

    private static Set<Papel> papeis(Set<Papel> informados) {
        Objects.requireNonNull(informados, "papéis");
        exigir(informados.stream().noneMatch(Objects::isNull), "PAPEL_INVALIDO", "Papel inválido");
        return informados.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(informados));
    }

    /** Gera e confere contra a mesma política aplicada às senhas escolhidas pelo usuário. */
    private String senhaProvisoria(String login, String nome) {
        for (int i = 0; i < TENTATIVAS_SENHA; i++) {
            String s = geradorSenha.get();
            try {
                PoliticaSenha.validar(s, login, nome);
                return s;
            } catch (RegraVioladaException e) {
                // improvável (ex.: o login curto apareceu por acaso); gera outra
            }
        }
        throw new IllegalStateException("gerador de senha provisória não produziu senha válida");
    }

    private void encerrarSessoes(UUID usuarioId) {
        try {
            sessoes.encerrarTodas(usuarioId);
        } catch (RuntimeException e) {
            // A operação já foi confirmada; a revalidação por transação no banco ainda impede
            // o uso das permissões antigas. Registra só o tipo do erro (sem dados pessoais).
            LOG.log(System.Logger.Level.WARNING, "falha ao encerrar sessões após alteração de acesso: {0}",
                    e.getClass().getSimpleName());
        }
    }
}
