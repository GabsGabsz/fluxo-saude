package br.fluxosaude.episodio.web;

import br.fluxosaude.episodio.aplicacao.Consultas;
import br.fluxosaude.episodio.aplicacao.ServicoConsultas;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import br.fluxosaude.episodio.dominio.MomentoInformado;
import br.fluxosaude.episodio.dominio.Responsavel;
import br.fluxosaude.identidade.dominio.Papel;
import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contratos JSON da API de episódios e pendências. Todo texto tem limite de tamanho;
 * a validação de conteúdo (regras de negócio) fica no domínio, não aqui.
 */
final class EpisodioDtos {

    private EpisodioDtos() {
    }

    /** Instante do fato: omitido = agora (servidor). Retroativo exige justificativa e permissão (RNF-017). */
    record Momento(Instant ocorridoEm, @Size(max = 120) String justificativaAjuste) {
        MomentoInformado paraDominio() {
            return ocorridoEm == null ? null : new MomentoInformado(ocorridoEm, justificativaAjuste);
        }
    }

    record NovoPacienteRequest(@NotNull @Size(max = 200) String nome, LocalDate dataNascimento,
                               @Size(max = 20) String cns, @Size(max = 40) String identificadorInstitucional) {
    }

    record AbrirRequest(UUID pacienteId, @Valid NovoPacienteRequest novoPaciente, @NotNull UUID setorId,
                        @Valid Momento momento, @Size(max = 300) String justificativaDuplicidade) {
    }

    record MudarEtapaRequest(@NotNull @Min(0) Integer versao, @NotNull UUID etapaId, UUID motivoId,
                             @Size(max = 500) String motivoDetalhe, @Size(max = 32) String protocoloSistema,
                             @Size(max = 60) String protocoloNumero, @Size(max = 1000) String justificativa,
                             @Valid Momento momento) {
    }

    record MotivoRequest(@NotNull @Min(0) Integer versao, UUID motivoId, @Size(max = 500) String detalhe,
                         @Valid Momento momento) {
    }

    record ProtocoloRequest(@NotNull @Min(0) Integer versao, @NotNull @Size(max = 32) String sistema,
                            @NotNull @Size(max = 60) String numero, @Valid Momento momento) {
    }

    record DestinoRequest(@NotNull @Min(0) Integer versao, UUID especialidadeId, @Size(max = 200) String descricao,
                          @Valid Momento momento) {
    }

    record SetorRequest(@NotNull @Min(0) Integer versao, @NotNull UUID setorId, @Valid Momento momento) {
    }

    record ObservacaoRequest(@NotNull @Size(max = 1000) String texto) {
    }

    /** Exatamente um dos três campos; a existência do usuário/setor na unidade é validada no serviço. */
    record ResponsavelDto(UUID usuarioId, UUID setorId, Papel papel) {
        Responsavel paraDominio() {
            int informados = (usuarioId != null ? 1 : 0) + (setorId != null ? 1 : 0) + (papel != null ? 1 : 0);
            if (informados != 1) {
                throw new br.fluxosaude.compartilhado.RegraVioladaException("RESPONSAVEL_INVALIDO",
                        "Informe exatamente um responsável: usuário, setor ou perfil");
            }
            if (usuarioId != null) {
                return new Responsavel.Usuario(usuarioId);
            }
            return setorId != null ? new Responsavel.Setor(setorId) : new Responsavel.Perfil(papel);
        }
    }

    record CriarPendenciaRequest(@NotNull CategoriaBloqueio categoria, @NotNull @Size(max = 500) String descricao,
                                 @NotNull @Valid ResponsavelDto responsavel, @NotNull Instant prazo,
                                 @NotNull CriticidadeOperacional criticidade) {
    }

    record AtualizarPendenciaRequest(@NotNull @Min(0) Integer versao, @Valid ResponsavelDto responsavel, Instant prazo) {
    }

    record EncerrarPendenciaRequest(@NotNull @Min(0) Integer versao, @NotNull @Size(max = 1000) String texto) {
    }

    record Resultado(UUID id, int versao) {
    }

    /** {@code agora} = relógio do servidor: o cliente calcula cronômetros sem depender do relógio local. */
    record TorreResponse(Instant agora, List<Consultas.LinhaTorre> itens,
                         java.util.Map<UUID, List<AlertaResumo>> alertas) {
    }

    /**
     * Destaque de alerta OPERACIONAL na Torre (RF-011): não é risco clínico nem prioridade (RN-013).
     * Os detalhes (responsável, ação esperada) estão em /api/travados.
     */
    record AlertaResumo(UUID regraId, int regraVersao, String regraNome,
                        br.fluxosaude.alerta.dominio.TipoRegraAlerta tipo, Instant referenciaEm, Instant atingidoEm,
                        UUID pendenciaId, Long limiteMinutos, String acaoEsperada, boolean ciente) {
        static AlertaResumo de(br.fluxosaude.alerta.aplicacao.ServicoAlertas.AlertaVisto v) {
            var a = v.alerta();
            return new AlertaResumo(a.regraId(), a.regraVersao(), a.regraNome(), a.tipo(), a.referenciaEm(),
                    a.atingidoEm(), a.pendenciaId(), a.limite() == null ? null : a.limite().toMinutes(),
                    a.acaoEsperada(), v.ciencia() != null);
        }
    }

    /**
     * {@code dados} vem do banco (jsonb serializado pelo próprio PostgreSQL), por isso é
     * emitido como JSON bruto — nunca há texto do cliente concatenado aqui.
     */
    record EventoDto(UUID id, String tipo, Instant ocorridoEm, Instant registradoEm, UUID autorId, String autorNome,
                     @JsonRawValue String dados, UUID corrigeEventoId) {
        static EventoDto de(Consultas.LinhaEvento e) {
            return new EventoDto(e.id(), e.tipo(), e.ocorridoEm(), e.registradoEm(), e.autorId(), e.autorNome(),
                    e.dadosJson() == null ? "{}" : e.dadosJson(), e.corrigeEventoId());
        }
    }

    record CasoResponse(Instant agora, Consultas.LinhaTorre resumo, String pacienteCns, String pacienteIdentificador,
                        LocalDate pacienteNascimento, String destinoDescricao, String motivoDetalhe, String desfecho,
                        Instant encerradoEm, String justificativaEncerramento, String justificativaDuplicidade,
                        List<Consultas.LinhaPendencia> pendencias, List<EventoDto> linhaDoTempo,
                        List<Consultas.Observacao> observacoes, boolean historicoTruncado,
                        List<AlertaResumo> alertas) {
        static CasoResponse de(Instant agora, Consultas.Caso c, List<AlertaResumo> alertas) {
            return new CasoResponse(agora, c.resumo(), c.pacienteCns(), c.pacienteIdentificador(),
                    c.pacienteNascimento(), c.destinoDescricao(), c.motivoDetalhe(), c.desfecho(), c.encerradoEm(),
                    c.justificativaEncerramento(), c.justificativaDuplicidade(), c.pendencias(),
                    c.linhaDoTempo().stream().map(EventoDto::de).toList(), c.observacoes(), c.historicoTruncado(),
                    alertas);
        }
    }

    record PainelResponse(Instant agora, List<ServicoConsultas.LinhaPainelPseudonimizada> itens) {
    }
}
