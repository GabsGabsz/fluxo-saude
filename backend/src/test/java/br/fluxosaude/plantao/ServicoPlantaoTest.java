package br.fluxosaude.plantao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.compartilhado.ConflitoDeEstadoException;
import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import br.fluxosaude.episodio.dominio.NaturezaEtapa;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.plantao.aplicacao.RepositorioPlantao;
import br.fluxosaude.plantao.aplicacao.ServicoPlantao;
import br.fluxosaude.plantao.aplicacao.TransacaoPlantao;
import br.fluxosaude.plantao.dominio.CasoAtual;
import br.fluxosaude.plantao.dominio.ConteudoPassagem;
import br.fluxosaude.plantao.dominio.PendenciaAtual;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fluxo da passagem (preparar → entregar → receber), conflitos e permissões, com relógio controlado. */
class ServicoPlantaoTest {

    static final UUID UNIDADE = UUID.randomUUID();
    static final ContextoOrigem ORIGEM = new ContextoOrigem("10.0.0.1", "teste");
    static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");

    /** Relógio que o teste avança. */
    static final class Relogio extends Clock {
        Instant agora = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    /** Banco em memória: estado dos episódios, passagens e auditoria. */
    static final class Banco implements TransacaoPlantao, RepositorioPlantao {
        final List<CasoAtual> casos = new ArrayList<>();
        final List<RegraAlerta> regras = new ArrayList<>();
        final Map<UUID, Passagem> passagens = new LinkedHashMap<>();
        final Map<UUID, ConteudoPassagem> conteudos = new HashMap<>();
        final List<String> auditoria = new ArrayList<>();
        Relogio relogio;
        UsuarioAutenticado atual;

        @Override
        public <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioPlantao, T> t) {
            atual = usuario;
            return t.apply(this);
        }

        @Override
        public List<CasoAtual> casosAbertos(int limite) {
            return casos.stream().limit(limite).toList();
        }

        @Override
        public List<RegraAlerta> regrasAtivas() {
            return regras;
        }

        @Override
        public Optional<Passagem> pendente() {
            return passagens.values().stream().filter(p -> p.status().equals("ENTREGUE")).findFirst();
        }

        @Override
        public Optional<Instant> ultimaRecebidaEm() {
            return passagens.values().stream().filter(p -> p.status().equals("RECEBIDA")).map(Passagem::entregueEm)
                    .max(Instant::compareTo);
        }

        @Override
        public void inserir(UUID id, ConteudoPassagem c, String observacao) {
            passagens.put(id, new Passagem(id, "ENTREGUE", ultimaRecebidaEm().orElse(null), atual.usuarioId(), atual.nome(),
                    relogio.instant(), c.assinatura(), c.totalCasos(), c.totalCriticos(), c.totalTransferencias(),
                    c.totalPendencias(), c.totalVencidas(), observacao, null, null, null, null, null, null, null, null, 0));
            conteudos.put(id, c);
        }

        @Override
        public Optional<Passagem> passagem(UUID id) {
            return Optional.ofNullable(passagens.get(id));
        }

        @Override
        public Optional<ConteudoPassagem> conteudo(UUID id) {
            return Optional.ofNullable(conteudos.get(id));
        }

        @Override
        public List<Passagem> historico(int limite) {
            return List.copyOf(passagens.values());
        }

        @Override
        public void receber(UUID id, int versaoLida, String assinatura, Map<String, Integer> diferencas) {
            Passagem p = passagens.get(id);
            if (p.versao() != versaoLida) {
                throw new ConflitoDeVersaoException();
            }
            passagens.put(id, new Passagem(id, "RECEBIDA", p.periodoInicio(), p.entreguePor(), p.entreguePorNome(),
                    p.entregueEm(), p.assinatura(), p.totalCasos(), p.totalCriticos(), p.totalTransferencias(),
                    p.totalPendencias(), p.totalVencidas(), p.observacao(), atual.usuarioId(), atual.nome(),
                    relogio.instant(), diferencas, null, null, null, null, p.versao() + 1));
        }

