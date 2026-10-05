package br.fluxosaude.episodio.dominio;

import br.fluxosaude.compartilhado.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Configuração equivalente a fluxo.provisionar_unidade() (subconjunto usado nos
 * testes) + relógio controlável.
 */
public final class FluxoDeTeste {

    public static final UUID UNIDADE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    public static final UUID SETOR = UUID.fromString("00000000-0000-0000-0000-0000000005a1");
    public static final UUID AUTOR = UUID.fromString("11111111-1111-1111-1111-000000000002");
    public static final UUID PACIENTE = UUID.fromString("22222222-0000-0000-0000-000000000020");

    public final Map<String, Etapa> etapas = new TreeMap<>();
    public final Map<String, MotivoBloqueio> motivos = new TreeMap<>();
    public final RelogioDeTeste relogio = new RelogioDeTeste(Instant.parse("2026-10-05T11:12:00Z")); // 08:12 em Bom Jesus
    public final Supplier<UUID> ids = new UuidV7(relogio)::proximo;
    public final FluxoConfigurado fluxo;

    public FluxoDeTeste() {
        etapa("EM_ATENDIMENTO", NaturezaEtapa.ATENDIMENTO, null, true, false, false, false, true);
        etapa("AGUARDANDO_EXAME_PARECER", NaturezaEtapa.ESPERA, null, false, true, false, false, true);
        etapa("AGUARDANDO_DECISAO", NaturezaEtapa.ESPERA, null, false, true, false, false, true);
        etapa("AGUARDANDO_SOLICITACAO_TRANSFERENCIA", NaturezaEtapa.ESPERA, null, false, true, false, false, true);
        etapa("TRANSFERENCIA_SOLICITADA", NaturezaEtapa.ESPERA, null, false, true, true, false, true);
        etapa("AGUARDANDO_RECURSO_LEITO", NaturezaEtapa.ESPERA, null, false, true, false, false, true);
        etapa("ACEITO", NaturezaEtapa.ACEITO, null, false, false, false, false, true);
        etapa("AGUARDANDO_TRANSPORTE", NaturezaEtapa.TRANSPORTE, null, false, true, false, false, true);
        etapa("TRANSFERIDO", NaturezaEtapa.DESFECHO, TipoDesfecho.TRANSFERENCIA, false, false, false, false, true);
        etapa("ALTA", NaturezaEtapa.DESFECHO, TipoDesfecho.ALTA, false, false, false, false, true);
        etapa("CANCELADO_ENCERRADO", NaturezaEtapa.DESFECHO, TipoDesfecho.ENCERRAMENTO_ADMINISTRATIVO,
                false, false, false, true, true);
        etapa("ETAPA_DESATIVADA", NaturezaEtapa.ESPERA, null, false, false, false, false, false);

        motivo("SOLICITACAO_NAO_ENVIADA", CategoriaBloqueio.REGULACAO, false, true);
        motivo("AGUARDANDO_ANALISE_ACEITE", CategoriaBloqueio.REGULACAO, false, true);
        motivo("SEM_LEITO_ESPECIALIDADE", CategoriaBloqueio.LEITO_CAPACIDADE, false, true);
        motivo("TRANSPORTE_PENDENTE", CategoriaBloqueio.LOGISTICA, false, true);
        motivo("AGUARDANDO_EXAME", CategoriaBloqueio.ASSISTENCIAL, false, true);
        motivo("OUTROS", CategoriaBloqueio.OUTROS, true, true);
        motivo("MOTIVO_DESATIVADO", CategoriaBloqueio.ADMINISTRATIVO, false, false);

        List<FluxoConfigurado.Transicao> t = new ArrayList<>();
        String[][] arestas = {
            {"EM_ATENDIMENTO", "AGUARDANDO_EXAME_PARECER"},
            {"EM_ATENDIMENTO", "AGUARDANDO_DECISAO"},
            {"EM_ATENDIMENTO", "AGUARDANDO_SOLICITACAO_TRANSFERENCIA"},
            {"EM_ATENDIMENTO", "ETAPA_DESATIVADA"},
            {"AGUARDANDO_EXAME_PARECER", "EM_ATENDIMENTO"},
            {"AGUARDANDO_SOLICITACAO_TRANSFERENCIA", "TRANSFERENCIA_SOLICITADA"},
            {"TRANSFERENCIA_SOLICITADA", "AGUARDANDO_RECURSO_LEITO"},
            {"TRANSFERENCIA_SOLICITADA", "ACEITO"},
            {"AGUARDANDO_RECURSO_LEITO", "ACEITO"},
            {"ACEITO", "AGUARDANDO_TRANSPORTE"},
            {"ACEITO", "TRANSFERIDO"},
            {"AGUARDANDO_TRANSPORTE", "TRANSFERIDO"},
            {"EM_ATENDIMENTO", "ALTA"},
            {"EM_ATENDIMENTO", "CANCELADO_ENCERRADO"},
        };
        for (String[] a : arestas) {
            t.add(new FluxoConfigurado.Transicao(etapas.get(a[0]).id(), etapas.get(a[1]).id()));
        }
        fluxo = new FluxoConfigurado(UNIDADE, etapas.values(), t, motivos.values(), PoliticaTempo.PADRAO);
    }

    public UUID etapa(String codigo) {
        return etapas.get(codigo).id();
    }

    public UUID motivo(String codigo) {
        return motivos.get(codigo).id();
    }

    public Episodio abrirAgora() {
        return Episodio.abrir(new Episodio.ComandoAbertura(PACIENTE, SETOR, MomentoInformado.agora(relogio), null),
                false, fluxo, AUTOR, relogio, ids);
    }

    void mudar(Episodio ep, String etapa, String motivo) {
        mudar(ep, etapa, motivo, null, null);
    }

    void mudar(Episodio ep, String etapa, String motivo, ProtocoloExterno protocolo, String justificativa) {
        Episodio.MotivoInformado m = motivo == null ? null : new Episodio.MotivoInformado(motivo(motivo), null);
        ep.mudarEtapa(new Episodio.ComandoMudancaEtapa(etapa(etapa), MomentoInformado.agora(relogio), m, protocolo, justificativa),
                fluxo, AUTOR, relogio, ids);
    }

    private void etapa(String codigo, NaturezaEtapa natureza, TipoDesfecho desfecho, boolean inicial,
                       boolean exigeMotivo, boolean exigeProtocolo, boolean exigeJustificativa, boolean ativa) {
        etapas.put(codigo, new Etapa(UUID.randomUUID(), codigo, codigo.toLowerCase(), natureza, desfecho, inicial,
                exigeMotivo, exigeProtocolo, exigeJustificativa, ativa));
    }

    private void motivo(String codigo, CategoriaBloqueio categoria, boolean exigeDetalhe, boolean ativo) {
        motivos.put(codigo, new MotivoBloqueio(UUID.randomUUID(), categoria, codigo, codigo.toLowerCase(),
                exigeDetalhe, ativo));
    }

    /** Relógio mutável para simular a passagem do tempo. */
    public static final class RelogioDeTeste extends Clock {
        private Instant agora;

        RelogioDeTeste(Instant inicio) {
            this.agora = inicio;
        }

        public void avancar(java.time.Duration d) {
            agora = agora.plus(d);
        }

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
}
