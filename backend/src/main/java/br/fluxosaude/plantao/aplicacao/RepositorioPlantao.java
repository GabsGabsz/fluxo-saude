package br.fluxosaude.plantao.aplicacao;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.plantao.dominio.CasoAtual;
import br.fluxosaude.plantao.dominio.ConteudoPassagem;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Nomes, descrições e rótulos lidos do estado ATUAL, numa única consulta (não ficam gravados na
     * passagem). Os rótulos de setor, etapa, motivo e profissional vêm do servidor para que valores
     * novos, inativados ou ainda fora do catálogo carregado pela tela apareçam corretamente.
     */
    record Nomes(Map<UUID, String> pacientes, Map<UUID, String> pendencias, Map<UUID, String> setores,
                 Map<UUID, String> etapas, Map<UUID, String> motivos, Map<UUID, String> profissionais,
                 Map<RegraVersaoId, RegraNaVersao> regrasPorVersao, Map<UUID, RegraAtual> regrasAtuais) {
        public Nomes {
            pacientes = Map.copyOf(pacientes);
            pendencias = Map.copyOf(pendencias);
            setores = Map.copyOf(setores);
            etapas = Map.copyOf(etapas);
            motivos = Map.copyOf(motivos);
            profissionais = Map.copyOf(profissionais);
            regrasPorVersao = Map.copyOf(regrasPorVersao);
            regrasAtuais = Map.copyOf(regrasAtuais);
        }
    }

    /** Uma versão de uma regra de alerta (a gravada no alerta da passagem). */
    record RegraVersaoId(UUID regraId, int versao) {
    }

    /**
     * Dados DAQUELA versão da regra (histórico imutável, V18): nunca os da configuração atual. Ausente
     * do mapa = versão anterior ao histórico (detalhes indisponíveis).
     */
    record RegraNaVersao(UUID regraId, int versao, String nome, String tipo, Long limiteMinutos, String acaoEsperada,
                         boolean ativa) {
    }

    /** Situação ATUAL da regra (para dizer, junto do alerta histórico, se ela mudou ou foi desativada). */
    record RegraAtual(UUID regraId, int versao, boolean ativa) {
    }

    /** Identificadores cujos nomes/rótulos serão exibidos. */
    record Referencias(Set<UUID> episodios, Set<UUID> pendencias, Set<UUID> setores, Set<UUID> etapas,
                       Set<UUID> motivos, Set<UUID> profissionais, Set<RegraVersaoId> regras) {
        public Referencias {
            regras = Set.copyOf(regras);
            episodios = Set.copyOf(episodios);
            pendencias = Set.copyOf(pendencias);
            setores = Set.copyOf(setores);
            etapas = Set.copyOf(etapas);
            motivos = Set.copyOf(motivos);
            profissionais = Set.copyOf(profissionais);
        }
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

    Nomes nomes(Referencias referencias);

    /** Auditoria semântica (sem texto livre). */
    void auditar(String acao, UUID passagemId, Map<String, Object> dados);

    /**
     * Registra uma LEITURA nominal (RNF-002, ADR-0003): ator, unidade e instante vêm do contexto do
     * banco; {@code episodios} são as referências exibidas além das já registradas na própria
     * passagem (conjunto gravado uma vez por conteúdo, endereçado pelo seu hash); {@code dados}
     * só com valores escalares do código (sem nomes nem descrições).
     *
     * @param passagemId nulo na prévia (o conteúdo ainda não existe como registro)
     */
    void auditarConsulta(String acao, UUID passagemId, Collection<UUID> episodios, Map<String, Object> dados);
}
