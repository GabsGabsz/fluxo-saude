package br.fluxosaude.identidade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.aplicacao.GeradorSenhaProvisoria;
import br.fluxosaude.identidade.aplicacao.HashDeSenha;
import br.fluxosaude.identidade.aplicacao.RepositorioUsuarios;
import br.fluxosaude.identidade.aplicacao.ServicoGestaoUsuarios;
import br.fluxosaude.identidade.aplicacao.TransacaoUsuarios;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.PoliticaSenha;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Regras da gestão de usuários com um banco em memória que reproduz o alcance da V11
 * (o comportamento real do PostgreSQL é coberto por t08 e GestaoUsuariosIT).
 */
class ServicoGestaoUsuariosTest {

    static final ContextoOrigem ORIGEM = new ContextoOrigem("10.0.0.1", "teste");
    static final UUID UNIDADE_A = UUID.randomUUID();
    static final UUID UNIDADE_B = UUID.randomUUID();

    // ------------------------------------------------------------------ banco em memória

    static final class Conta {
        final UUID id;
        String login;
        String nome;
        String email;
        String registro;
        boolean ativo = true;
        boolean deveTrocar = true;
        int versao;
        String hash;
        UUID gestora;
        final Map<UUID, Set<Papel>> lotacoes = new HashMap<>();

        Conta(UUID id, String login, String nome) {
            this.id = id;
            this.login = login;
            this.nome = nome;
        }
    }

    static final class Banco implements TransacaoUsuarios {
        final Map<UUID, Conta> contas = new LinkedHashMap<>();
        final List<String> auditoria = new ArrayList<>();

        Conta conta(String login, UUID unidade, Papel... papeis) {
            Conta c = new Conta(UUID.randomUUID(), login, "Pessoa " + login);
            c.lotacoes.put(unidade, EnumSet.of(papeis[0], papeis));
            c.gestora = unidade;
            contas.put(c.id, c);
            return c;
        }

        @Override
        public <T> T executar(UsuarioAutenticado adm, ContextoOrigem origem, Function<RepositorioUsuarios, T> t) {
            return t.apply(new Visao(adm));
        }

        final class Visao implements RepositorioUsuarios {
            final UUID adm;
            final UUID unidade;

            Visao(UsuarioAutenticado u) {
                adm = u.usuarioId();
                unidade = u.unidadeAtiva();
            }

            boolean administra(UUID u) {
                Conta a = contas.get(adm);
                return a != null && a.ativo && a.lotacoes.getOrDefault(u, Set.of()).contains(Papel.ADMINISTRADOR);
            }

            /** Igual à V11: unidade ativa = gestora da conta + administra todas as unidades dela. */
            boolean gerenciavel(Conta c) {
                return !c.id.equals(adm) && unidade.equals(c.gestora) && administra(c.gestora)
                        && c.lotacoes.containsKey(unidade) && c.lotacoes.keySet().stream().allMatch(this::administra);
            }

            Usuario ver(Conta c) {
                return new Usuario(c.id, c.login, c.nome, c.email, c.registro, c.ativo, c.deveTrocar, c.versao,
                        c.lotacoes.get(unidade), c.lotacoes.size() > 1, gerenciavel(c));
            }

            Conta versionada(UUID id, int versao) {
                Conta c = contas.get(id);
                if (c == null) {
                    throw new RecursoNaoEncontradoException("Usuário");
                }
                if (c.versao != versao) {
                    throw new ConflitoDeVersaoException();
                }
                return c;
            }

            @Override public List<Usuario> listar(int limite) {
                return contas.values().stream().filter(c -> c.lotacoes.containsKey(unidade)).limit(limite)
                        .map(this::ver).toList();
            }

            @Override public Optional<Usuario> obter(UUID id) {
                return Optional.ofNullable(contas.get(id)).filter(c -> c.lotacoes.containsKey(unidade)).map(this::ver);
            }

            @Override public Optional<Localizado> localizarPorLogin(String login) {
                return contas.values().stream().filter(c -> c.login.equals(login)).findFirst()
                        .map(c -> new Localizado(c.id, c.versao, c.lotacoes.containsKey(unidade),
                                c.ativo && !c.id.equals(adm) && !c.lotacoes.isEmpty()));
            }

            @Override public int criar(UUID id, String login, String nome, String email, String registro, String h,
                                       Set<Papel> papeis) {
                if (contas.values().stream().anyMatch(c -> c.login.equals(login))) {
                    throw new RegraVioladaException("LOGIN_EM_USO", "login em uso"); // único no banco
                }
                Conta c = new Conta(id, login, nome);
                c.email = email;
                c.registro = registro;
                c.hash = h;
                c.lotacoes.put(unidade, EnumSet.copyOf(papeis));
                c.gestora = unidade;
                contas.put(id, c);
                auditoria.add("USUARIO_CRIADO " + id + " " + papeis);
                return 0;
            }

