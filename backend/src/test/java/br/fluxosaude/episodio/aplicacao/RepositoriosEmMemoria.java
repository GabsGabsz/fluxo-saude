package br.fluxosaude.episodio.aplicacao;

import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.episodio.dominio.Episodio;
import br.fluxosaude.episodio.dominio.EventoEpisodio;
import br.fluxosaude.episodio.dominio.FluxoConfigurado;
import br.fluxosaude.episodio.dominio.NovoPaciente;
import br.fluxosaude.episodio.dominio.Pendencia;
import br.fluxosaude.episodio.dominio.StatusPendencia;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Repositórios em memória para testar os casos de uso sem banco. Reproduz o controle
 * otimista (versão) e devolve CÓPIAS reconstituídas, como faria a persistência real.
 */
final class RepositoriosEmMemoria implements Repositorios, Transacao {

    final FluxoConfigurado fluxo;
    final Map<UUID, NovoPaciente> pacientes = new HashMap<>();
    final Set<UUID> reconciliados = new HashSet<>();
    final Map<UUID, Episodio> episodios = new HashMap<>();
    final Map<UUID, Pendencia> pendencias = new HashMap<>();
    final Set<UUID> setores = new HashSet<>();
    final Set<UUID> especialidades = new HashSet<>();
    final Set<UUID> lotados = new HashSet<>();
    final List<EventoEpisodio> eventos = new ArrayList<>();
    final Map<UUID, String> observacoes = new HashMap<>();
    final List<UUID> consultasRegistradas = new ArrayList<>();
    List<Consultas.LinhaPainel> painel = List.of();
    Consultas.FiltroTorre ultimoFiltro;
    int transacoes;

    RepositoriosEmMemoria(FluxoConfigurado fluxo) {
        this.fluxo = fluxo;
    }

