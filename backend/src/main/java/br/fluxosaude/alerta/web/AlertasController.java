package br.fluxosaude.alerta.web;

import br.fluxosaude.alerta.aplicacao.RepositorioAlertas;
import br.fluxosaude.alerta.aplicacao.ServicoAlertas;
import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.web.ProvedorContexto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertas operacionais e "Pacientes travados" (M04, RF-018, RF-022, RF-027; ADR-0007).
 * <pre>
 * GET  /api/travados                              casos que violam regras ativas (tempo, motivo, responsável, ação)
 * POST /api/episodios/{id}/alertas/ciencia        "ciente" de uma ocorrência em alerta, na versão da regra vista
 *                                                 (409 se a regra mudou; não encerra pendência)
 * GET  /api/config/regras-alerta                  regras da unidade ativa
 * POST /api/config/regras-alerta                  cria regra (Administrador)
 * PUT  /api/config/regras-alerta/{id}             altera/desativa regra (Administrador; versão obrigatória)
 * </pre>
 * Alerta operacional NÃO é classificação de risco nem prioridade clínica (RN-007, RN-013).
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api")
class AlertasController {

    /** Limite em minutos (1 min a 30 dias), como a regra e o banco. */
    static final int MINUTOS_MAX = 30 * 24 * 60;

    record CienciaDto(String autorNome, Instant registradaEm) {
    }

    /** {@code regraVersao}: versão da regra exibida — deve voltar no pedido de ciência. */
    record AlertaDto(UUID regraId, int regraVersao, String regraNome, TipoRegraAlerta tipo, Instant referenciaEm,
                     Instant atingidoEm, UUID pendenciaId, Long limiteMinutos, String acaoEsperada, CienciaDto ciencia) {
        static AlertaDto de(ServicoAlertas.AlertaVisto v) {
            var a = v.alerta();
            return new AlertaDto(a.regraId(), a.regraVersao(), a.regraNome(), a.tipo(), a.referenciaEm(), a.atingidoEm(),
                    a.pendenciaId(),
                    a.limite() == null ? null : a.limite().toMinutes(), a.acaoEsperada(),
                    v.ciencia() == null ? null : new CienciaDto(v.ciencia().autorNome(), v.ciencia().registradaEm()));
        }
    }

    record CasoDto(UUID episodioId, String pacienteNome, String setorNome, String etapaNome, Instant entradaEm,
                   Instant etapaDesde, Instant bloqueioDesde, String motivoBloqueio, String categoriaBloqueio,
                   Instant ultimoRegistroEm, List<RepositorioAlertas.PendenciaResumo> pendencias,
                   List<AlertaDto> alertas) {
        static CasoDto de(ServicoAlertas.CasoTravado c) {
            var e = c.episodio();
            var s = e.situacao();
            return new CasoDto(s.episodioId(), e.pacienteNome(), e.setorNome(), e.etapaNome(), s.entradaEm(),
                    s.etapaDesde(), s.bloqueioDesde(), e.motivoDescricao(), e.categoriaBloqueio(), s.ultimoRegistroEm(),
                    e.pendencias(), c.alertas().stream().map(AlertaDto::de).toList());
        }
    }

    /** {@code agora}: relógio do servidor, base de todos os tempos exibidos. */
    record TravadosResponse(Instant agora, List<CasoDto> itens, boolean truncado) {
    }

    /** {@code regraVersao}: a versão da regra que o profissional viu (obrigatória; 409 se mudou). */
    record CienciaRequest(@NotNull UUID regraId, @NotNull @Min(0) Integer regraVersao, @NotNull Instant referenciaEm,
                          UUID pendenciaId) {
    }

    record CienciaResponse(boolean registrada) {
    }

    record RegraDto(UUID id, String nome, TipoRegraAlerta tipo, UUID etapaId, CategoriaBloqueio categoria,
                    Long limiteMinutos, String acaoEsperada, boolean ativa, int versao) {
        static RegraDto de(RegraAlerta r) {
            return new RegraDto(r.id(), r.nome(), r.tipo(), r.etapaId(), r.categoria(),
                    r.limite() == null ? null : r.limite().toMinutes(), r.acaoEsperada(), r.ativa(), r.versao());
        }
    }

    record NovaRegraRequest(@NotNull @Size(max = 120) String nome, @NotNull TipoRegraAlerta tipo, UUID etapaId,
                            CategoriaBloqueio categoria, @Min(1) @Max(MINUTOS_MAX) Integer limiteMinutos,
                            @Size(max = 200) String acaoEsperada) {
    }

    record AlteraRegraRequest(@NotNull @Min(0) Integer versao, @NotNull @Size(max = 120) String nome, UUID etapaId,
                              CategoriaBloqueio categoria, @Min(1) @Max(MINUTOS_MAX) Integer limiteMinutos,
                              @Size(max = 200) String acaoEsperada, @NotNull Boolean ativa) {
    }

    private final ServicoAlertas servico;
    private final ProvedorContexto provedor;

    AlertasController(ServicoAlertas servico, ProvedorContexto provedor) {
        this.servico = servico;
        this.provedor = provedor;
    }

    @GetMapping("/travados")
    TravadosResponse travados(HttpServletRequest req) {
        var t = servico.travados(usuario(), provedor.origem(req));
        return new TravadosResponse(t.agora(), t.itens().stream().map(CasoDto::de).toList(), t.truncado());
    }

    @PostMapping("/episodios/{id}/alertas/ciencia")
    ResponseEntity<CienciaResponse> ciencia(@PathVariable UUID id, @Valid @RequestBody CienciaRequest c,
                                            HttpServletRequest req) {
        boolean nova = servico.registrarCiencia(usuario(), provedor.origem(req),
                new ServicoAlertas.PedidoCiencia(id, c.regraId(), c.regraVersao(), c.referenciaEm(), c.pendenciaId()));
        return ResponseEntity.status(nova ? HttpStatus.CREATED : HttpStatus.OK).body(new CienciaResponse(nova));
    }

    @GetMapping("/config/regras-alerta")
    List<RegraDto> regras(HttpServletRequest req) {
        return servico.regras(usuario(), provedor.origem(req)).stream().map(RegraDto::de).toList();
    }

    @PostMapping("/config/regras-alerta")
    ResponseEntity<RegraDto> criar(@Valid @RequestBody NovaRegraRequest c, HttpServletRequest req) {
        RegraAlerta r = servico.criarRegra(usuario(), provedor.origem(req), new ServicoAlertas.NovaRegra(c.nome(), c.tipo(),
                c.etapaId(), c.categoria(), minutos(c.limiteMinutos()), c.acaoEsperada()));
        return ResponseEntity.created(URI.create("/api/config/regras-alerta/" + r.id())).body(RegraDto.de(r));
    }

    @PutMapping("/config/regras-alerta/{id}")
    RegraDto alterar(@PathVariable UUID id, @Valid @RequestBody AlteraRegraRequest c, HttpServletRequest req) {
        return RegraDto.de(servico.alterarRegra(usuario(), provedor.origem(req), id, c.versao(),
                new ServicoAlertas.AlteracaoRegra(c.nome(), c.etapaId(), c.categoria(), minutos(c.limiteMinutos()),
                        c.acaoEsperada(), c.ativa())));
    }

    // ----------------------------------------------------------------------------

    private UsuarioAutenticado usuario() {
        return provedor.usuario().orElseThrow(() -> new AcessoNegadoException(null));
    }

    private static Duration minutos(Integer m) {
        return m == null ? null : Duration.ofMinutes(m);
    }
}