            @Override public int definirPapeis(UUID id, int versao, Set<Papel> papeis) {
                if (!administra(unidade) || id.equals(adm)) {
                    throw new AcessoNegadoException(null);
                }
                Conta c = versionada(id, versao);
                Set<Papel> antes = c.lotacoes.getOrDefault(unidade, Set.of());
                if (antes.equals(papeis)) {
                    return versao;
                }
                if (antes.isEmpty() && !(c.ativo && !c.lotacoes.isEmpty())) {
                    throw new AcessoNegadoException(null); // conta órfã/inativa não é vinculável
                }
                if (papeis.isEmpty()) {
                    c.lotacoes.remove(unidade);
                    if (unidade.equals(c.gestora)) {
                        c.gestora = null; // nenhuma outra unidade herda a gestão da conta
                    }
                } else {
                    c.lotacoes.put(unidade, EnumSet.copyOf(papeis));
                }
                if (c.lotacoes.isEmpty()) {
                    c.ativo = false; // última lotação removida desativa a conta
                }
                auditoria.add((papeis.isEmpty() ? "ACESSO_REVOGADO " : "PAPEIS ") + id);
                return ++c.versao;
            }

            @Override public int alterarConta(UUID id, int versao, String nome, String email, String registro) {
                Conta c = versionada(id, versao);
                auditoria.add("CONTA_ALTERADA " + id);
                c.nome = nome;
                c.email = email;
                c.registro = registro;
                return ++c.versao;
            }

            @Override public int definirSituacao(UUID id, int versao, boolean ativo) {
                Conta c = versionada(id, versao);
                auditoria.add((ativo ? "CONTA_REATIVADA " : "CONTA_DESATIVADA ") + id);
                c.ativo = ativo;
                return ++c.versao;
            }

            @Override public int definirSenhaProvisoria(UUID id, int versao, String h) {
                Conta c = versionada(id, versao);
                if (!gerenciavel(c)) {
                    throw new AcessoNegadoException(null);
                }
                c.hash = h;
                c.deveTrocar = true;
                auditoria.add("SENHA_PROVISORIA_DEFINIDA " + id);
                return ++c.versao;
            }
        }
    }

    static final class HashFalso implements HashDeSenha {
        @Override public String gerar(String s) { return "h:" + s; }
        @Override public boolean confere(String s, String h) { return h.equals("h:" + s); }
        @Override public String hashFicticio() { return "h:x"; }
    }

    // ------------------------------------------------------------------ cenário

    Banco banco;
    List<UUID> sessoesEncerradas;
    ServicoGestaoUsuarios servico;
    Conta admA;
    Conta admB;
    Conta enfA;

    @BeforeEach
    void setUp() {
        banco = new Banco();
        sessoesEncerradas = new ArrayList<>();
        servico = new ServicoGestaoUsuarios(banco, new HashFalso(), sessoesEncerradas::add,
                new GeradorSenhaProvisoria(), UUID::randomUUID);
        admA = banco.conta("admin.a", UNIDADE_A, Papel.ADMINISTRADOR);
        admB = banco.conta("admin.b", UNIDADE_B, Papel.ADMINISTRADOR);
        enfA = banco.conta("enf.a", UNIDADE_A, Papel.ENFERMAGEM);
    }

    static UsuarioAutenticado sessao(Conta c, UUID unidade) {
        Map<UUID, Set<Papel>> lot = new HashMap<>();
        c.lotacoes.forEach((u, p) -> lot.put(u, Set.copyOf(p)));
        return new UsuarioAutenticado(c.id, c.login, c.nome, lot, unidade, false, 1);
    }

    static String erro(Executable e) {
        return assertThrows(RegraVioladaException.class, e).codigo();
    }

    // ------------------------------------------------------------------ testes

