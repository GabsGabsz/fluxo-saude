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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Agregado Episódio: uma passagem do paciente pela unidade, da entrada ao desfecho
 * (ERS §4). Toda mudança gera {@link EventoEpisodio} para a linha do tempo (RF-014).
 *
 * <p>Regras garantidas aqui (e repetidas no banco como última linha de defesa):
 * RF-004, RF-008/RN-003, RF-009, RF-015, RN-008, cronologia e imutabilidade após
 * encerramento. O sistema nunca altera prioridade clínica (RN-001): este agregado
 * não possui nenhum atributo clínico.
 */
public final class Episodio {

    /** Motivo informado pelo usuário ao entrar/permanecer em espera. */
    public record MotivoInformado(UUID motivoId, String detalhe) {
        public MotivoInformado {
            Objects.requireNonNull(motivoId);
        }
    }

    public record ComandoAbertura(UUID pacienteId, UUID setorId, Instant entradaEm) {
        public ComandoAbertura {
            Objects.requireNonNull(pacienteId, "paciente");
            Objects.requireNonNull(setorId, "setor");
            Objects.requireNonNull(entradaEm, "entrada");
        }
    }

    /**
     * @param motivo        obrigatório se a etapa destino exigir (RN-003); opcional caso contrário
     * @param protocolo     registra/atualiza o protocolo externo junto com a mudança (RF-009)
     * @param justificativa obrigatória em desfechos que a exigem (RF-015)
     */
    public record ComandoMudancaEtapa(UUID etapaDestinoId, Instant ocorridoEm, MotivoInformado motivo,
                                      ProtocoloExterno protocolo, String justificativa) {
        public ComandoMudancaEtapa {
            Objects.requireNonNull(etapaDestinoId, "etapa destino");
            Objects.requireNonNull(ocorridoEm, "instante");
        }
    }

    private final UUID id;
    private final UUID unidadeId;
    private final UUID pacienteId;
    private final Instant entradaEm;
    private UUID setorId;
    private UUID etapaId;
    private Instant etapaDesde;
    private Bloqueio bloqueio;
    private ProtocoloExterno protocolo;
    private UUID especialidadeRequeridaId;
    private String destinoDescricao;
    private TipoDesfecho desfecho;
    private Instant encerradoEm;
    private String justificativaEncerramento;
    private final int versao;
    /** Último instante de fato registrado — piso cronológico para novos registros. */
    private Instant ultimoFatoEm;

    private final transient List<EventoEpisodio> eventosPendentes = new ArrayList<>();

    private Episodio(UUID id, UUID unidadeId, UUID pacienteId, UUID setorId, Instant entradaEm,
                     UUID etapaId, Instant etapaDesde, int versao) {
        this.id = Objects.requireNonNull(id);
        this.unidadeId = Objects.requireNonNull(unidadeId);
        this.pacienteId = Objects.requireNonNull(pacienteId);
        this.setorId = Objects.requireNonNull(setorId);
        this.entradaEm = Objects.requireNonNull(entradaEm);
        this.etapaId = Objects.requireNonNull(etapaId);
        this.etapaDesde = Objects.requireNonNull(etapaDesde);
        this.versao = versao;
        this.ultimoFatoEm = etapaDesde;
    }

    // ------------------------------------------------------------------ fábrica

    /** RF-002. A unicidade de episódio ativo (RF-003) é verificada na aplicação + índice único. */
    public static Episodio abrir(ComandoAbertura cmd, FluxoConfigurado fluxo, UUID autorId,
                                 Clock clock, Supplier<UUID> ids) {
        Instant agora = clock.instant();
        fluxo.politicaTempo().validar(cmd.entradaEm(), agora, null, "Data/hora de entrada");
        Etapa inicial = fluxo.etapaInicial();
        Episodio ep = new Episodio(ids.get(), fluxo.unidadeId(), cmd.pacienteId(), cmd.setorId(),
                cmd.entradaEm(), inicial.id(), cmd.entradaEm(), 0);
        ep.registrar(ids, TipoEvento.EPISODIO_ABERTO, cmd.entradaEm(), autorId,
                dados("etapa", inicial.codigo(), "setor_id", cmd.setorId().toString()));
        return ep;
    }

