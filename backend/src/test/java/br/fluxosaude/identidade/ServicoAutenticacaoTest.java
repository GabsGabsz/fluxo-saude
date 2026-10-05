package br.fluxosaude.identidade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.aplicacao.CredenciaisPort;
import br.fluxosaude.identidade.aplicacao.HashDeSenha;
import br.fluxosaude.identidade.aplicacao.ResultadoAutenticacao;
import br.fluxosaude.identidade.aplicacao.ServicoAutenticacao;
import br.fluxosaude.identidade.dominio.LimitadorDeTentativas;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServicoAutenticacaoTest {

    static final Instant AGORA = Instant.parse("2026-10-05T12:00:00Z");
    static final UUID USUARIO = UUID.fromString("11111111-1111-1111-1111-000000000002");
    static final UUID UNIDADE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final ContextoOrigem ORIGEM = new ContextoOrigem("10.0.0.1", "c1");

    /** Hash de teste: "h:" + senha. Conta as verificações para provar equalização de tempo. */
    static final class HashFalso implements HashDeSenha {
        int verificacoes;
        @Override public String gerar(String s) { return "h:" + s; }
        @Override public boolean confere(String s, String h) { verificacoes++; return h.equals("h:" + s); }
        @Override public String hashFicticio() { return "h:" + UUID.randomUUID(); }
    }

    static final class CredenciaisFalsas implements CredenciaisPort {
        final Map<String, Credencial> porLogin = new HashMap<>();
        final Map<UUID, Map<UUID, Set<Papel>>> lotacoes = new HashMap<>();
        final List<String> tentativas = new ArrayList<>();
        String hashGravado;

        @Override public Optional<Credencial> buscarPorLogin(String login, ContextoOrigem o) {
            return Optional.ofNullable(porLogin.get(login));
        }
        @Override public Optional<Instant> registrarTentativa(UUID id, boolean sucesso, ContextoOrigem o) {
            tentativas.add(id + ":" + sucesso);
            return Optional.empty();
        }
        @Override public void registrarTentativaDuranteBloqueio(UUID id, ContextoOrigem o) {
            tentativas.add(id + ":bloqueada");
        }
        @Override public void registrarFalhaNaTrocaDeSenha(UUID id, ContextoOrigem o) {
            tentativas.add(id + ":troca-falhou");
        }
        @Override public Map<UUID, Set<Papel>> lotacoes(UUID id, ContextoOrigem o) {
            return lotacoes.getOrDefault(id, Map.of());
        }
        @Override public Perfil perfil(UUID id, ContextoOrigem o) { return new Perfil("enf.a", "Enfermeira A"); }
        @Override public String hashAtual(UUID id, ContextoOrigem o) { return porLogin.get("enf.a").senhaHash(); }
        @Override public void gravarNovaSenha(UUID id, String novoHash, ContextoOrigem o) { hashGravado = novoHash; }
    }

    HashFalso hash;
    CredenciaisFalsas cred;
    ServicoAutenticacao servico;

    @BeforeEach
    void setUp() {
        hash = new HashFalso();
        cred = new CredenciaisFalsas();
        cred.porLogin.put("enf.a", new CredenciaisPort.Credencial(USUARIO, "h:senha correta longa", true, false, null));
        cred.lotacoes.put(USUARIO, Map.of(UNIDADE, Set.of(Papel.ENFERMAGEM)));
        Clock clock = Clock.fixed(AGORA, ZoneOffset.UTC);
        servico = new ServicoAutenticacao(cred, hash, new ServicoAutenticacao.Limites(
                new LimitadorDeTentativas(10, Duration.ofMinutes(5), 1000, clock),
                new LimitadorDeTentativas(5, Duration.ofMinutes(5), 1000, clock),
                new LimitadorDeTentativas(3, Duration.ofMinutes(15), 1000, clock),
                Duration.ofMinutes(5)), clock);
    }

    @Test
    void sucessoNormalizaLoginEAudita() {
        ResultadoAutenticacao r = servico.autenticar("  ENF.A ", "senha correta longa", ORIGEM);
        UsuarioAutenticado u = ((ResultadoAutenticacao.Sucesso) r).usuario();
        assertEquals(USUARIO, u.usuarioId());
        assertEquals(UNIDADE, u.unidadeAtiva());
        assertEquals(List.of(USUARIO + ":true"), cred.tentativas);
    }

    @Test
    @DisplayName("Login inexistente também verifica hash (tempo equalizado) e é auditado")
    void loginInexistente() {
        ResultadoAutenticacao r = servico.autenticar("ninguem", "qualquer coisa", ORIGEM);
        assertTrue(r instanceof ResultadoAutenticacao.Falha);
        assertEquals(1, hash.verificacoes);
        assertEquals(List.of("null:false"), cred.tentativas);
        // login em formato inválido (ex.: injeção) nem chega ao banco, mas o tempo é o mesmo
        servico.autenticar("x' OR 1=1 --", "a", ORIGEM);
        assertEquals(2, hash.verificacoes);
    }

    @Test
    void senhaErrada() {
        ResultadoAutenticacao r = servico.autenticar("enf.a", "errada", ORIGEM);
        assertEquals(ResultadoAutenticacao.Motivo.CREDENCIAIS_INVALIDAS, ((ResultadoAutenticacao.Falha) r).motivo());
        assertEquals(List.of(USUARIO + ":false"), cred.tentativas);
    }

    @Test
    @DisplayName("Conta bloqueada falha mesmo com a senha certa e não prolonga o bloqueio")
    void contaBloqueada() {
        cred.porLogin.put("enf.a", new CredenciaisPort.Credencial(USUARIO, "h:senha correta longa", true, false,
                AGORA.plus(Duration.ofMinutes(3))));
        ResultadoAutenticacao r = servico.autenticar("enf.a", "senha correta longa", ORIGEM);
        assertEquals(ResultadoAutenticacao.Motivo.CONTA_BLOQUEADA, ((ResultadoAutenticacao.Falha) r).motivo());
        assertEquals(List.of(USUARIO + ":bloqueada"), cred.tentativas, "auditada sem incrementar falhas");
        assertEquals(1, hash.verificacoes, "tempo equalizado também no bloqueio");
    }

    @Test
    void contaInativaESemLotacao() {
        cred.porLogin.put("enf.a", new CredenciaisPort.Credencial(USUARIO, "h:senha correta longa", false, false, null));
        assertEquals(ResultadoAutenticacao.Motivo.CONTA_INATIVA,
                ((ResultadoAutenticacao.Falha) servico.autenticar("enf.a", "senha correta longa", ORIGEM)).motivo());
        cred.porLogin.put("enf.a", new CredenciaisPort.Credencial(USUARIO, "h:senha correta longa", true, false, null));
        cred.lotacoes.clear();
        assertEquals(ResultadoAutenticacao.Motivo.SEM_LOTACAO,
                ((ResultadoAutenticacao.Falha) servico.autenticar("enf.a", "senha correta longa", ORIGEM)).motivo());
    }

    @Test
    @DisplayName("Limite por origem+login: 6ª falha em 5 min é recusada antes de tocar no banco")
    void limitePorOrigemELogin() {
        for (int i = 0; i < 5; i++) {
            servico.autenticar("enf.a", "errada", ORIGEM);
        }
        int antes = cred.tentativas.size();
        assertTrue(servico.autenticar("enf.a", "senha correta longa", ORIGEM) instanceof ResultadoAutenticacao.LimiteExcedido);
        assertEquals(antes, cred.tentativas.size());
        assertTrue(servico.autenticar("enf.a", "senha correta longa", new ContextoOrigem("10.0.0.2", "c2"))
                instanceof ResultadoAutenticacao.Sucesso, "outra origem não é afetada");
    }

    @Test
    @DisplayName("Sucesso NÃO zera o limite por origem (quem tem conta válida não 'limpa' o contador)")
    void sucessoNaoZeraLimite() {
        cred.porLogin.put("outro", new CredenciaisPort.Credencial(UUID.randomUUID(), "h:x", true, false, null));
        for (int i = 0; i < 4; i++) {
            servico.autenticar("outro", "errada", ORIGEM);
        }
        assertTrue(servico.autenticar("enf.a", "senha correta longa", ORIGEM) instanceof ResultadoAutenticacao.Sucesso);
        for (int i = 0; i < 6; i++) {
            servico.autenticar("terceiro" + i, "errada", ORIGEM);
        }
        assertTrue(servico.autenticar("enf.a", "senha correta longa", ORIGEM) instanceof ResultadoAutenticacao.LimiteExcedido,
                "10 falhas da mesma origem bloqueiam a origem, mesmo com sucessos no meio");
    }

    @Test
    void trocaDeSenhaComLimiteEAuditoria() {
        UsuarioAutenticado u = ((ResultadoAutenticacao.Sucesso) servico.autenticar("enf.a", "senha correta longa", ORIGEM)).usuario();
        for (int i = 0; i < 3; i++) {
            assertThrows(RegraVioladaException.class, () -> servico.trocarSenha(u, "errada", "nova frase bem comprida", ORIGEM));
        }
        assertTrue(cred.tentativas.contains(USUARIO + ":troca-falhou"));
        assertThrows(br.fluxosaude.compartilhado.LimiteExcedidoException.class,
                () -> servico.trocarSenha(u, "senha correta longa", "nova frase bem comprida", ORIGEM));
    }

    @Test
    void trocaDeSenha() {
        cred.porLogin.put("enf.a", new CredenciaisPort.Credencial(USUARIO, "h:senha correta longa", true, true, null));
        UsuarioAutenticado u = ((ResultadoAutenticacao.Sucesso) servico.autenticar("enf.a", "senha correta longa", ORIGEM)).usuario();
        assertTrue(u.deveTrocarSenha());
        assertEquals("SENHA_ATUAL_INCORRETA", assertThrows(RegraVioladaException.class,
                () -> servico.trocarSenha(u, "errada", "nova frase bem comprida", ORIGEM)).codigo());
        assertEquals("SENHA_CURTA", assertThrows(RegraVioladaException.class,
                () -> servico.trocarSenha(u, "senha correta longa", "curta", ORIGEM)).codigo());
        assertEquals("SENHA_REPETIDA", assertThrows(RegraVioladaException.class,
                () -> servico.trocarSenha(u, "senha correta longa", "senha correta longa", ORIGEM)).codigo());
        UsuarioAutenticado depois = servico.trocarSenha(u, "senha correta longa", "nova frase bem comprida", ORIGEM);
        assertFalse(depois.deveTrocarSenha());
        assertEquals("h:nova frase bem comprida", cred.hashGravado);
    }
}