        @Override
        public void cancelar(UUID id, int versaoLida, String justificativa) {
            Passagem p = passagens.get(id);
            if (p.versao() != versaoLida) {
                throw new ConflitoDeVersaoException();
            }
            passagens.put(id, new Passagem(id, "CANCELADA", p.periodoInicio(), p.entreguePor(), p.entreguePorNome(),
                    p.entregueEm(), p.assinatura(), p.totalCasos(), p.totalCriticos(), p.totalTransferencias(),
                    p.totalPendencias(), p.totalVencidas(), p.observacao(), null, null, null, null, atual.usuarioId(),
                    atual.nome(), relogio.instant(), justificativa, p.versao() + 1));
        }

        @Override
        public Nomes nomes(Collection<UUID> episodios, Collection<UUID> pendencias) {
            Map<UUID, String> pac = new HashMap<>();
            episodios.forEach(e -> pac.put(e, "Paciente " + e.toString().substring(30)));
            Map<UUID, String> pend = new HashMap<>();
            pendencias.forEach(p -> pend.put(p, "Ação " + p.toString().substring(30)));
            return new Nomes(pac, pend);
        }

        @Override
        public void auditar(String acao, UUID passagemId, Map<String, Object> dados) {
            auditoria.add(acao);
        }
    }

    Banco banco;
    Relogio relogio;
    ServicoPlantao servico;
    final UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
    final UsuarioAutenticado medico = usuario(Papel.MEDICO);

    static UsuarioAutenticado usuario(Papel... papeis) {
        return new UsuarioAutenticado(UUID.randomUUID(), "u." + papeis[0].name().toLowerCase(), "Usuário " + papeis[0],
                Map.of(UNIDADE, Set.of(papeis)), UNIDADE, false, 1);
    }

    static CasoAtual caso(int n, int versao, List<PendenciaAtual> pendencias) {
        return new CasoAtual(new UUID(0, n), versao, new UUID(1, 1), NaturezaEtapa.ESPERA, new UUID(2, 2), null, null, null,
                T0.minus(Duration.ofHours(5)), T0.minus(Duration.ofHours(1)), T0.minus(Duration.ofMinutes(10)),
                false, false, pendencias);
    }

    static PendenciaAtual pendencia(int n, int versao, Instant prazo) {
        return new PendenciaAtual(new UUID(3, n), versao, CategoriaBloqueio.LOGISTICA, CriticidadeOperacional.ALTA, prazo,
                null, new UUID(2, 2), null);
    }

    @BeforeEach
    void setUp() {
        banco = new Banco();
        relogio = new Relogio();
        banco.relogio = relogio;
        servico = new ServicoPlantao(banco, relogio, UUID::randomUUID);
        banco.casos.add(caso(1, 0, List.of(pendencia(1, 0, T0.plus(Duration.ofHours(2))))));
        banco.casos.add(caso(2, 0, List.of()));
    }

    @Test
    void fluxoCompletoEntregaPorUmRecebimentoPorOutro() {
        ServicoPlantao.Previa previa = servico.previa(enf, ORIGEM);
        assertEquals(2, previa.conteudo().totalCasos());
        assertNull(previa.periodoInicio(), "primeira passagem: sem início de período");
        assertEquals("Paciente " + new UUID(0, 1).toString().substring(30),
                previa.nomes().pacientes().get(new UUID(0, 1)), "nomes vêm do estado atual, à parte do conteúdo");

        UUID id = servico.entregar(enf, ORIGEM, previa.assinatura(), "  Leito 4 em higienização  ");
        RepositorioPlantao.Passagem p = banco.passagens.get(id);
        assertEquals("ENTREGUE", p.status());
        assertEquals("Leito 4 em higienização", p.observacao());
        assertEquals(previa.assinatura(), p.assinatura());

        ServicoPlantao.Detalhe d = servico.obter(medico, ORIGEM, id);
        assertTrue(d.integra());
        assertTrue(d.diferencas().vazia());
        servico.receber(medico, ORIGEM, id, p.versao(), d.assinaturaRecebimento());
        assertEquals("RECEBIDA", banco.passagens.get(id).status());
        assertEquals(medico.usuarioId(), banco.passagens.get(id).recebidaPor());
        assertEquals(List.of("PASSAGEM_ENTREGUE", "PASSAGEM_RECEBIDA"), banco.auditoria);

        // A próxima passagem representa o período desde a entrega da última recebida.
        relogio.agora = T0.plus(Duration.ofHours(12));
        ServicoPlantao.Previa proxima = servico.previa(medico, ORIGEM);
        assertEquals(p.entregueEm(), proxima.periodoInicio());
    }