    /** Reconstrução a partir da persistência (sem validação de "agora" e sem eventos). */
    public static Episodio reconstituir(UUID id, UUID unidadeId, UUID pacienteId, UUID setorId, Instant entradaEm,
                                        UUID etapaId, Instant etapaDesde, Bloqueio bloqueio, ProtocoloExterno protocolo,
                                        UUID especialidadeRequeridaId, String destinoDescricao, TipoDesfecho desfecho,
                                        Instant encerradoEm, String justificativaEncerramento, Instant ultimoFatoEm,
                                        int versao) {
        Episodio ep = new Episodio(id, unidadeId, pacienteId, setorId, entradaEm, etapaId, etapaDesde, versao);
        ep.bloqueio = bloqueio;
        ep.protocolo = protocolo;
        ep.especialidadeRequeridaId = especialidadeRequeridaId;
        ep.destinoDescricao = destinoDescricao;
        ep.desfecho = desfecho;
        ep.encerradoEm = encerradoEm;
        ep.justificativaEncerramento = justificativaEncerramento;
        ep.ultimoFatoEm = ultimoFatoEm == null || ultimoFatoEm.isBefore(etapaDesde) ? etapaDesde : ultimoFatoEm;
        return ep;
    }

    // ---------------------------------------------------------------- comandos

    /** RF-004 / RF-008 / RF-009 / RF-015. */
    public void mudarEtapa(ComandoMudancaEtapa cmd, FluxoConfigurado fluxo, UUID autorId,
                           Clock clock, Supplier<UUID> ids) {
        exigirAberto();
        exigirMesmaUnidade(fluxo);
        Etapa origem = fluxo.etapa(etapaId);
        Etapa destino = fluxo.etapa(cmd.etapaDestinoId());
        exigir(destino.ativa(), "ETAPA_INATIVA", "Etapa \"" + destino.nome() + "\" está inativa");
        exigir(fluxo.transicaoPermitida(origem.id(), destino.id()), "TRANSICAO_NAO_PERMITIDA",
                "Não é permitido passar de \"" + origem.nome() + "\" para \"" + destino.nome() + "\"");

        Instant quando = cmd.ocorridoEm();
        fluxo.politicaTempo().validar(quando, clock.instant(), ultimoFatoEm, "Data/hora da mudança de etapa");

        // ---- 1. Validar TUDO antes de alterar qualquer estado (comando atômico) ----
        ProtocoloExterno protocoloResultante = cmd.protocolo() != null ? cmd.protocolo() : protocolo;
        exigir(!destino.exigeProtocoloExterno() || protocoloResultante != null, "PROTOCOLO_OBRIGATORIO",
                "A etapa \"" + destino.nome() + "\" exige o número do protocolo no sistema oficial");

        String justificativa = Textos.opcional(cmd.justificativa(), "Justificativa", 3, 1000);
        if (destino.terminal()) {
            exigir(cmd.motivo() == null, "DESFECHO_SEM_BLOQUEIO", "Desfecho não admite motivo de bloqueio");
            exigir(!destino.exigeJustificativa() || justificativa != null, "JUSTIFICATIVA_OBRIGATORIA",
                    "O desfecho \"" + destino.nome() + "\" exige justificativa");
        } else {
            exigir(justificativa == null, "JUSTIFICATIVA_INDEVIDA", "Justificativa só se aplica a desfecho");
            exigir(!destino.exigeMotivoBloqueio() || cmd.motivo() != null, "RN-003",
                    "A etapa \"" + destino.nome() + "\" exige o motivo do bloqueio");
        }
        Bloqueio novoBloqueio = calcularBloqueio(cmd.motivo(), quando, fluxo);

        // ---- 2. Aplicar ----
        if (cmd.protocolo() != null && !cmd.protocolo().equals(protocolo)) {
            protocolo = cmd.protocolo();
            registrar(ids, TipoEvento.PROTOCOLO_REGISTRADO, quando, autorId,
                    dados("sistema", protocolo.sistema(), "numero", protocolo.numero()));
        }
        etapaId = destino.id();
        etapaDesde = quando;
        ultimoFatoEm = quando;
        registrar(ids, TipoEvento.ETAPA_ALTERADA, quando, autorId,
                dados("de", origem.codigo(), "para", destino.codigo()));
        aplicarBloqueio(novoBloqueio, quando, fluxo, autorId, ids);

        if (destino.terminal()) {
            desfecho = destino.desfecho();
            encerradoEm = quando;
            justificativaEncerramento = justificativa;
            registrar(ids, TipoEvento.EPISODIO_ENCERRADO, quando, autorId, dados("desfecho", desfecho.name()));
        }
    }

