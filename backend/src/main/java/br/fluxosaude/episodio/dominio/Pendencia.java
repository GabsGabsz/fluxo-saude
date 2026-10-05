package br.fluxosaude.episodio.dominio;

import static br.fluxosaude.compartilhado.RegraVioladaException.exigir;

import br.fluxosaude.compartilhado.Textos;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Ação concreta necessária para o fluxo avançar (RF-007, RF-013, RN-005).
 * Agregado separado do episódio para que várias pessoas atualizem pendências do
 * mesmo caso sem conflito de versão no episódio.
 */
public final class Pendencia {

    /** Tolerância para prazo informado "agora" (igual ao banco). */
    private static final Duration TOLERANCIA_PRAZO = Duration.ofMinutes(5);
    /** Pendência é ação operacional de curto prazo; prazo distante indica erro de digitação. */
    static final Duration PRAZO_MAXIMO = Duration.ofDays(30);

    public record ComandoCriacao(CategoriaBloqueio categoria, String descricao, Responsavel responsavel,
                                 Instant prazo, CriticidadeOperacional criticidade) {
        public ComandoCriacao {
            Objects.requireNonNull(categoria, "categoria");
            Objects.requireNonNull(responsavel, "responsável");
            Objects.requireNonNull(prazo, "prazo");
            Objects.requireNonNull(criticidade, "criticidade");
        }
    }

    private final UUID id;
    private final UUID unidadeId;
    private final UUID episodioId;
    private final CategoriaBloqueio categoria;
    private final String descricao;
    private Responsavel responsavel;
    private Instant prazo;
    private final CriticidadeOperacional criticidade;
    private StatusPendencia status;
    private final Instant criadaEm;
    private String resolucao;
    private final int versao;

    private final transient List<EventoEpisodio> eventosPendentes = new ArrayList<>();

    private Pendencia(UUID id, UUID unidadeId, UUID episodioId, CategoriaBloqueio categoria, String descricao,
                      Responsavel responsavel, Instant prazo, CriticidadeOperacional criticidade, StatusPendencia status,
                      Instant criadaEm, String resolucao, int versao) {
        this.id = Objects.requireNonNull(id);
        this.unidadeId = Objects.requireNonNull(unidadeId);
        this.episodioId = Objects.requireNonNull(episodioId);
        this.categoria = Objects.requireNonNull(categoria);
        this.descricao = Objects.requireNonNull(descricao);
        this.responsavel = Objects.requireNonNull(responsavel);
        this.prazo = Objects.requireNonNull(prazo);
        this.criticidade = Objects.requireNonNull(criticidade);
        this.status = Objects.requireNonNull(status);
        this.criadaEm = Objects.requireNonNull(criadaEm);
        this.resolucao = resolucao;
        this.versao = versao;
    }

    public static Pendencia criar(Episodio episodio, ComandoCriacao cmd, UUID autorId, Clock clock, Supplier<UUID> ids) {
        exigir(!episodio.encerrado(), "EPISODIO_ENCERRADO", "Episódio encerrado não aceita novas pendências");
        Instant agora = clock.instant();
        String descricao = Textos.obrigatorio(cmd.descricao(), "Descrição da pendência", 3, 500);
        validarPrazo(cmd.prazo(), agora);
        Pendencia p = new Pendencia(ids.get(), episodio.unidadeId(), episodio.id(), cmd.categoria(), descricao,
                cmd.responsavel(), cmd.prazo(), cmd.criticidade(), StatusPendencia.ABERTA, agora, null, 0);
        p.registrar(ids, TipoEvento.PENDENCIA_CRIADA, agora, autorId,
                "pendencia_id", p.id.toString(), "categoria", cmd.categoria().name(),
                "criticidade", cmd.criticidade().name(), "prazo", cmd.prazo().toString());
        return p;
    }

    private static void validarPrazo(Instant prazo, Instant agora) {
        exigir(!prazo.isBefore(agora.minus(TOLERANCIA_PRAZO)), "PRAZO_PASSADO", "O prazo não pode estar no passado");
        exigir(!prazo.isAfter(agora.plus(PRAZO_MAXIMO)), "PRAZO_DISTANTE", "O prazo não pode passar de 30 dias");
    }