    @Test
    @DisplayName("Cria conta com senha provisória válida, troca obrigatória e auditoria sem a senha")
    void criaUsuario() {
        var p = servico.criar(sessao(admA, UNIDADE_A), ORIGEM, new ServicoGestaoUsuarios.NovoUsuario(
                "  Maria.Souza ", "Maria   Souza", "Maria@Upa.Gov.br", "COREN 123", Set.of(Papel.ENFERMAGEM)));
        Conta c = banco.contas.get(p.id());
        assertEquals("maria.souza", c.login);
        assertEquals("Maria Souza", c.nome);
        assertEquals("maria@upa.gov.br", c.email);
        assertEquals(Set.of(Papel.ENFERMAGEM), c.lotacoes.get(UNIDADE_A));
        assertTrue(c.deveTrocar);
        assertEquals("h:" + p.senhaProvisoria(), c.hash, "só o hash é persistido");
        PoliticaSenha.validar(p.senhaProvisoria(), c.login, c.nome);
        assertEquals(0, p.versao());
        assertTrue(banco.auditoria.stream().noneMatch(a -> a.contains(p.senhaProvisoria())), "senha fora da auditoria");
        assertFalse(p.toString().contains(p.senhaProvisoria()), "toString não expõe a senha");
        assertTrue(banco.auditoria.stream().anyMatch(a -> a.startsWith("USUARIO_CRIADO")));
    }

    @Test
    void validacoesDeCriacao() {
        UsuarioAutenticado adm = sessao(admA, UNIDADE_A);
        assertEquals("LOGIN_EM_USO", erro(() -> servico.criar(adm, ORIGEM,
                new ServicoGestaoUsuarios.NovoUsuario("ENF.A", "Outra Pessoa", null, null, Set.of(Papel.MEDICO)))));
        assertEquals("LOGIN_INVALIDO", erro(() -> servico.criar(adm, ORIGEM,
                new ServicoGestaoUsuarios.NovoUsuario("a b", "Nome Ok", null, null, Set.of(Papel.MEDICO)))));
        assertEquals("PAPEL_OBRIGATORIO", erro(() -> servico.criar(adm, ORIGEM,
                new ServicoGestaoUsuarios.NovoUsuario("sem.papel", "Nome Ok", null, null, Set.of()))));
        assertEquals("EMAIL_INVALIDO", erro(() -> servico.criar(adm, ORIGEM,
                new ServicoGestaoUsuarios.NovoUsuario("mail.ruim", "Nome Ok", "sem-arroba", null, Set.of(Papel.MEDICO)))));
        Set<Papel> comNulo = new HashSet<>();
        comNulo.add(null);
        assertEquals("PAPEL_INVALIDO", erro(() -> servico.criar(adm, ORIGEM,
                new ServicoGestaoUsuarios.NovoUsuario("papel.nulo", "Nome Ok", null, null, comNulo))));
    }

    @Test
    @DisplayName("Sem USUARIO_GERENCIAR (inclusive coordenação e direção) nada é permitido")
    void semPermissao() {
        Conta coord = banco.conta("coord.a", UNIDADE_A, Papel.COORDENACAO_FLUXO, Papel.DIRECAO);
        UsuarioAutenticado u = sessao(coord, UNIDADE_A);
        List<Executable> tentativas = List.of(
                () -> servico.listar(u, ORIGEM),
                () -> servico.obter(u, ORIGEM, enfA.id),
                () -> servico.localizarPorLogin(u, ORIGEM, "enf.a"),
                () -> servico.criar(u, ORIGEM, new ServicoGestaoUsuarios.NovoUsuario("x.y", "X Y", null, null,
                        Set.of(Papel.ADMINISTRADOR))),
                () -> servico.definirPapeis(u, ORIGEM, enfA.id, 0, Set.of(Papel.ADMINISTRADOR)),
                () -> servico.alterarConta(u, ORIGEM, enfA.id, 0, new ServicoGestaoUsuarios.DadosConta("N N", null, null)),
                () -> servico.definirSituacao(u, ORIGEM, enfA.id, 0, false),
                () -> servico.redefinirSenha(u, ORIGEM, enfA.id, 0));
        for (Executable e : tentativas) {
            assertThrows(AcessoNegadoException.class, e);
        }
        assertEquals(Set.of(Papel.ENFERMAGEM), enfA.lotacoes.get(UNIDADE_A));
        assertTrue(enfA.ativo);
    }

    @Test
    @DisplayName("Autoalteração proibida: papéis, conta, situação e senha da própria conta")
    void autoalteracao() {
        UsuarioAutenticado adm = sessao(admA, UNIDADE_A);
        assertEquals("AUTOALTERACAO", erro(() -> servico.definirPapeis(adm, ORIGEM, admA.id, 0,
                Set.of(Papel.ADMINISTRADOR, Papel.COORDENACAO_FLUXO))));
        assertEquals("AUTOALTERACAO", erro(() -> servico.definirPapeis(adm, ORIGEM, admA.id, 0, Set.of())));
        assertEquals("AUTOALTERACAO", erro(() -> servico.definirSituacao(adm, ORIGEM, admA.id, 0, false)));
        assertEquals("AUTOALTERACAO", erro(() -> servico.redefinirSenha(adm, ORIGEM, admA.id, 0)));
        assertEquals("AUTOALTERACAO", erro(() -> servico.alterarConta(adm, ORIGEM, admA.id, 0,
                new ServicoGestaoUsuarios.DadosConta("Outro Nome", null, null))));
        assertEquals(Set.of(Papel.ADMINISTRADOR), admA.lotacoes.get(UNIDADE_A));
    }

