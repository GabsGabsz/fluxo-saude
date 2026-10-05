package br.fluxosaude.episodio.aplicacao;

import static br.fluxosaude.compartilhado.RegraVioladaException.exigir;

import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.compartilhado.Textos;
import br.fluxosaude.episodio.dominio.Episodio;
import br.fluxosaude.episodio.dominio.Etapa;
import br.fluxosaude.episodio.dominio.EventoEpisodio;
import br.fluxosaude.episodio.dominio.FluxoConfigurado;
import br.fluxosaude.episodio.dominio.MomentoInformado;
import br.fluxosaude.episodio.dominio.NovoPaciente;
import br.fluxosaude.episodio.dominio.Pendencia;
import br.fluxosaude.episodio.dominio.ProtocoloExterno;
import br.fluxosaude.episodio.dominio.TipoEvento;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Casos de uso do episódio (RF-002..RF-015, RF-028, RF-035..RF-037). Cada método:
 * <ol>
 *   <li>exige a permissão da ação na unidade ativa (e {@code HORARIO_AJUSTAR} se o horário
 *       informado for ajuste manual — RNF-017);</li>
 *   <li>executa numa transação com o contexto validado pelo banco;</li>
 *   <li>confere a versão lida pelo cliente (RF-036) antes de alterar;</li>
 *   <li>persiste agregado + eventos da linha do tempo na mesma transação.</li>
 * </ol>
 */
public final class ServicoEpisodios {

    /** Identificador e versão resultantes (o cliente usa a versão na próxima alteração). */
    public record Resultado(UUID id, int versao) {
    }

    /** Abertura: paciente existente ({@code pacienteId}) OU novo cadastro ({@code novoPaciente}). */
    public record AbrirEpisodio(UUID pacienteId, NovoPaciente novoPaciente, UUID setorId,
                                MomentoInformado entrada, String justificativaDuplicidade) {
        public AbrirEpisodio {
            exigir((pacienteId == null) != (novoPaciente == null), "PACIENTE_OBRIGATORIO",
                    "Informe um paciente já cadastrado OU os dados de um novo cadastro");
            Objects.requireNonNull(setorId, "setor");
        }
    }

    private final Transacao transacao;
    /** Relógio base; cada caso de uso o "congela" uma única vez (ver {@link #agora()}). */
    private final Clock relogio;
    private final Supplier<UUID> ids;

