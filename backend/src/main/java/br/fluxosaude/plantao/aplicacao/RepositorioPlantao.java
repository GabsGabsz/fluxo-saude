package br.fluxosaude.plantao.aplicacao;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.plantao.dominio.CasoAtual;
import br.fluxosaude.plantao.dominio.ConteudoPassagem;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência da passagem de plantão (V16). Toda operação roda numa transação com o contexto
 * validado pelo banco (unidade ativa, papéis, versão de credencial); RLS restringe à unidade e
 * aos papéis com PLANTAO_GERENCIAR.
 */
public interface RepositorioPlantao {

    /** Registro da passagem (metadados; o conteúdo fica em {@link #conteudo}). */
    record Passagem(UUID id, String status, Instant periodoInicio, UUID entreguePor, String entreguePorNome,
                    Instant entregueEm, String assinatura, int totalCasos, int totalCriticos, int totalTransferencias,
                    int totalPendencias, int totalVencidas, String observacao, UUID recebidaPor, String recebidaPorNome,
                    Instant recebidaEm, Map<String, Integer> diferencasRecebimento, UUID canceladaPor,
                    String canceladaPorNome, Instant canceladaEm, String justificativaCancelamento, int versao) {
    }

    /** Nomes e descrições lidos do estado ATUAL (não ficam gravados na passagem). */
    record Nomes(Map<UUID, String> pacientes, Map<UUID, String> pendencias) {
    }

    /** Todos os episódios abertos da unidade (até {@code limite}), numa única consulta. */
    List<CasoAtual> casosAbertos(int limite);

    List<RegraAlerta> regrasAtivas();

    Optional<Passagem> pendente();

    Optional<Instant> ultimaRecebidaEm();

    /** Insere a passagem e o conteúdo; outra pendente na unidade → ConflitoDeEstado PASSAGEM_PENDENTE. */
    void inserir(UUID id, ConteudoPassagem conteudo, String observacao);

    Optional<Passagem> passagem(UUID id);

    Optional<ConteudoPassagem> conteudo(UUID id);

    List<Passagem> historico(int limite);

    /** UPDATE versionado (ENTREGUE → RECEBIDA); versão divergente → ConflitoDeVersaoException. */
    void receber(UUID id, int versaoLida, String assinaturaRecebimento, Map<String, Integer> diferencas);

    /** UPDATE versionado (ENTREGUE → CANCELADA); versão divergente → ConflitoDeVersaoException. */
    void cancelar(UUID id, int versaoLida, String justificativa);

    Nomes nomes(Collection<UUID> episodios, Collection<UUID> pendencias);

    /** Auditoria semântica (sem texto livre). */
    void auditar(String acao, UUID passagemId, Map<String, Object> dados);
}