    /** Troca/define o motivo sem mudar de etapa (ex.: "sem vaga" → "aguardando aceite"). */
    public void definirMotivo(MotivoInformado motivo, Instant ocorridoEm, FluxoConfigurado fluxo, UUID autorId,
                              Clock clock, Supplier<UUID> ids) {
        exigirAberto();
        exigirMesmaUnidade(fluxo);
        Etapa atual = fluxo.etapa(etapaId);
        exigir(motivo != null || !atual.exigeMotivoBloqueio(), "RN-003",
                "A etapa \"" + atual.nome() + "\" exige o motivo do bloqueio");
        fluxo.politicaTempo().validar(ocorridoEm, clock.instant(), ultimoFatoEm, "Data/hora do bloqueio");
        Bloqueio novo = calcularBloqueio(motivo, ocorridoEm, fluxo);
        ultimoFatoEm = ocorridoEm;
        aplicarBloqueio(novo, ocorridoEm, fluxo, autorId, ids);
    }

    public void registrarProtocolo(ProtocoloExterno novo, Instant ocorridoEm, FluxoConfigurado fluxo, UUID autorId,
                                   Clock clock, Supplier<UUID> ids) {
        exigirAberto();
        Objects.requireNonNull(novo);
        fluxo.politicaTempo().validar(ocorridoEm, clock.instant(), ultimoFatoEm, "Data/hora do protocolo");
        if (novo.equals(protocolo)) {
            return;
        }
        protocolo = novo;
        ultimoFatoEm = ocorridoEm;
        registrar(ids, TipoEvento.PROTOCOLO_REGISTRADO, ocorridoEm, autorId,
                dados("sistema", novo.sistema(), "numero", novo.numero()));
    }

    public void definirDestino(UUID especialidadeId, String descricao, Instant ocorridoEm, FluxoConfigurado fluxo,
                               UUID autorId, Clock clock, Supplier<UUID> ids) {
        exigirAberto();
        fluxo.politicaTempo().validar(ocorridoEm, clock.instant(), ultimoFatoEm, "Data/hora do destino");
        String desc = Textos.opcional(descricao, "Destino", 1, 200);
        especialidadeRequeridaId = especialidadeId;
        destinoDescricao = desc;
        ultimoFatoEm = ocorridoEm;
        registrar(ids, TipoEvento.DESTINO_DEFINIDO, ocorridoEm, autorId,
                dados("especialidade_id", especialidadeId == null ? null : especialidadeId.toString()));
    }

    public void transferirSetor(UUID novoSetorId, Instant ocorridoEm, FluxoConfigurado fluxo, UUID autorId,
                                Clock clock, Supplier<UUID> ids) {
        exigirAberto();
        Objects.requireNonNull(novoSetorId);
        if (novoSetorId.equals(setorId)) {
            return;
        }
        fluxo.politicaTempo().validar(ocorridoEm, clock.instant(), ultimoFatoEm, "Data/hora da troca de setor");
        UUID anterior = setorId;
        setorId = novoSetorId;
        ultimoFatoEm = ocorridoEm;
        registrar(ids, TipoEvento.SETOR_ALTERADO, ocorridoEm, autorId,
                dados("de", anterior.toString(), "para", novoSetorId.toString()));
    }

    // --------------------------------------------------------------- relógios

    /** RF-005. Para no encerramento (RN-008). */
    public Duration tempoTotal(Instant agora) {
        return naoNegativa(Duration.between(entradaEm, encerrado() ? encerradoEm : agora));
    }

    /** RF-006. */
    public Duration tempoNaEtapa(Instant agora) {
        return naoNegativa(Duration.between(etapaDesde, encerrado() ? encerradoEm : agora));
    }

    public Optional<Duration> tempoBloqueado(Instant agora) {
        return Optional.ofNullable(bloqueio).map(b -> naoNegativa(Duration.between(b.desde(), agora)));
    }