    public ServicoEpisodios(Transacao transacao, Clock clock, Supplier<UUID> ids) {
        this.transacao = Objects.requireNonNull(transacao);
        this.relogio = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    /** RF-002 / RF-003. */
    public Resultado abrir(UsuarioAutenticado u, ContextoOrigem origem, AbrirEpisodio cmd) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_ABRIR);
        Clock clock = agora();
        MomentoInformado entrada = cmd.entrada() != null ? cmd.entrada() : MomentoInformado.agora(clock);
        return transacao.executar(u, origem, r -> {
            FluxoConfigurado fluxo = r.fluxoDaUnidade(u.unidadeAtiva());
            exigirPermissaoDeAjuste(u, fluxo, entrada, clock);
            exigir(r.setorExiste(cmd.setorId()), "SETOR_INVALIDO", "Setor não pertence à unidade");
            UUID pacienteId = resolverPaciente(r, u, cmd);
            boolean jaAtivo = r.existeEpisodioAtivo(u.unidadeAtiva(), pacienteId);
            Episodio ep = Episodio.abrir(new Episodio.ComandoAbertura(pacienteId, cmd.setorId(), entrada,
                    cmd.justificativaDuplicidade()), jaAtivo, fluxo, u.usuarioId(), clock, ids);
            r.inserir(ep);
            r.inserirEventos(ep.retirarEventos());
            return new Resultado(ep.id(), 0);
        });
    }

    /** RF-004 / RF-008 / RF-009 / RF-015. Desfecho encerra as pendências abertas (RN-008). */
    public Resultado mudarEtapa(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId, int versaoLida,
                                Episodio.ComandoMudancaEtapa cmd) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_ALTERAR);
        Clock clock = agora();
        return transacao.executar(u, origem, r -> {
            FluxoConfigurado fluxo = r.fluxoDaUnidade(u.unidadeAtiva());
            Episodio ep = carregar(r, episodioId, versaoLida);
            Etapa destino = fluxo.etapa(cmd.etapaDestinoId());
            if (destino.terminal()) {
                AcessoNegadoException.exigir(u, Permissao.EPISODIO_ENCERRAR);
            }
            exigirPermissaoDeAjuste(u, fluxo, cmd.momento(), clock);
            ep.mudarEtapa(cmd, fluxo, u.usuarioId(), clock, ids);

            List<EventoEpisodio> eventosPendencias = new ArrayList<>();
            if (ep.encerrado()) {
                for (Pendencia p : r.pendenciasAbertas(ep.id())) {
                    p.encerrarPorDesfecho(ep.desfecho().orElseThrow(), u.usuarioId(), clock, ids);
                    r.atualizar(p);
                    eventosPendencias.addAll(p.retirarEventos());
                }
            }
            r.atualizar(ep);
            r.inserirEventos(ep.retirarEventos());
            r.inserirEventos(eventosPendencias);
            return new Resultado(ep.id(), ep.versao() + 1);
        });
    }

    /** RN-003 / RF-035: troca o motivo do bloqueio sem mudar de etapa. */
    public Resultado definirMotivo(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId, int versaoLida,
                                   Episodio.MotivoInformado motivo, MomentoInformado momento) {
        return alterar(u, origem, episodioId, versaoLida, momento,
                (r, ep, fluxo, m, clock) -> ep.definirMotivo(motivo, m, fluxo, u.usuarioId(), clock, ids));
    }

    /** RF-009. */
    public Resultado registrarProtocolo(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId, int versaoLida,
                                        ProtocoloExterno protocolo, MomentoInformado momento) {
        return alterar(u, origem, episodioId, versaoLida, momento,
                (r, ep, fluxo, m, clock) -> ep.registrarProtocolo(protocolo, m, fluxo, u.usuarioId(), clock, ids));
    }

    public Resultado definirDestino(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId, int versaoLida,
                                    UUID especialidadeId, String descricao, MomentoInformado momento) {
        return alterar(u, origem, episodioId, versaoLida, momento,
                (r, ep, fluxo, m, clock) -> {
                    exigir(especialidadeId == null || r.especialidadeAtiva(especialidadeId), "ESPECIALIDADE_INVALIDA",
                            "Especialidade inexistente ou inativa");
                    ep.definirDestino(especialidadeId, descricao, m, fluxo, u.usuarioId(), clock, ids);
                });
    }

    public Resultado transferirSetor(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId, int versaoLida,
                                     UUID setorId, MomentoInformado momento) {
        return alterar(u, origem, episodioId, versaoLida, momento, (r, ep, fluxo, m, clock) -> {
            exigir(r.setorExiste(setorId), "SETOR_INVALIDO", "Setor não pertence à unidade");
            ep.transferirSetor(setorId, m, fluxo, u.usuarioId(), clock, ids);
        });
    }

    /**
     * RF-028: observação operacional (texto livre curto). O texto fica em tabela própria,
     * não no evento imutável; o evento só referencia a observação (LGPD, RN-009).
     */
    public Resultado registrarObservacao(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId, String texto) {
        AcessoNegadoException.exigir(u, Permissao.OBSERVACAO_REGISTRAR);
        String t = Textos.obrigatorio(texto, "Observação", 3, 1000);
        Clock clock = agora();
        return transacao.executar(u, origem, r -> {
            Episodio ep = r.episodio(episodioId).orElseThrow(() -> new RecursoNaoEncontradoException("Episódio"));
            exigir(!ep.encerrado(), "EPISODIO_ENCERRADO", "Episódio encerrado não aceita observações");
            UUID id = ids.get();
            r.inserirObservacao(id, ep.id(), ep.unidadeId(), t);
            r.inserirEventos(List.of(new EventoEpisodio(ids.get(), ep.id(), TipoEvento.OBSERVACAO_REGISTRADA,
                    clock.instant(), u.usuarioId(), Map.of("observacao_id", id.toString()), null)));
            return new Resultado(id, 0);
        });
    }

    // ----------------------------------------------------------------------------

    @FunctionalInterface
    private interface Operacao {
        void aplicar(Repositorios r, Episodio ep, FluxoConfigurado fluxo, MomentoInformado momento, Clock clock);
    }

    private Resultado alterar(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId, int versaoLida,
                              MomentoInformado momentoInformado, Operacao operacao) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_ALTERAR);
        Clock clock = agora();
        MomentoInformado momento = momentoInformado != null ? momentoInformado : MomentoInformado.agora(clock);
        return transacao.executar(u, origem, r -> {
            FluxoConfigurado fluxo = r.fluxoDaUnidade(u.unidadeAtiva());
            Episodio ep = carregar(r, episodioId, versaoLida);
            exigirPermissaoDeAjuste(u, fluxo, momento, clock);
            operacao.aplicar(r, ep, fluxo, momento, clock);
            List<EventoEpisodio> eventos = ep.retirarEventos();
            if (eventos.isEmpty()) {
                return new Resultado(ep.id(), ep.versao()); // nada mudou: não incrementa versão
            }
            r.atualizar(ep);
            r.inserirEventos(eventos);
            return new Resultado(ep.id(), ep.versao() + 1);
        });
    }

    private static Episodio carregar(Repositorios r, UUID episodioId, int versaoLida) {
        Episodio ep = r.episodio(episodioId).orElseThrow(() -> new RecursoNaoEncontradoException("Episódio"));
        if (ep.versao() != versaoLida) {
            throw new ConflitoDeVersaoException();
        }
        return ep;
    }

    /**
     * Um único "agora" por caso de uso: a decisão de ajuste manual (permissão) e a validação
     * do domínio usam o MESMO instante — sem isso, um horário na fronteira do limiar poderia
     * ser avaliado de um jeito na permissão e de outro na validação.
     */
    private Clock agora() {
        return Clock.fixed(relogio.instant(), ZoneOffset.UTC);
    }

    private static void exigirPermissaoDeAjuste(UsuarioAutenticado u, FluxoConfigurado fluxo, MomentoInformado momento,
                                                Clock clock) {
        if (fluxo.politicaTempo().ehAjusteManual(momento, clock.instant())) {
            AcessoNegadoException.exigir(u, Permissao.HORARIO_AJUSTAR);
        }
    }

    private UUID resolverPaciente(Repositorios r, UsuarioAutenticado u, AbrirEpisodio cmd) {
        if (cmd.pacienteId() != null) {
            Repositorios.SituacaoPaciente s = r.situacaoPaciente(cmd.pacienteId());
            if (s == Repositorios.SituacaoPaciente.INEXISTENTE) {
                throw new RecursoNaoEncontradoException("Paciente");
            }
            exigir(s == Repositorios.SituacaoPaciente.ATIVO, "PACIENTE_RECONCILIADO",
                    "Cadastro reconciliado: use o cadastro principal do paciente");
            return cmd.pacienteId();
        }
        NovoPaciente novo = cmd.novoPaciente();
        // RF-003: cadastro já existente pelos identificadores fortes → usar o existente (sem duplicar).
        if (novo.cns() != null) {
            exigir(r.pacientePorCns(u.unidadeAtiva(), novo.cns()).isEmpty(), "PACIENTE_JA_CADASTRADO",
                    "Já existe paciente com este CNS na unidade; selecione o cadastro existente");
        }
        if (novo.identificadorInstitucional() != null) {
            exigir(r.pacientePorIdentificador(u.unidadeAtiva(), novo.identificadorInstitucional()).isEmpty(),
                    "PACIENTE_JA_CADASTRADO",
                    "Já existe paciente com este identificador na unidade; selecione o cadastro existente");
        }
        return r.inserirPaciente(ids.get(), u.unidadeAtiva(), novo);
    }
}