    @Override
    public <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<Repositorios, T> trabalho) {
        transacoes++;
        return trabalho.apply(this);
    }

    @Override public FluxoConfigurado fluxoDaUnidade(UUID unidadeId) { return fluxo; }

    @Override public Optional<UUID> pacientePorCns(UUID unidadeId, String cns) {
        return pacientes.entrySet().stream().filter(e -> cns.equals(e.getValue().cns())).map(Map.Entry::getKey).findFirst();
    }

    @Override public Optional<UUID> pacientePorIdentificador(UUID unidadeId, String id) {
        return pacientes.entrySet().stream().filter(e -> id.equals(e.getValue().identificadorInstitucional()))
                .map(Map.Entry::getKey).findFirst();
    }

    @Override public SituacaoPaciente situacaoPaciente(UUID pacienteId) {
        if (!pacientes.containsKey(pacienteId)) {
            return SituacaoPaciente.INEXISTENTE;
        }
        return reconciliados.contains(pacienteId) ? SituacaoPaciente.RECONCILIADO : SituacaoPaciente.ATIVO;
    }

    @Override public UUID inserirPaciente(UUID id, UUID unidadeId, NovoPaciente p) {
        pacientes.put(id, p);
        return id;
    }

    @Override public boolean existeEpisodioAtivo(UUID unidadeId, UUID pacienteId) {
        return episodios.values().stream().anyMatch(e -> e.pacienteId().equals(pacienteId) && !e.encerrado());
    }

    @Override public Optional<Episodio> episodio(UUID id) {
        return Optional.ofNullable(episodios.get(id)).map(e -> copia(e, e.versao()));
    }

    @Override public void inserir(Episodio e) {
        episodios.put(e.id(), copia(e, 0));
    }

    @Override public void atualizar(Episodio e) {
        Episodio atual = episodios.get(e.id());
        if (atual == null || atual.versao() != e.versao()) {
            throw new ConflitoDeVersaoException();
        }
        episodios.put(e.id(), copia(e, e.versao() + 1));
    }

    @Override public Optional<Pendencia> pendencia(UUID id) {
        return Optional.ofNullable(pendencias.get(id)).map(p -> copia(p, p.versao()));
    }

    @Override public List<Pendencia> pendenciasAbertas(UUID episodioId) {
        return pendencias.values().stream()
                .filter(p -> p.episodioId().equals(episodioId) && p.status() == StatusPendencia.ABERTA)
                .map(p -> copia(p, p.versao())).toList();
    }

    @Override public void inserir(Pendencia p) {
        pendencias.put(p.id(), copia(p, 0));
    }

    @Override public void atualizar(Pendencia p) {
        Pendencia atual = pendencias.get(p.id());
        if (atual == null || atual.versao() != p.versao()) {
            throw new ConflitoDeVersaoException();
        }
        pendencias.put(p.id(), copia(p, p.versao() + 1));
    }

    @Override public boolean setorExiste(UUID setorId) { return setores.contains(setorId); }

    @Override public boolean especialidadeAtiva(UUID especialidadeId) { return especialidades.contains(especialidadeId); }

    @Override public boolean usuarioLotadoNaUnidade(UUID usuarioId, UUID unidadeId) { return lotados.contains(usuarioId); }

    @Override public void inserirEventos(List<EventoEpisodio> novos) { eventos.addAll(novos); }

    @Override public void inserirObservacao(UUID id, UUID episodioId, UUID unidadeId, String texto) {
        observacoes.put(id, texto);
    }

    Boolean ultimoCatalogoComProfissionais;
    String ultimaBuscaPaciente;
    List<Consultas.PacienteEncontrado> pacientesEncontrados = List.of();

    @Override public Consultas consultas() {
        return new Consultas() {
            @Override public List<LinhaTorre> torre(FiltroTorre filtro) {
                ultimoFiltro = filtro;
                return List.of();
            }
            @Override public Optional<Caso> caso(UUID episodioId) {
                return episodios.containsKey(episodioId)
                        ? Optional.of(new Caso(null, null, null, null, null, null, null, null, null, null,
                                List.of(), List.of(), List.of(), false))
                        : Optional.empty();
            }
            @Override public List<LinhaPainel> painel(int limite) { return painel; }
            @Override public void registrarConsultaDeCaso(UUID episodioId, UUID unidadeId) {
                consultasRegistradas.add(episodioId);
            }
            @Override public Catalogo catalogo(boolean incluirProfissionais) {
                ultimoCatalogoComProfissionais = incluirProfissionais;
                return new Catalogo(new UnidadeInfo(fluxo.unidadeId(), "UPA", "UPA Teste", "America/Fortaleza"),
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        incluirProfissionais ? List.of(new Profissional(UUID.randomUUID(), "Profissional")) : List.of());
            }
            @Override public List<PacienteEncontrado> pacientes(String cns, String identificador) {
                ultimaBuscaPaciente = cns != null ? "cns:" + cns : "id:" + identificador;
                return pacientesEncontrados;
            }
            @Override public void registrarConsultaDePaciente(UUID pacienteId, UUID unidadeId) {
                consultasRegistradas.add(pacienteId);
            }
        };
    }

    private static Episodio copia(Episodio e, int versao) {
        return Episodio.reconstituir(e.id(), e.unidadeId(), e.pacienteId(), e.setorId(), e.entradaEm(), e.etapaId(),
                e.etapaDesde(), e.bloqueio().orElse(null), e.protocolo().orElse(null),
                e.especialidadeRequeridaId().orElse(null), e.destinoDescricao().orElse(null), e.desfecho().orElse(null),
                e.encerradoEm().orElse(null), e.justificativaEncerramento().orElse(null),
                e.justificativaDuplicidade().orElse(null), e.etapaDesde(), versao);
    }

    private static Pendencia copia(Pendencia p, int versao) {
        return Pendencia.reconstituir(p.id(), p.unidadeId(), p.episodioId(), p.categoria(), p.descricao(),
                p.responsavel(), p.prazo(), p.criticidade(), p.status(), p.criadaEm(), p.resolucao(), versao);
    }
}