    @Test
    @DisplayName("Alterar papéis encerra as sessões; sem mudança, nada acontece")
    void papeisEncerramSessoes() {
        UsuarioAutenticado adm = sessao(admA, UNIDADE_A);
        var r = servico.definirPapeis(adm, ORIGEM, enfA.id, 0, Set.of(Papel.ENFERMAGEM, Papel.MEDICO));
        assertEquals(1, r.versao());
        assertEquals(List.of(enfA.id), sessoesEncerradas);
        var igual = servico.definirPapeis(adm, ORIGEM, enfA.id, 1, Set.of(Papel.MEDICO, Papel.ENFERMAGEM));
        assertEquals(1, igual.versao());
        assertEquals(1, sessoesEncerradas.size(), "sem mudança não encerra sessões");
    }

    @Test
    @DisplayName("Conflito de versão: nada é sobrescrito")
    void conflitoDeVersao() {
        UsuarioAutenticado adm = sessao(admA, UNIDADE_A);
        servico.definirPapeis(adm, ORIGEM, enfA.id, 0, Set.of(Papel.MEDICO));
        assertThrows(ConflitoDeVersaoException.class,
                () -> servico.definirPapeis(adm, ORIGEM, enfA.id, 0, Set.of(Papel.TRANSPORTE)));
        assertThrows(ConflitoDeVersaoException.class, () -> servico.definirSituacao(adm, ORIGEM, enfA.id, 0, false));
        assertThrows(ConflitoDeVersaoException.class, () -> servico.redefinirSenha(adm, ORIGEM, enfA.id, 0));
        assertEquals(Set.of(Papel.MEDICO), enfA.lotacoes.get(UNIDADE_A));
        assertTrue(enfA.ativo);
    }

    @Test
    @DisplayName("Usuário em duas unidades: a A revoga só o acesso na A; desativar e senha exigem alcance total")
    void usuarioEmDuasUnidades() {
        // admin.b vincula a enfermeira da A também à B
        UsuarioAutenticado admNaB = sessao(admB, UNIDADE_B);
        var loc = servico.localizarPorLogin(admNaB, ORIGEM, "ENF.A");
        assertFalse(loc.lotadoNaUnidade());
        assertTrue(loc.vinculavel());
        servico.definirPapeis(admNaB, ORIGEM, loc.id(), loc.versao(), Set.of(Papel.ENFERMAGEM));

        UsuarioAutenticado admNaA = sessao(admA, UNIDADE_A);
        var vista = servico.obter(admNaA, ORIGEM, enfA.id);
        assertTrue(vista.possuiOutrasUnidades());
        assertFalse(vista.contaGerenciavel());
        int v = vista.versao();
        assertThrows(AcessoNegadoException.class, () -> servico.definirSituacao(admNaA, ORIGEM, enfA.id, v, false));
        assertThrows(AcessoNegadoException.class, () -> servico.redefinirSenha(admNaA, ORIGEM, enfA.id, v));
        assertThrows(AcessoNegadoException.class, () -> servico.alterarConta(admNaA, ORIGEM, enfA.id, v,
                new ServicoGestaoUsuarios.DadosConta("Outro Nome", null, null)));
        assertTrue(enfA.ativo);

        servico.definirPapeis(admNaA, ORIGEM, enfA.id, v, Set.of());
        assertFalse(enfA.lotacoes.containsKey(UNIDADE_A), "acesso na A revogado");
        assertEquals(Set.of(Papel.ENFERMAGEM), enfA.lotacoes.get(UNIDADE_B), "acesso na B intacto");
        assertTrue(enfA.ativo, "conta continua ativa");
        assertTrue(sessoesEncerradas.contains(enfA.id));
        assertThrows(RecursoNaoEncontradoException.class, () -> servico.obter(admNaA, ORIGEM, enfA.id));
        // A B vinculou a conta, mas não a gere: sem "tomada" por redefinição de senha
        var naB = servico.obter(admNaB, ORIGEM, enfA.id);
        assertFalse(naB.contaGerenciavel());
        assertThrows(AcessoNegadoException.class, () -> servico.redefinirSenha(admNaB, ORIGEM, enfA.id, naB.versao()));
    }

