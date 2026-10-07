package br.fluxosaude.plantao.web;

import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.web.ProvedorContexto;
import br.fluxosaude.plantao.aplicacao.RepositorioPlantao;
import br.fluxosaude.plantao.aplicacao.ServicoPlantao;
import br.fluxosaude.plantao.dominio.ConteudoPassagem;
import br.fluxosaude.plantao.dominio.Diferencas;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API da passagem de plantão (M06: RF-016, RF-017; ERS §10.4). Exige PLANTAO_GERENCIAR e acesso
 * nominal (o conteúdo identifica pacientes). Toda confirmação envia a assinatura do conteúdo que
 * foi exibido; se o servidor recompõe outro conteúdo, responde 409 e nada é gravado.
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api/plantao")
class PlantaoController {

    record AlertaDto(UUID regraId, int regraVersao, String tipo, Instant referenciaEm, Instant atingidoEm,
                     UUID pendenciaId) {
    }

    record PendenciaDto(UUID id, String descricao, int versao, String categoria, String criticidade, Instant prazo,
                        boolean vencida, UUID responsavelUsuarioId, UUID responsavelSetorId, String responsavelPapel) {
    }

    record CasoDto(UUID episodioId, String pacienteNome, int versao, UUID etapaId, UUID setorId, UUID motivoId,
                   String categoria, Instant bloqueioDesde, Instant entradaEm, Instant etapaDesde, boolean critico,
                   boolean transferencia, List<AlertaDto> alertas, List<PendenciaDto> pendencias) {
    }

    record TotaisDto(int casos, int criticos, int transferencias, int pendencias, int vencidas) {
    }

    record PassagemDto(UUID id, String status, Instant periodoInicio, UUID entreguePor, String entreguePorNome,
                       Instant entregueEm, int totalCasos, int totalCriticos, int totalTransferencias, int totalPendencias,
                       int totalVencidas, String observacao, UUID recebidaPor, String recebidaPorNome, Instant recebidaEm,
                       Map<String, Integer> diferencasRecebimento, UUID canceladaPor, String canceladaPorNome,
                       Instant canceladaEm, String justificativaCancelamento, int versao) {
        static PassagemDto de(RepositorioPlantao.Passagem p) {
            return p == null ? null : new PassagemDto(p.id(), p.status(), p.periodoInicio(), p.entreguePor(),
                    p.entreguePorNome(), p.entregueEm(), p.totalCasos(), p.totalCriticos(), p.totalTransferencias(),
                    p.totalPendencias(), p.totalVencidas(), p.observacao(), p.recebidaPor(), p.recebidaPorNome(),
                    p.recebidaEm(), p.diferencasRecebimento(), p.canceladaPor(), p.canceladaPorNome(), p.canceladaEm(),
                    p.justificativaCancelamento(), p.versao());
        }
    }

    /** {@code assinatura}: devolvida na entrega; corresponde exatamente aos {@code casos} exibidos. */
    record PreviaDto(Instant agora, String assinatura, Instant periodoInicio, PassagemDto pendente, TotaisDto totais,
                     List<CasoDto> casos) {
    }

    /** Diferenças desde a entrega: ids (exibidos com os casos/pendências correspondentes) e os casos novos. */
    record DiferencasDto(List<UUID> casosEncerrados, List<CasoDto> casosNovos, List<UUID> casosAlterados,
                         List<UUID> pendenciasEncerradas, List<PendenciaDto> pendenciasNovas, List<UUID> pendenciasAlteradas,
                         Map<String, Integer> contagens) {
    }

    record DetalheDto(Instant agora, PassagemDto passagem, boolean integra, TotaisDto totais, List<CasoDto> casos,
                      DiferencasDto diferencas, String assinaturaRecebimento) {
    }

    record EntregaRequest(@NotNull @Size(min = 64, max = 64) String assinatura, @Size(max = 500) String observacao) {
    }

    record RecebimentoRequest(@NotNull @Min(0) Integer versao, @NotNull @Size(min = 64, max = 64) String assinatura) {
    }

    record CancelamentoRequest(@NotNull @Min(0) Integer versao, @NotNull @Size(max = 500) String justificativa) {
    }

    record Criada(UUID id) {
    }

    private final ServicoPlantao servico;
    private final ProvedorContexto provedor;

    PlantaoController(ServicoPlantao servico, ProvedorContexto provedor) {
        this.servico = servico;
        this.provedor = provedor;
    }

