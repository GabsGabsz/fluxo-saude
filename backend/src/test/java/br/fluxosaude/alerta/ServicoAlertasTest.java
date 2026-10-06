package br.fluxosaude.alerta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.alerta.aplicacao.RepositorioAlertas;
import br.fluxosaude.alerta.aplicacao.ServicoAlertas;
import br.fluxosaude.alerta.aplicacao.TransacaoAlertas;
import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServicoAlertasTest {

    static final ContextoOrigem ORIGEM = new ContextoOrigem("10.0.0.1", "teste");
    static final UUID UNIDADE = UUID.randomUUID();
    static final UUID ETAPA_TRANSPORTE = UUID.randomUUID();
    static final UUID ETAPA_ATENDIMENTO = UUID.randomUUID();
    static final Instant AGORA = Instant.parse("2026-10-07T12:00:00Z");

    /** Banco em memória: regras e episódios ABERTOS da unidade (como a consulta real). */
    static final class Banco implements TransacaoAlertas, RepositorioAlertas {
        final Map<UUID, RegraAlerta> regras = new LinkedHashMap<>();
        final Map<UUID, EpisodioMonitorado> abertos = new LinkedHashMap<>();
        final Map<Ocorrencia, Ciencia> ciencias = new HashMap<>();
        final List<String> auditoria = new ArrayList<>();

        @Override public <T> T executar(UsuarioAutenticado u, ContextoOrigem o, Function<RepositorioAlertas, T> t) {
            return t.apply(this);
        }
        @Override public List<RegraAlerta> regras(boolean somenteAtivas) {
            return regras.values().stream().filter(r -> !somenteAtivas || r.ativa()).toList();
        }
        @Override public Optional<RegraAlerta> regra(UUID id) { return Optional.ofNullable(regras.get(id)); }
        @Override public boolean etapaDaUnidade(UUID etapaId) {
            return etapaId.equals(ETAPA_TRANSPORTE) || etapaId.equals(ETAPA_ATENDIMENTO);
        }
        @Override public void inserirRegra(RegraAlerta regra) { regras.put(regra.id(), regra); auditoria.add("regra+"); }
        @Override public void atualizarRegra(RegraAlerta regra, int versaoLida) {
            if (regras.get(regra.id()).versao() != versaoLida) {
                throw new ConflitoDeVersaoException();
            }
            regras.put(regra.id(), regra);
            auditoria.add("regra~");
        }
        @Override public List<EpisodioMonitorado> episodiosAbertos(int limite) {
            return abertos.values().stream().limit(limite).toList();
        }
        @Override public List<EpisodioMonitorado> episodiosAbertos(Collection<UUID> ids) {
            return abertos.values().stream().filter(e -> ids.contains(e.situacao().episodioId())).toList();
        }
        @Override public Map<Ocorrencia, Ciencia> ciencias(Collection<UUID> ids) {
            Map<Ocorrencia, Ciencia> m = new HashMap<>();
            ciencias.forEach((k, v) -> { if (ids.contains(k.episodioId())) { m.put(k, v); } });
            return m;
        }
        @Override public boolean registrarCiencia(UUID id, Ocorrencia o) {
            if (ciencias.containsKey(o)) {
                return false;
            }
            ciencias.put(o, new Ciencia("Enfermeira", AGORA));
            auditoria.add("ciencia");
            return true;
        }

        UUID episodio(UUID etapa, Duration naEtapa, List<SituacaoEpisodio.PendenciaAberta> pendencias) {
            UUID id = UUID.randomUUID();
            Instant desde = AGORA.minus(naEtapa);
            abertos.put(id, new EpisodioMonitorado(new SituacaoEpisodio(id, etapa, desde.minus(Duration.ofHours(1)), desde,
                    null, null, desde, pendencias), "Paciente", "Setor", "Etapa", null, null, List.of()));
            return id;
        }
    }

    Banco banco;
    ServicoAlertas servico;

    @BeforeEach
    void setUp() {
        banco = new Banco();
        servico = new ServicoAlertas(banco, Clock.fixed(AGORA, ZoneOffset.UTC), UUID::randomUUID);
    }

    static UsuarioAutenticado usuario(Papel... papeis) {
        return new UsuarioAutenticado(UUID.randomUUID(), "u.teste", "Usuário", Map.of(UNIDADE, Set.of(papeis)),
                UNIDADE, false, 1);
    }

    RegraAlerta transporte(UsuarioAutenticado adm) {
        return servico.criarRegra(adm, ORIGEM, new ServicoAlertas.NovaRegra("Transporte atrasado (ilustrativo)",
                TipoRegraAlerta.TEMPO_NA_ETAPA, ETAPA_TRANSPORTE, null, Duration.ofHours(2), "Acionar central"));
    }

    @Test
    @DisplayName("Sem regras cadastradas, ninguém está travado (nenhum limite implícito — RN-014)")
    void semRegrasSemTravados() {
        banco.episodio(ETAPA_TRANSPORTE, Duration.ofDays(5), List.of());
        assertTrue(servico.travados(usuario(Papel.ENFERMAGEM), ORIGEM).itens().isEmpty());
    }

    @Test
    @DisplayName("Travados: só quem viola regra; na fronteira entra; ordem pelo limite atingido há mais tempo")
    void travadosNaFronteira() {
        transporte(usuario(Papel.ADMINISTRADOR));
        UUID noLimite = banco.episodio(ETAPA_TRANSPORTE, Duration.ofHours(2), List.of());
        UUID abaixo = banco.episodio(ETAPA_TRANSPORTE, Duration.ofHours(2).minusSeconds(1), List.of());
        UUID muitoAcima = banco.episodio(ETAPA_TRANSPORTE, Duration.ofHours(9), List.of());
        UUID outraEtapa = banco.episodio(ETAPA_ATENDIMENTO, Duration.ofHours(9), List.of());
        var t = servico.travados(usuario(Papel.ENFERMAGEM), ORIGEM);
        assertEquals(AGORA, t.agora());
        assertEquals(List.of(muitoAcima, noLimite), t.itens().stream().map(c -> c.episodio().situacao().episodioId()).toList());
        assertFalse(t.itens().stream().anyMatch(c -> c.episodio().situacao().episodioId().equals(abaixo)));
        assertFalse(t.itens().stream().anyMatch(c -> c.episodio().situacao().episodioId().equals(outraEtapa)));
        assertEquals("Acionar central", t.itens().get(0).alertas().get(0).alerta().acaoEsperada());
    }

    @Test
    @DisplayName("Permissões: travados/Torre exigem acesso nominal; painel só a lista; configuração só administrador")
    void permissoes() {
        UsuarioAutenticado adm = usuario(Papel.ADMINISTRADOR);
        UsuarioAutenticado direcao = usuario(Papel.DIRECAO);
        UsuarioAutenticado transporteUser = usuario(Papel.TRANSPORTE);
        UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
        transporte(adm);
        UUID ep = banco.episodio(ETAPA_TRANSPORTE, Duration.ofHours(3), List.of());
        assertThrows(AcessoNegadoException.class, () -> servico.travados(adm, ORIGEM), "administrador sem acesso nominal");
        assertThrows(AcessoNegadoException.class, () -> servico.travados(direcao, ORIGEM));
        assertThrows(AcessoNegadoException.class, () -> servico.alertasDe(direcao, ORIGEM, List.of(ep)));
        assertEquals(Set.of(ep), servico.alertasDe(enf, ORIGEM, List.of(ep)).porEpisodio().keySet(), "Torre: só os listados");
        assertThrows(AcessoNegadoException.class, () -> servico.travados(transporteUser, ORIGEM));
        assertEquals(Set.of(ep), servico.episodiosEmAlerta(direcao, ORIGEM, List.of(ep, UUID.randomUUID())),
                "painel coletivo: só quais estão em alerta");
        assertThrows(AcessoNegadoException.class, () -> servico.episodiosEmAlerta(transporteUser, ORIGEM, List.of(ep)));
        assertThrows(AcessoNegadoException.class, () -> servico.criarRegra(enf, ORIGEM, new ServicoAlertas.NovaRegra(
                "Regra", TipoRegraAlerta.TEMPO_TOTAL, null, null, Duration.ofHours(1), null)));
        assertThrows(AcessoNegadoException.class, () -> servico.regras(transporteUser, ORIGEM));
        assertEquals(1, servico.regras(enf, ORIGEM).size(), "quem acompanha os casos lê as regras");
        assertEquals(1, servico.regras(adm, ORIGEM).size());
        assertThrows(AcessoNegadoException.class, () -> servico.registrarCiencia(direcao, ORIGEM,
                new ServicoAlertas.PedidoCiencia(ep, UUID.randomUUID(), AGORA, null)));
    }

    @Test
    @DisplayName("Ciência: só de alerta ativo, idempotente, não encerra pendência e aparece no painel")
    void ciencia() {
        UsuarioAutenticado adm = usuario(Papel.ADMINISTRADOR);
        RegraAlerta pend = servico.criarRegra(adm, ORIGEM, new ServicoAlertas.NovaRegra("Pendência vencida",
                TipoRegraAlerta.PENDENCIA_VENCIDA, null, null, null, null));
        UUID pid = UUID.randomUUID();
        Instant prazo = AGORA.minus(Duration.ofMinutes(10));
        UUID ep = banco.episodio(ETAPA_ATENDIMENTO, Duration.ofHours(1),
                List.of(new SituacaoEpisodio.PendenciaAberta(pid, CategoriaBloqueio.LOGISTICA, prazo)));
        UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
        var ocorrencia = new ServicoAlertas.PedidoCiencia(ep, pend.id(), prazo, pid);
        assertNull(servico.travados(enf, ORIGEM).itens().get(0).alertas().get(0).ciencia());
        assertTrue(servico.registrarCiencia(enf, ORIGEM, ocorrencia));
        assertFalse(servico.registrarCiencia(enf, ORIGEM, ocorrencia), "idempotente");
        var visto = servico.travados(enf, ORIGEM).itens().get(0).alertas().get(0);
        assertNotNull(visto.ciencia(), "continua travado, agora com ciência");
        assertEquals(1, banco.abertos.get(ep).situacao().pendencias().size(), "pendência continua aberta");
        // Ocorrência que não está em alerta (ex.: outro prazo, já resolvida) é recusada
        assertEquals("ALERTA_INEXISTENTE", assertThrows(RegraVioladaException.class, () -> servico.registrarCiencia(enf,
                ORIGEM, new ServicoAlertas.PedidoCiencia(ep, pend.id(), prazo.plusSeconds(1), pid))).codigo());
        // Regra alterada (ex.: limite revisto) = nova ocorrência: a ciência anterior não vale mais
        UsuarioAutenticado admin = usuario(Papel.ADMINISTRADOR);
        servico.alterarRegra(admin, ORIGEM, pend.id(), 0, new ServicoAlertas.AlteracaoRegra("Pendência vencida (revista)",
                null, null, null, null, true));
        assertNull(servico.travados(enf, ORIGEM).itens().get(0).alertas().get(0).ciencia(), "nova versão da regra");
        assertTrue(servico.registrarCiencia(enf, ORIGEM, ocorrencia));
        // Episódio que não está aberto (encerrado ou de outra unidade): 404
        assertThrows(RecursoNaoEncontradoException.class, () -> servico.registrarCiencia(enf, ORIGEM,
                new ServicoAlertas.PedidoCiencia(UUID.randomUUID(), pend.id(), prazo, pid)));
    }

    @Test
    @DisplayName("Configuração: versão, regra sem mudança não incrementa, etapa de outra unidade recusada, desativação")
    void configuracao() {
        UsuarioAutenticado adm = usuario(Papel.ADMINISTRADOR);
        RegraAlerta r = transporte(adm);
        assertEquals(0, r.versao());
        var igual = servico.alterarRegra(adm, ORIGEM, r.id(), 0, new ServicoAlertas.AlteracaoRegra(r.nome(), r.etapaId(),
                null, r.limite(), r.acaoEsperada(), true));
        assertEquals(0, igual.versao());
        var desativada = servico.alterarRegra(adm, ORIGEM, r.id(), 0, new ServicoAlertas.AlteracaoRegra(r.nome(),
                r.etapaId(), null, Duration.ofHours(3), r.acaoEsperada(), false));
        assertEquals(1, desativada.versao());
        assertThrows(ConflitoDeVersaoException.class, () -> servico.alterarRegra(adm, ORIGEM, r.id(), 0,
                new ServicoAlertas.AlteracaoRegra(r.nome(), null, null, Duration.ofHours(1), null, true)));
        banco.episodio(ETAPA_TRANSPORTE, Duration.ofDays(1), List.of());
        assertTrue(servico.travados(usuario(Papel.ENFERMAGEM), ORIGEM).itens().isEmpty(), "regra desativada não alerta");
        assertEquals("ETAPA_INVALIDA", assertThrows(RegraVioladaException.class, () -> servico.criarRegra(adm, ORIGEM,
                new ServicoAlertas.NovaRegra("Etapa alheia", TipoRegraAlerta.TEMPO_NA_ETAPA, UUID.randomUUID(), null,
                        Duration.ofHours(1), null))).codigo());
        assertThrows(RecursoNaoEncontradoException.class, () -> servico.alterarRegra(adm, ORIGEM, UUID.randomUUID(), 0,
                new ServicoAlertas.AlteracaoRegra("Nome", null, null, Duration.ofHours(1), null, true)));
    }
}
