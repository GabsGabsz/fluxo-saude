package br.fluxosaude.episodio.aplicacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import br.fluxosaude.episodio.dominio.Episodio;
import br.fluxosaude.episodio.dominio.FluxoDeTeste;
import br.fluxosaude.episodio.dominio.MomentoInformado;
import br.fluxosaude.episodio.dominio.NovoPaciente;
import br.fluxosaude.episodio.dominio.Pendencia;
import br.fluxosaude.episodio.dominio.ProtocoloExterno;
import br.fluxosaude.episodio.dominio.Responsavel;
import br.fluxosaude.episodio.dominio.StatusPendencia;
import br.fluxosaude.episodio.dominio.TipoEvento;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ServicosDeAplicacaoTest {

    static final ContextoOrigem ORIGEM = new ContextoOrigem("10.0.0.1", "teste");

    FluxoDeTeste t;
    RepositoriosEmMemoria repo;
    ServicoEpisodios episodios;
    ServicoPendencias pendencias;
    ServicoConsultas consultas;

    @BeforeEach
    void setUp() {
        t = new FluxoDeTeste();
        repo = new RepositoriosEmMemoria(t.fluxo);
        repo.setores.add(FluxoDeTeste.SETOR);
        episodios = new ServicoEpisodios(repo, t.relogio, t.ids);
        pendencias = new ServicoPendencias(repo, t.relogio, t.ids);
        consultas = new ServicoConsultas(repo);
    }

    static UsuarioAutenticado usuario(Papel papel) {
        return new UsuarioAutenticado(UUID.randomUUID(), "u." + papel.name().toLowerCase(), "Usuário",
                Map.of(FluxoDeTeste.UNIDADE, Set.of(papel)), FluxoDeTeste.UNIDADE, false, 1);
    }

    static String erro(Executable e) {
        return assertThrows(RegraVioladaException.class, e).codigo();
    }

    ServicoEpisodios.Resultado abrirNovo(UsuarioAutenticado u, String cns) {
        return episodios.abrir(u, ORIGEM, new ServicoEpisodios.AbrirEpisodio(null,
                new NovoPaciente("Maria  da Silva", null, cns, null), FluxoDeTeste.SETOR, null, null));
    }

    Episodio.ComandoMudancaEtapa para(String etapa, String motivo) {
        return new Episodio.ComandoMudancaEtapa(t.etapa(etapa), MomentoInformado.agora(t.relogio),
                motivo == null ? null : new Episodio.MotivoInformado(t.motivo(motivo), null), null, null);
    }

    @Nested
    @DisplayName("Abertura (RF-002, RF-003)")
    class Abertura {
        @Test
        void abreComNovoPacienteEPersisteEventos() {
            ServicoEpisodios.Resultado r = abrirNovo(usuario(Papel.ENFERMAGEM), "291417776317066");
            assertEquals(0, r.versao());
            assertEquals(1, repo.pacientes.size());
            assertEquals("Maria da Silva", repo.pacientes.values().iterator().next().nome(), "nome normalizado");
            assertTrue(repo.eventos.stream().anyMatch(e -> e.tipo() == TipoEvento.EPISODIO_ABERTO));
            assertEquals(1, repo.transacoes, "uma única transação");
        }

        @Test
        void exigePermissao() {
            assertThrows(AcessoNegadoException.class, () -> abrirNovo(usuario(Papel.TRANSPORTE), null));
            assertThrows(AcessoNegadoException.class, () -> abrirNovo(usuario(Papel.DIRECAO), null));
            assertEquals(0, repo.transacoes, "permissão negada antes de tocar no banco");
        }

        @Test
        void naoDuplicaCadastroPorCns() {
            abrirNovo(usuario(Papel.ENFERMAGEM), "291417776317066");
            assertEquals("PACIENTE_JA_CADASTRADO", erro(() -> abrirNovo(usuario(Papel.ENFERMAGEM), "291417776317066")));
            assertEquals("CNS_INVALIDO", erro(() -> abrirNovo(usuario(Papel.ENFERMAGEM), "291417776317067")));
        }

        @Test
        @DisplayName("Segundo episódio ativo exige justificativa e não é bloqueado")
        void duplicidadeJustificada() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            abrirNovo(enf, null);
            UUID paciente = repo.pacientes.keySet().iterator().next();
            assertEquals("POSSIVEL_DUPLICIDADE", erro(() -> episodios.abrir(enf, ORIGEM,
                    new ServicoEpisodios.AbrirEpisodio(paciente, null, FluxoDeTeste.SETOR, null, null))));
            episodios.abrir(enf, ORIGEM, new ServicoEpisodios.AbrirEpisodio(paciente, null, FluxoDeTeste.SETOR, null,
                    "Retorno após evasão"));
            assertEquals(2, repo.episodios.size());
        }

        @Test
        void pacienteReconciliadoOuInexistente() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            assertThrows(RecursoNaoEncontradoException.class, () -> episodios.abrir(enf, ORIGEM,
                    new ServicoEpisodios.AbrirEpisodio(UUID.randomUUID(), null, FluxoDeTeste.SETOR, null, null)));
            UUID p = UUID.randomUUID();
            repo.pacientes.put(p, new NovoPaciente("Fulano", null, null, null));
            repo.reconciliados.add(p);
            assertEquals("PACIENTE_RECONCILIADO", erro(() -> episodios.abrir(enf, ORIGEM,
                    new ServicoEpisodios.AbrirEpisodio(p, null, FluxoDeTeste.SETOR, null, null))));
        }

        @Test
        void setorDeOutraUnidade() {
            assertEquals("SETOR_INVALIDO", erro(() -> episodios.abrir(usuario(Papel.ENFERMAGEM), ORIGEM,
                    new ServicoEpisodios.AbrirEpisodio(null, new NovoPaciente("Fulano", null, null, null),
                            UUID.randomUUID(), null, null))));
        }
    }

    @Nested
    @DisplayName("Alterações com versão (RF-036) e ajuste manual (RNF-017)")
    class Alteracoes {
        @Test
        void versaoDesatualizadaGeraConflito() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            UUID id = abrirNovo(enf, null).id();
            ServicoEpisodios.Resultado r = episodios.mudarEtapa(enf, ORIGEM, id, 0, para("AGUARDANDO_EXAME_PARECER", "AGUARDANDO_EXAME"));
            assertEquals(1, r.versao());
            assertThrows(ConflitoDeVersaoException.class,
                    () -> episodios.mudarEtapa(enf, ORIGEM, id, 0, para("EM_ATENDIMENTO", null)));
            assertEquals(2, episodios.mudarEtapa(enf, ORIGEM, id, 1, para("EM_ATENDIMENTO", null)).versao());
        }

        @Test
        @DisplayName("Horário retroativo exige HORARIO_AJUSTAR (médico não tem; enfermagem tem)")
        void ajusteManualRestrito() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            UUID id = abrirNovo(enf, null).id();
            t.relogio.avancar(Duration.ofHours(1));
            Instant antes = t.relogio.instant().minus(Duration.ofMinutes(30));
            Episodio.ComandoMudancaEtapa retro = new Episodio.ComandoMudancaEtapa(t.etapa("AGUARDANDO_EXAME_PARECER"),
                    MomentoInformado.ajustado(antes, "Registro feito ao fim do atendimento"),
                    new Episodio.MotivoInformado(t.motivo("AGUARDANDO_EXAME"), null), null, null);
            AcessoNegadoException negado = assertThrows(AcessoNegadoException.class,
                    () -> episodios.mudarEtapa(usuario(Papel.MEDICO), ORIGEM, id, 0, retro));
            assertEquals(Permissao.HORARIO_AJUSTAR, negado.permissao());
            assertEquals(1, episodios.mudarEtapa(enf, ORIGEM, id, 0, retro).versao());
        }

        @Test
        @DisplayName("Desfecho exige EPISODIO_ENCERRAR e encerra as pendências abertas (RN-008)")
        void desfechoEncerraPendencias() {
            UsuarioAutenticado coord = usuario(Papel.COORDENACAO_FLUXO);
            UUID id = abrirNovo(coord, null).id();
            UUID pend = pendencias.criar(coord, ORIGEM, id, new Pendencia.ComandoCriacao(CategoriaBloqueio.LOGISTICA,
                    "Acionar transporte", new Responsavel.Perfil(Papel.TRANSPORTE), t.relogio.instant().plusSeconds(3600),
                    CriticidadeOperacional.ALTA)).id();
            episodios.mudarEtapa(coord, ORIGEM, id, 0, para("ALTA", null));
            assertEquals(StatusPendencia.ENCERRADA_POR_DESFECHO, repo.pendencias.get(pend).status());
            assertTrue(repo.eventos.stream().anyMatch(e -> e.tipo() == TipoEvento.PENDENCIA_ENCERRADA));
            assertTrue(repo.eventos.stream().anyMatch(e -> e.tipo() == TipoEvento.EPISODIO_ENCERRADO));
        }

        @Test
        void operacaoSemMudancaNaoIncrementaVersao() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            UUID id = abrirNovo(enf, null).id();
            ProtocoloExterno p = new ProtocoloExterno("REGULA_PI", "2026-1");
            assertEquals(1, episodios.registrarProtocolo(enf, ORIGEM, id, 0, p, null).versao());
            assertEquals(1, episodios.registrarProtocolo(enf, ORIGEM, id, 1, p, null).versao(), "mesmo protocolo: no-op");
        }

        @Test
        void episodioInexistenteOuSetorInvalido() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            assertThrows(RecursoNaoEncontradoException.class,
                    () -> episodios.mudarEtapa(enf, ORIGEM, UUID.randomUUID(), 0, para("ALTA", null)));
            UUID id = abrirNovo(enf, null).id();
            assertEquals("SETOR_INVALIDO", erro(() -> episodios.transferirSetor(enf, ORIGEM, id, 0, UUID.randomUUID(), null)));
        }

        @Test
        void destinoExigeEspecialidadeAtiva() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            UUID id = abrirNovo(enf, null).id();
            assertEquals("ESPECIALIDADE_INVALIDA", erro(() -> episodios.definirDestino(enf, ORIGEM, id, 0,
                    UUID.randomUUID(), "Ortopedia", null)));
            UUID ortopedia = UUID.randomUUID();
            repo.especialidades.add(ortopedia);
            assertEquals(1, episodios.definirDestino(enf, ORIGEM, id, 0, ortopedia, "Ortopedia", null).versao());
            // Só descrição (sem especialidade do catálogo) é aceito
            assertEquals(2, episodios.definirDestino(enf, ORIGEM, id, 1, null, "Hospital regional", null).versao());
        }

        @Test
        @DisplayName("RF-028: observação fica fora do evento imutável (só a referência)")
        void observacao() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            UUID id = abrirNovo(enf, null).id();
            UUID obs = episodios.registrarObservacao(enf, ORIGEM, id, "Família avisada sobre a transferência").id();
            assertEquals("Família avisada sobre a transferência", repo.observacoes.get(obs));
            var evento = repo.eventos.stream().filter(e -> e.tipo() == TipoEvento.OBSERVACAO_REGISTRADA).findFirst().orElseThrow();
            assertEquals(Map.of("observacao_id", obs.toString()), evento.dados());
            assertThrows(AcessoNegadoException.class,
                    () -> episodios.registrarObservacao(usuario(Papel.TRANSPORTE), ORIGEM, id, "texto qualquer"));
        }
    }

    @Nested
    @DisplayName("Pendências (RF-007, RF-013, RN-005)")
    class Pendencias {
        UsuarioAutenticado coord;
        UUID episodio;

        @BeforeEach
        void abrir() {
            coord = usuario(Papel.COORDENACAO_FLUXO);
            episodio = abrirNovo(coord, null).id();
        }

        Pendencia.ComandoCriacao cmd(Responsavel r) {
            return new Pendencia.ComandoCriacao(CategoriaBloqueio.REGULACAO, "Atualizar regulação", r,
                    t.relogio.instant().plusSeconds(7200), CriticidadeOperacional.MEDIA);
        }

        @Test
        void responsavelPrecisaSerDaUnidade() {
            assertEquals("RESPONSAVEL_INVALIDO", erro(() -> pendencias.criar(coord, ORIGEM, episodio,
                    cmd(new Responsavel.Usuario(UUID.randomUUID())))));
            assertEquals("RESPONSAVEL_INVALIDO", erro(() -> pendencias.criar(coord, ORIGEM, episodio,
                    cmd(new Responsavel.Setor(UUID.randomUUID())))));
            UUID lotado = UUID.randomUUID();
            repo.lotados.add(lotado);
            pendencias.criar(coord, ORIGEM, episodio, cmd(new Responsavel.Usuario(lotado)));
        }

        @Test
        void cicloComVersao() {
            UUID p = pendencias.criar(coord, ORIGEM, episodio, cmd(new Responsavel.Perfil(Papel.COORDENACAO_FLUXO))).id();
            assertEquals(1, pendencias.atualizar(coord, ORIGEM, p, 0,
                    new ServicoPendencias.Atualizacao(null, t.relogio.instant().plusSeconds(10_000))).versao());
            assertThrows(ConflitoDeVersaoException.class, () -> pendencias.resolver(coord, ORIGEM, p, 0, "Feito"));
            assertEquals("NADA_A_ALTERAR", erro(() -> pendencias.atualizar(coord, ORIGEM, p, 1,
                    new ServicoPendencias.Atualizacao(null, null))));
            assertEquals(2, pendencias.resolver(coord, ORIGEM, p, 1, "Informações atualizadas").versao());
            assertEquals(StatusPendencia.RESOLVIDA, repo.pendencias.get(p).status());
        }

        @Test
        void permissao() {
            assertThrows(AcessoNegadoException.class, () -> pendencias.criar(usuario(Papel.TRANSPORTE), ORIGEM, episodio,
                    cmd(new Responsavel.Perfil(Papel.TRANSPORTE))));
        }
    }

    @Nested
    @DisplayName("Consultas (RF-010, RF-038, RNF-002)")
    class Leitura {
        @Test
        void casoNominalEhAuditado() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            UUID id = abrirNovo(enf, null).id();
            consultas.caso(enf, ORIGEM, id);
            assertEquals(List.of(id), repo.consultasRegistradas);
            assertThrows(AcessoNegadoException.class, () -> consultas.caso(usuario(Papel.DIRECAO), ORIGEM, id));
            assertThrows(RecursoNaoEncontradoException.class, () -> consultas.caso(enf, ORIGEM, UUID.randomUUID()));
        }

        @Test
        @DisplayName("Painel coletivo: direção vê, mas só pseudônimo — nunca o nome")
        void painelPseudonimizado() {
            UUID ep = UUID.fromString("33333333-0000-0000-0000-00000000a3f2");
            repo.painel = List.of(new Consultas.LinhaPainel(ep, "Maria da Silva Costa", "Observação", "Aceito", "ACEITO",
                    t.relogio.instant(), t.relogio.instant(), null, null, 0));
            var linhas = consultas.painel(usuario(Papel.DIRECAO), ORIGEM);
            assertEquals("M.S.C · A3F2", linhas.get(0).identificacao());
            assertFalse(linhas.toString().contains("Maria"));
            assertThrows(AcessoNegadoException.class, () -> consultas.painel(usuario(Papel.AUDITORIA), ORIGEM));
            assertThrows(AcessoNegadoException.class, () -> consultas.torre(usuario(Papel.DIRECAO), ORIGEM, null));
        }

        @Test
        @DisplayName("Catálogo: qualquer perfil da unidade; nomes de profissionais só para quem atua nos casos")
        void catalogo() {
            consultas.catalogo(usuario(Papel.ENFERMAGEM), ORIGEM);
            assertTrue(repo.ultimoCatalogoComProfissionais);
            var direcao = consultas.catalogo(usuario(Papel.DIRECAO), ORIGEM);
            assertFalse(repo.ultimoCatalogoComProfissionais);
            assertTrue(direcao.profissionais().isEmpty());
            consultas.catalogo(usuario(Papel.ADMINISTRADOR), ORIGEM);
            assertTrue(repo.ultimoCatalogoComProfissionais, "administrador gere usuários");
            UsuarioAutenticado pendente = new UsuarioAutenticado(UUID.randomUUID(), "u.p", "Pendente",
                    Map.of(FluxoDeTeste.UNIDADE, Set.of(Papel.ENFERMAGEM)), FluxoDeTeste.UNIDADE, true, 1);
            assertThrows(AcessoNegadoException.class, () -> consultas.catalogo(pendente, ORIGEM),
                    "troca de senha pendente");
        }

        @Test
        @DisplayName("Busca de paciente: exata por CNS OU identificador, exige abrir episódio e é auditada")
        void buscaDePaciente() {
            UsuarioAutenticado enf = usuario(Papel.ENFERMAGEM);
            UUID achado = UUID.randomUUID();
            repo.pacientesEncontrados = List.of(new Consultas.PacienteEncontrado(achado, "Maria", null, false, null, false));
            assertEquals(1, consultas.pacientes(enf, ORIGEM, " 291 4177 7631 7066 ", null).size());
            assertEquals("cns:291417776317066", repo.ultimaBuscaPaciente);
            assertTrue(repo.consultasRegistradas.contains(achado), "consulta nominal auditada");
            consultas.pacientes(enf, ORIGEM, null, " PR-12 ");
            assertEquals("id:PR-12", repo.ultimaBuscaPaciente);
            assertEquals("BUSCA_INVALIDA", erro(() -> consultas.pacientes(enf, ORIGEM, null, null)));
            assertEquals("BUSCA_INVALIDA", erro(() -> consultas.pacientes(enf, ORIGEM, "291417776317066", "PR-1")));
            assertEquals("CNS_INVALIDO", erro(() -> consultas.pacientes(enf, ORIGEM, "123", null)));
            assertThrows(AcessoNegadoException.class, () -> consultas.pacientes(usuario(Papel.DIRECAO), ORIGEM, null, "PR-1"));
            assertThrows(AcessoNegadoException.class, () -> consultas.pacientes(usuario(Papel.MEDICO), ORIGEM, null, "PR-1"),
                    "médico não abre episódio");
        }

        @Test
        void filtroNormalizado() {
            consultas.torre(usuario(Papel.ENFERMAGEM), ORIGEM, new Consultas.FiltroTorre(null, null, null, null, null,
                    null, -5, null, null, false, 100_000));
            assertEquals(ServicoConsultas.LIMITE_MAXIMO, repo.ultimoFiltro.limite(), "limite máximo imposto");
            assertEquals(0, repo.ultimoFiltro.minutosMinimosNaEtapa().intValue());
            assertEquals(Consultas.Ordem.TEMPO_NA_ETAPA, repo.ultimoFiltro.ordem());
            consultas.torre(usuario(Papel.ENFERMAGEM), ORIGEM, null);
            assertNull(repo.ultimoFiltro.setorId());
        }
    }
}