    // ---------------------------------------------------------------- consulta

    public boolean encerrado() {
        return encerradoEm != null;
    }

    /** Eventos gerados desde a carga; devem ser persistidos na mesma transação. */
    public List<EventoEpisodio> retirarEventos() {
        List<EventoEpisodio> copia = List.copyOf(eventosPendentes);
        eventosPendentes.clear();
        return copia;
    }

    public UUID id() { return id; }
    public UUID unidadeId() { return unidadeId; }
    public UUID pacienteId() { return pacienteId; }
    public UUID setorId() { return setorId; }
    public Instant entradaEm() { return entradaEm; }
    public UUID etapaId() { return etapaId; }
    public Instant etapaDesde() { return etapaDesde; }
    public Optional<Bloqueio> bloqueio() { return Optional.ofNullable(bloqueio); }
    public Optional<ProtocoloExterno> protocolo() { return Optional.ofNullable(protocolo); }
    public Optional<UUID> especialidadeRequeridaId() { return Optional.ofNullable(especialidadeRequeridaId); }
    public Optional<String> destinoDescricao() { return Optional.ofNullable(destinoDescricao); }
    public Optional<TipoDesfecho> desfecho() { return Optional.ofNullable(desfecho); }
    public Optional<Instant> encerradoEm() { return Optional.ofNullable(encerradoEm); }
    public Optional<String> justificativaEncerramento() { return Optional.ofNullable(justificativaEncerramento); }
    public int versao() { return versao; }

    // ---------------------------------------------------------------- internos

    /** Valida o motivo informado e calcula o novo bloqueio, SEM alterar estado. */
    private Bloqueio calcularBloqueio(MotivoInformado informado, Instant quando, FluxoConfigurado fluxo) {
        if (informado == null) {
            return null;
        }
        MotivoBloqueio motivo = fluxo.motivo(informado.motivoId());
        boolean mesmoMotivo = bloqueio != null && bloqueio.motivoId().equals(motivo.id());
        exigir(mesmoMotivo || motivo.ativo(), "MOTIVO_INATIVO", "Motivo \"" + motivo.descricao() + "\" está inativo");
        String detalhe = Textos.opcional(informado.detalhe(), "Detalhe do bloqueio", 3, 500);
        exigir(!motivo.exigeDetalhe() || detalhe != null, "DETALHE_OBRIGATORIO",
                "O motivo \"" + motivo.descricao() + "\" exige detalhamento");
        // Mesmo motivo: preserva o início do bloqueio (o relógio do gargalo não "zera").
        return new Bloqueio(motivo.id(), detalhe, mesmoMotivo ? bloqueio.desde() : quando);
    }

    private void aplicarBloqueio(Bloqueio novo, Instant quando, FluxoConfigurado fluxo, UUID autorId,
                                 Supplier<UUID> ids) {
        if (Objects.equals(novo, bloqueio)) {
            return;
        }
        if (novo == null) {
            registrar(ids, TipoEvento.BLOQUEIO_REMOVIDO, quando, autorId,
                    dados("motivo", fluxo.motivo(bloqueio.motivoId()).codigo()));
        } else {
            MotivoBloqueio motivo = fluxo.motivo(novo.motivoId());
            registrar(ids, TipoEvento.BLOQUEIO_DEFINIDO, quando, autorId,
                    dados("motivo", motivo.codigo(), "categoria", motivo.categoria().name()));
        }
        bloqueio = novo;
    }

    private void exigirAberto() {
        exigir(!encerrado(), "EPISODIO_ENCERRADO",
                "Episódio encerrado não pode ser alterado; registre uma correção");
    }

    private void exigirMesmaUnidade(FluxoConfigurado fluxo) {
        if (!fluxo.unidadeId().equals(unidadeId)) {
            throw new IllegalArgumentException("configuração de fluxo de outra unidade");
        }
    }

    private void registrar(Supplier<UUID> ids, TipoEvento tipo, Instant quando, UUID autorId, Map<String, String> dados) {
        eventosPendentes.add(new EventoEpisodio(ids.get(), id, tipo, quando, autorId, dados, null));
    }

    private static Map<String, String> dados(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    private static Duration naoNegativa(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }
}
