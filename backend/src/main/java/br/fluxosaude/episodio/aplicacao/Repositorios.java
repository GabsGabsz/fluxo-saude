package br.fluxosaude.episodio.aplicacao;

import br.fluxosaude.episodio.dominio.Episodio;
import br.fluxosaude.episodio.dominio.EventoEpisodio;
import br.fluxosaude.episodio.dominio.FluxoConfigurado;
import br.fluxosaude.episodio.dominio.NovoPaciente;
import br.fluxosaude.episodio.dominio.Pendencia;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Portas de persistência usadas pelos casos de uso, todas ligadas à MESMA transação
 * (ver {@link Transacao}). A implementação aplica o contexto (usuário/unidade) e o RLS;
 * aqui só há regra de aplicação.
 */
public interface Repositorios {

    FluxoConfigurado fluxoDaUnidade(UUID unidadeId);

    // --- pacientes
    Optional<UUID> pacientePorCns(UUID unidadeId, String cns);

    Optional<UUID> pacientePorIdentificador(UUID unidadeId, String identificador);

    /** Situação do cadastro visível ao usuário (RLS): inexistente, ativo ou reconciliado (RF-037). */
    SituacaoPaciente situacaoPaciente(UUID pacienteId);

    enum SituacaoPaciente { INEXISTENTE, ATIVO, RECONCILIADO }

    UUID inserirPaciente(UUID id, UUID unidadeId, NovoPaciente paciente);

    // --- episódios
    boolean existeEpisodioAtivo(UUID unidadeId, UUID pacienteId);

    Optional<Episodio> episodio(UUID episodioId);

    void inserir(Episodio episodio);

    /** UPDATE com controle otimista; lança ConflitoDeVersaoException se a versão mudou. */
    void atualizar(Episodio episodio);

    // --- pendências
    Optional<Pendencia> pendencia(UUID pendenciaId);

    List<Pendencia> pendenciasAbertas(UUID episodioId);

    void inserir(Pendencia pendencia);

    void atualizar(Pendencia pendencia);

    boolean setorExiste(UUID setorId);

    /** Catálogo global de especialidades: existe e está ativa. */
    boolean especialidadeAtiva(UUID especialidadeId);

    boolean usuarioLotadoNaUnidade(UUID usuarioId, UUID unidadeId);

    // --- linha do tempo e observações
    void inserirEventos(List<EventoEpisodio> eventos);

    void inserirObservacao(UUID id, UUID episodioId, UUID unidadeId, String texto);

    // --- leitura (telas)
    Consultas consultas();
}