    @GetMapping("/previa")
    PreviaDto previa(HttpServletRequest req) {
        ServicoPlantao.Previa p = servico.previa(usuario(), provedor.origem(req));
        return new PreviaDto(p.agora(), p.assinatura(), p.periodoInicio(), PassagemDto.de(p.pendente()), totais(p.conteudo()),
                casos(p.conteudo().casos(), p.nomes()));
    }

    @PostMapping("/passagens")
    ResponseEntity<Criada> entregar(@Valid @RequestBody EntregaRequest corpo, HttpServletRequest req) {
        UUID id = servico.entregar(usuario(), provedor.origem(req), corpo.assinatura(), corpo.observacao());
        return ResponseEntity.created(URI.create("/api/plantao/passagens/" + id)).body(new Criada(id));
    }

    @GetMapping("/passagens")
    List<PassagemDto> historico(HttpServletRequest req) {
        return servico.historico(usuario(), provedor.origem(req)).stream().map(PassagemDto::de).toList();
    }

    @GetMapping("/passagens/{id}")
    DetalheDto detalhe(@PathVariable UUID id, HttpServletRequest req) {
        ServicoPlantao.Detalhe d = servico.obter(usuario(), provedor.origem(req), id);
        DiferencasDto dif = null;
        if (d.diferencas() != null) {
            Diferencas x = d.diferencas();
            Set<UUID> novos = Set.copyOf(x.casosNovos());
            Set<UUID> pendNovas = Set.copyOf(x.pendenciasNovas());
            List<CasoDto> casosNovos = casos(d.atual().casos().stream().filter(c -> novos.contains(c.episodioId())).toList(),
                    d.nomes());
            List<PendenciaDto> pendenciasNovas = d.atual().casos().stream()
                    .flatMap(c -> c.pendencias().stream()).filter(p -> pendNovas.contains(p.id()))
                    .map(p -> pendencia(p, d.nomes())).toList();
            dif = new DiferencasDto(x.casosEncerrados(), casosNovos, x.casosAlterados(), x.pendenciasEncerradas(),
                    pendenciasNovas, x.pendenciasAlteradas(), x.contagens());
        }
        return new DetalheDto(d.agora(), PassagemDto.de(d.passagem()), d.integra(), totais(d.conteudo()),
                casos(d.conteudo().casos(), d.nomes()), dif, d.assinaturaRecebimento());
    }

    @PostMapping("/passagens/{id}/recebimento")
    ResponseEntity<Void> receber(@PathVariable UUID id, @Valid @RequestBody RecebimentoRequest corpo, HttpServletRequest req) {
        servico.receber(usuario(), provedor.origem(req), id, corpo.versao(), corpo.assinatura());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/passagens/{id}/cancelamento")
    ResponseEntity<Void> cancelar(@PathVariable UUID id, @Valid @RequestBody CancelamentoRequest corpo,
                                  HttpServletRequest req) {
        servico.cancelar(usuario(), provedor.origem(req), id, corpo.versao(), corpo.justificativa());
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------------------

    private UsuarioAutenticado usuario() {
        return provedor.usuario().orElseThrow(() -> new AcessoNegadoException(null));
    }

    private static TotaisDto totais(ConteudoPassagem c) {
        return new TotaisDto(c.totalCasos(), c.totalCriticos(), c.totalTransferencias(), c.totalPendencias(),
                c.totalVencidas());
    }

    private static List<CasoDto> casos(List<ConteudoPassagem.CasoPassagem> casos, RepositorioPlantao.Nomes nomes) {
        return casos.stream().map(c -> new CasoDto(c.episodioId(), nomes.pacientes().get(c.episodioId()), c.versao(),
                c.etapaId(), c.setorId(), c.motivoId(), c.categoria() == null ? null : c.categoria().name(), c.bloqueioDesde(),
                c.entradaEm(), c.etapaDesde(), c.critico(), c.transferencia(),
                c.alertas().stream().map(a -> new AlertaDto(a.regraId(), a.regraVersao(), a.tipo().name(), a.referenciaEm(),
                        a.atingidoEm(), a.pendenciaId())).toList(),
                c.pendencias().stream().map(p -> pendencia(p, nomes)).collect(Collectors.toList()))).toList();
    }

    private static PendenciaDto pendencia(ConteudoPassagem.PendenciaPassagem p, RepositorioPlantao.Nomes nomes) {
        return new PendenciaDto(p.id(), nomes.pendencias().get(p.id()), p.versao(), p.categoria().name(),
                p.criticidade().name(), p.prazo(), p.vencida(), p.responsavelUsuarioId(), p.responsavelSetorId(),
                p.responsavelPapel());
    }
}