    public static Pendencia reconstituir(UUID id, UUID unidadeId, UUID episodioId, CategoriaBloqueio categoria,
                                         String descricao, Responsavel responsavel, Instant prazo,
                                         CriticidadeOperacional criticidade, StatusPendencia status, Instant criadaEm,
                                         String resolucao, int versao) {
        return new Pendencia(id, unidadeId, episodioId, categoria, descricao, responsavel, prazo, criticidade,
                status, criadaEm, resolucao, versao);
    }

    public void reatribuir(Responsavel novo, UUID autorId, Clock clock, Supplier<UUID> ids) {
        exigirAberta();
        Objects.requireNonNull(novo);
        if (novo.equals(responsavel)) {
            return;
        }
        responsavel = novo;
        registrar(ids, TipoEvento.PENDENCIA_ATUALIZADA, clock.instant(), autorId,
                "pendencia_id", id.toString(), "campo", "responsavel");
    }

    public void alterarPrazo(Instant novoPrazo, UUID autorId, Clock clock, Supplier<UUID> ids) {
        exigirAberta();
        Objects.requireNonNull(novoPrazo);
        Instant agora = clock.instant();
        validarPrazo(novoPrazo, agora);
        if (novoPrazo.equals(prazo)) {
            return;
        }
        Instant anterior = prazo;
        prazo = novoPrazo;
        registrar(ids, TipoEvento.PENDENCIA_ATUALIZADA, agora, autorId,
                "pendencia_id", id.toString(), "campo", "prazo", "de", anterior.toString(), "para", novoPrazo.toString());
    }

    /** RN-005: resolução obrigatória. */
    public void resolver(String textoResolucao, UUID autorId, Clock clock, Supplier<UUID> ids) {
        encerrar(StatusPendencia.RESOLVIDA, Textos.obrigatorio(textoResolucao, "Resolução", 3, 1000), autorId, clock, ids);
    }

    /** RN-005: cancelamento também exige justificativa. */
    public void cancelar(String justificativa, UUID autorId, Clock clock, Supplier<UUID> ids) {
        encerrar(StatusPendencia.CANCELADA, Textos.obrigatorio(justificativa, "Justificativa", 3, 1000), autorId, clock, ids);
    }

    /** RN-008: o desfecho do episódio encerra as pendências abertas. */
    public void encerrarPorDesfecho(TipoDesfecho desfecho, UUID autorId, Clock clock, Supplier<UUID> ids) {
        encerrar(StatusPendencia.ENCERRADA_POR_DESFECHO,
                "Encerrada automaticamente pelo desfecho do episódio: " + desfecho.name(), autorId, clock, ids);
    }

    /** Pendência vencida = aberta e com prazo ultrapassado. */
    public boolean vencida(Instant agora) {
        return status == StatusPendencia.ABERTA && agora.isAfter(prazo);
    }

    public List<EventoEpisodio> retirarEventos() {
        List<EventoEpisodio> copia = List.copyOf(eventosPendentes);
        eventosPendentes.clear();
        return copia;
    }

    public UUID id() { return id; }
    public UUID unidadeId() { return unidadeId; }
    public UUID episodioId() { return episodioId; }
    public CategoriaBloqueio categoria() { return categoria; }
    public String descricao() { return descricao; }
    public Responsavel responsavel() { return responsavel; }
    public Instant prazo() { return prazo; }
    public CriticidadeOperacional criticidade() { return criticidade; }
    public StatusPendencia status() { return status; }
    public Instant criadaEm() { return criadaEm; }
    public String resolucao() { return resolucao; }
    public int versao() { return versao; }

    private void encerrar(StatusPendencia novoStatus, String texto, UUID autorId, Clock clock, Supplier<UUID> ids) {
        exigirAberta();
        status = novoStatus;
        resolucao = texto;
        registrar(ids, TipoEvento.PENDENCIA_ENCERRADA, clock.instant(), autorId,
                "pendencia_id", id.toString(), "status", novoStatus.name());
    }

    private void exigirAberta() {
        exigir(status == StatusPendencia.ABERTA, "PENDENCIA_ENCERRADA", "Pendência já encerrada não pode ser alterada");
    }

    private void registrar(Supplier<UUID> ids, TipoEvento tipo, Instant quando, UUID autorId, String... kv) {
        Map<String, String> dados = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            dados.put(kv[i], kv[i + 1]);
        }
        eventosPendentes.add(new EventoEpisodio(ids.get(), episodioId, tipo, quando, autorId, dados, null));
    }
}