    @Test
    void entregaComConteudoDesatualizadoNaoGravaNada() {
        ServicoPlantao.Previa previa = servico.previa(enf, ORIGEM);
        // Outra pessoa resolve a pendência (episódio muda de versão) antes do clique.
        banco.casos.set(0, caso(1, 1, List.of()));
        ConflitoDeEstadoException e = assertThrows(ConflitoDeEstadoException.class,
                () -> servico.entregar(enf, ORIGEM, previa.assinatura(), null));
        assertEquals("PASSAGEM_DESATUALIZADA", e.codigo());
        assertTrue(banco.passagens.isEmpty(), "nada gravado: nenhuma confirmação silenciosa");
        // Nova leitura + nova ação explícita
        String nova = servico.previa(enf, ORIGEM).assinatura();
        assertNotNull(servico.entregar(enf, ORIGEM, nova, null));
    }

    @Test
    void prazoQueVenceEntreLeituraEConfirmacaoTambemExigeNovaLeitura() {
        relogio.agora = T0.plus(Duration.ofHours(2));                // prazo exatamente agora: não vencida
        ServicoPlantao.Previa previa = servico.previa(enf, ORIGEM);
        assertEquals(0, previa.conteudo().totalVencidas());
        relogio.agora = relogio.agora.plusSeconds(1);                // venceu antes do clique
        assertEquals("PASSAGEM_DESATUALIZADA", assertThrows(ConflitoDeEstadoException.class,
                () -> servico.entregar(enf, ORIGEM, previa.assinatura(), null)).codigo());
    }

    @Test
    void recebimentoComDiferencasMudadasEhRecusadoESemEfeito() {
        UUID id = servico.entregar(enf, ORIGEM, servico.previa(enf, ORIGEM).assinatura(), null);
        ServicoPlantao.Detalhe d = servico.obter(medico, ORIGEM, id);
        // Episódio 2 encerrado depois que o recebedor abriu a passagem.
        banco.casos.remove(1);
        assertEquals("RECEBIMENTO_DESATUALIZADO", assertThrows(ConflitoDeEstadoException.class,
                () -> servico.receber(medico, ORIGEM, id, 0, d.assinaturaRecebimento())).codigo());
        assertEquals("ENTREGUE", banco.passagens.get(id).status(), "nada confirmado");
        // Releitura mostra o encerramento como diferença; nova confirmação explícita
        ServicoPlantao.Detalhe relido = servico.obter(medico, ORIGEM, id);
        assertEquals(List.of(new UUID(0, 2)), relido.diferencas().casosEncerrados());
        servico.receber(medico, ORIGEM, id, 0, relido.assinaturaRecebimento());
        assertEquals(Integer.valueOf(1), banco.passagens.get(id).diferencasRecebimento().get("casosEncerrados"));
    }