    @Test
    @DisplayName("Desativação com alcance encerra sessões; senha provisória obriga troca")
    void desativacaoESenha() {
        UsuarioAutenticado adm = sessao(admA, UNIDADE_A);
        var s = servico.redefinirSenha(adm, ORIGEM, enfA.id, 0);
        assertEquals("h:" + s.senhaProvisoria(), enfA.hash);
        assertTrue(enfA.deveTrocar);
        assertEquals(1, s.versao());
        var d = servico.definirSituacao(adm, ORIGEM, enfA.id, 1, false);
        assertEquals(2, d.versao());
        assertFalse(enfA.ativo);
        assertEquals(List.of(enfA.id, enfA.id), sessoesEncerradas);
        assertTrue(banco.auditoria.stream().anyMatch(a -> a.startsWith("CONTA_DESATIVADA")));
        // Reativação não encerra sessões (não há) e é auditada
        servico.definirSituacao(adm, ORIGEM, enfA.id, 2, true);
        assertTrue(enfA.ativo);
        assertEquals(2, sessoesEncerradas.size());
        assertTrue(banco.auditoria.stream().noneMatch(a -> a.contains(s.senhaProvisoria())));
    }

    @Test
    void alteracaoDeConta() {
        UsuarioAutenticado adm = sessao(admA, UNIDADE_A);
        var r = servico.alterarConta(adm, ORIGEM, enfA.id, 0,
                new ServicoGestaoUsuarios.DadosConta(" Enfermeira  Ana ", "ana@upa.br", null));
        assertEquals(1, r.versao());
        assertEquals("Enfermeira Ana", enfA.nome);
        var igual = servico.alterarConta(adm, ORIGEM, enfA.id, 1,
                new ServicoGestaoUsuarios.DadosConta("Enfermeira Ana", "ana@upa.br", null));
        assertEquals(1, igual.versao(), "sem mudança, sem nova versão");
        assertTrue(sessoesEncerradas.isEmpty(), "dados cadastrais não afetam sessões");
    }

    @Test
    @DisplayName("Última lotação removida desativa a conta; conta órfã não pode ser vinculada por outra unidade")
    void contaOrfa() {
        UsuarioAutenticado admNaA = sessao(admA, UNIDADE_A);
        servico.definirPapeis(admNaA, ORIGEM, enfA.id, 0, Set.of());
        assertFalse(enfA.ativo, "sem lotação, conta desativada");
        UsuarioAutenticado admNaB = sessao(admB, UNIDADE_B);
        var loc = servico.localizarPorLogin(admNaB, ORIGEM, "enf.a");
        assertFalse(loc.vinculavel());
        assertThrows(AcessoNegadoException.class,
                () -> servico.definirPapeis(admNaB, ORIGEM, enfA.id, loc.versao(), Set.of(Papel.ADMINISTRADOR)));
        assertFalse(enfA.lotacoes.containsKey(UNIDADE_B));
    }

    @Test
    void falhaAoEncerrarSessaoNaoDesfazOperacao() {
        servico = new ServicoGestaoUsuarios(banco, new HashFalso(), id -> {
            throw new IllegalStateException("repositório de sessões indisponível");
        }, new GeradorSenhaProvisoria(), UUID::randomUUID);
        var r = servico.definirPapeis(sessao(admA, UNIDADE_A), ORIGEM, enfA.id, 0, Set.of(Papel.MEDICO));
        assertEquals(1, r.versao());
        assertEquals(Set.of(Papel.MEDICO), enfA.lotacoes.get(UNIDADE_A));
    }

    @Test
    void listaSoDaUnidadeAtiva() {
        var lista = servico.listar(sessao(admA, UNIDADE_A), ORIGEM);
        assertFalse(lista.truncado());
        assertEquals(Set.of(admA.id, enfA.id), new HashSet<>(lista.itens().stream().map(RepositorioUsuarios.Usuario::id)
                .toList()));
        assertFalse(lista.itens().stream().filter(u -> u.id().equals(admA.id)).findFirst().orElseThrow()
                .contaGerenciavel(), "a própria conta não é gerenciável");
    }

    @Test
    void geradorProduzSenhasDistintasEFortes() {
        GeradorSenhaProvisoria g = new GeradorSenhaProvisoria();
        Set<String> vistas = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String s = g.get();
            assertTrue(s.matches("^[a-hj-km-np-z2-9]{5}(-[a-hj-km-np-z2-9]{5}){3}$"), s);
            assertTrue(vistas.add(s));
        }
        assertNotEquals(g.get(), g.get());
    }
}