    @Test
    void quemEntregouNaoRecebeEOutrasRegras() {
        String ass = servico.previa(enf, ORIGEM).assinatura();
        UUID id = servico.entregar(enf, ORIGEM, ass, null);
        String recebimento = servico.obter(enf, ORIGEM, id).assinaturaRecebimento();
        assertEquals("RECEBEDOR_E_ENTREGADOR", assertThrows(RegraVioladaException.class,
                () -> servico.receber(enf, ORIGEM, id, 0, recebimento)).codigo());
        // Uma pendente por unidade
        assertEquals("PASSAGEM_PENDENTE", assertThrows(ConflitoDeEstadoException.class,
                () -> servico.entregar(medico, ORIGEM, ass, null)).codigo());
        // Versão lida desatualizada
        assertThrows(ConflitoDeVersaoException.class, () -> servico.receber(medico, ORIGEM, id, 7, recebimento));
        // Cancelamento: só o autor, com justificativa
        assertEquals("SO_AUTOR_CANCELA", assertThrows(RegraVioladaException.class,
                () -> servico.cancelar(medico, ORIGEM, id, 0, "Entregue por engano")).codigo());
        assertThrows(RegraVioladaException.class, () -> servico.cancelar(enf, ORIGEM, id, 0, " "));
        servico.cancelar(enf, ORIGEM, id, 0, "Entregue por engano");
        assertEquals("CANCELADA", banco.passagens.get(id).status());
        assertEquals("PASSAGEM_JA_ENCERRADA", assertThrows(ConflitoDeEstadoException.class,
                () -> servico.receber(medico, ORIGEM, id, 1, recebimento)).codigo());
        assertNull(servico.obter(medico, ORIGEM, id).diferencas(), "encerrada: sem diferenças a confirmar");
        assertThrows(RecursoNaoEncontradoException.class, () -> servico.obter(medico, ORIGEM, UUID.randomUUID()));
        assertEquals("ASSINATURA_INVALIDA", assertThrows(RegraVioladaException.class,
                () -> servico.entregar(enf, ORIGEM, "abc", null)).codigo());
    }

    @Test
    void passagemNaoAlteraEpisodiosNemPendencias() {
        List<CasoAtual> antes = List.copyOf(banco.casos);
        UUID id = servico.entregar(enf, ORIGEM, servico.previa(enf, ORIGEM).assinatura(), null);
        servico.receber(medico, ORIGEM, id, 0, servico.obter(medico, ORIGEM, id).assinaturaRecebimento());
        assertEquals(antes, banco.casos, "nenhum efeito sobre o estado dos episódios/pendências");
    }

    @Test
    void semPassagemParcial() {
        banco.casos.clear();
        for (int i = 0; i < ServicoPlantao.LIMITE_CASOS + 1; i++) {
            banco.casos.add(caso(10 + i, 0, List.of()));
        }
        assertEquals("PASSAGEM_GRANDE_DEMAIS", assertThrows(RegraVioladaException.class,
                () -> servico.previa(enf, ORIGEM)).codigo());
        banco.casos.remove(0);
        assertEquals(ServicoPlantao.LIMITE_CASOS, servico.previa(enf, ORIGEM).conteudo().totalCasos(),
                "no limite: todos os casos, sem paginação");
    }

    @Test
    void permissoes() {
        for (Papel sem : List.of(Papel.ADMINISTRADOR, Papel.DIRECAO, Papel.TRANSPORTE, Papel.AUDITORIA)) {
            UsuarioAutenticado u = usuario(sem);
            assertThrows(AcessoNegadoException.class, () -> servico.previa(u, ORIGEM), sem.name());
            assertThrows(AcessoNegadoException.class, () -> servico.historico(u, ORIGEM), sem.name());
        }
        assertEquals(2, servico.previa(usuario(Papel.COORDENACAO_FLUXO), ORIGEM).conteudo().totalCasos());
        UsuarioAutenticado trocaSenha = new UsuarioAutenticado(UUID.randomUUID(), "x", "X",
                Map.of(UNIDADE, Set.of(Papel.ENFERMAGEM)), UNIDADE, true, 1);
        assertThrows(AcessoNegadoException.class, () -> servico.previa(trocaSenha, ORIGEM), "troca de senha pendente");
        assertFalse(banco.auditoria.contains("PASSAGEM_ENTREGUE"));
    }
}
