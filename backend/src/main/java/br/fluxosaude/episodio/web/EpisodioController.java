package br.fluxosaude.episodio.web;

import br.fluxosaude.alerta.aplicacao.ServicoAlertas;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.episodio.aplicacao.Consultas;
import br.fluxosaude.episodio.aplicacao.ServicoConsultas;
import br.fluxosaude.episodio.aplicacao.ServicoEpisodios;
import br.fluxosaude.episodio.aplicacao.ServicoPendencias;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.Episodio;
import br.fluxosaude.episodio.dominio.MomentoInformado;
import br.fluxosaude.episodio.dominio.NovoPaciente;
import br.fluxosaude.episodio.dominio.Pendencia;
import br.fluxosaude.episodio.dominio.ProtocoloExterno;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.web.ProvedorContexto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API da Torre de Controle: episódios, etapas, pendências e linha do tempo (RF-002 a RF-036).
 * <pre>
 * GET   /api/episodios                       Torre (filtros e ordenação — RF-010/RF-012)
 * POST  /api/episodios                       abre episódio (RF-002/RF-003)
 * GET   /api/episodios/{id}                  caso completo + linha do tempo (auditado)
 * PUT   /api/episodios/{id}/etapa            muda etapa / encerra com desfecho
 * PUT   /api/episodios/{id}/motivo           motivo do bloqueio
 * PUT   /api/episodios/{id}/protocolo        protocolo externo (regulação)
 * PUT   /api/episodios/{id}/destino          especialidade/serviço requerido
 * PUT   /api/episodios/{id}/setor            transferência interna de setor
 * POST  /api/episodios/{id}/observacoes      observação operacional (RF-028)
 * POST  /api/episodios/{id}/pendencias       cria pendência
 * PATCH /api/pendencias/{id}                 reatribui e/ou altera prazo
 * POST  /api/pendencias/{id}/resolucao       resolve (resolução obrigatória)
 * POST  /api/pendencias/{id}/cancelamento    cancela (justificativa obrigatória)
 * GET   /api/painel                          painel coletivo pseudonimizado (RNF-015)
 * </pre>
 * Toda alteração exige a {@code versao} lida (controle otimista, RF-036): versão divergente
 * responde 409 e o cliente deve recarregar. A resposta devolve a nova versão.
 * Autorização: sempre no serviço (permissão na unidade ativa) e no banco (RLS por unidade).
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api")
class EpisodioController {

    private final ServicoEpisodios episodios;
    private final ServicoPendencias pendencias;
    private final ServicoConsultas consultas;
    private final ServicoAlertas alertas;
    private final ProvedorContexto provedor;
    private final Clock relogio;

    EpisodioController(ServicoEpisodios episodios, ServicoPendencias pendencias, ServicoConsultas consultas,
                       ServicoAlertas alertas, ProvedorContexto provedor, Clock relogio) {
        this.episodios = episodios;
        this.pendencias = pendencias;
        this.consultas = consultas;
        this.alertas = alertas;
        this.provedor = provedor;
        this.relogio = relogio;
    }

    // --------------------------------------------------------------------- leitura

    @GetMapping("/episodios")
    EpisodioDtos.TorreResponse torre(@RequestParam(required = false) UUID setor,
                                     @RequestParam(required = false) UUID etapa,
                                     @RequestParam(required = false) UUID motivo,
                                     @RequestParam(required = false) CategoriaBloqueio categoria,
                                     @RequestParam(required = false) UUID especialidade,
                                     @RequestParam(required = false) UUID responsavel,
                                     @RequestParam(required = false) @Min(0) @Max(100_000) Integer minutosNaEtapa,
                                     @RequestParam(required = false) Boolean somenteVencidas,
                                     @RequestParam(required = false) Consultas.Ordem ordem,
                                     @RequestParam(defaultValue = "true") boolean decrescente,
                                     @RequestParam(defaultValue = "500") @Min(1) @Max(ServicoConsultas.LIMITE_MAXIMO) int limite,
                                     HttpServletRequest req) {
        var filtro = new Consultas.FiltroTorre(setor, etapa, motivo, categoria == null ? null : categoria.name(),
                especialidade, responsavel, minutosNaEtapa, somenteVencidas, ordem, decrescente, limite);
        UsuarioAutenticado u = usuario();
        List<Consultas.LinhaTorre> itens = consultas.torre(u, origem(req), filtro);
        // RF-011 / CA-05: destaque dos casos que atingiram limites configurados (calculado no servidor).
        ServicoAlertas.AlertasDosEpisodios a = alertas.alertasDe(u, origem(req),
                itens.stream().map(Consultas.LinhaTorre::episodioId).toList());
        Map<UUID, List<EpisodioDtos.AlertaResumo>> destaque = new HashMap<>();
        for (Consultas.LinhaTorre l : itens) {
            List<ServicoAlertas.AlertaVisto> doCaso = a.porEpisodio().get(l.episodioId());
            if (doCaso != null) {
                destaque.put(l.episodioId(), doCaso.stream().map(EpisodioDtos.AlertaResumo::de).toList());
            }
        }
        return new EpisodioDtos.TorreResponse(a.agora(), itens, destaque);
    }

    @GetMapping("/episodios/{id}")
    EpisodioDtos.CasoResponse caso(@PathVariable UUID id, HttpServletRequest req) {
        return EpisodioDtos.CasoResponse.de(relogio.instant(), consultas.caso(usuario(), origem(req), id));
    }

    @GetMapping("/painel")
    EpisodioDtos.PainelResponse painel(HttpServletRequest req) {
        UsuarioAutenticado u = usuario();
        var linhas = consultas.painel(u, origem(req), ids -> alertas.episodiosEmAlerta(u, origem(req), ids));
        return new EpisodioDtos.PainelResponse(relogio.instant(), linhas);
    }

    // --------------------------------------------------------------------- episódio

    @PostMapping("/episodios")
    ResponseEntity<EpisodioDtos.Resultado> abrir(@Valid @RequestBody EpisodioDtos.AbrirRequest corpo,
                                                 HttpServletRequest req) {
        NovoPaciente novo = corpo.novoPaciente() == null ? null : new NovoPaciente(corpo.novoPaciente().nome(),
                corpo.novoPaciente().dataNascimento(), corpo.novoPaciente().cns(),
                corpo.novoPaciente().identificadorInstitucional());
        var r = episodios.abrir(usuario(), origem(req), new ServicoEpisodios.AbrirEpisodio(corpo.pacienteId(), novo,
                corpo.setorId(), momento(corpo.momento()), corpo.justificativaDuplicidade()));
        return criado("/api/episodios/" + r.id(), r);
    }

    @PutMapping("/episodios/{id}/etapa")
    EpisodioDtos.Resultado mudarEtapa(@PathVariable UUID id, @Valid @RequestBody EpisodioDtos.MudarEtapaRequest c,
                                      HttpServletRequest req) {
        MomentoInformado m = momento(c.momento());
        var cmd = new Episodio.ComandoMudancaEtapa(c.etapaId(), m != null ? m : MomentoInformado.agora(relogio),
                c.motivoId() == null ? null : new Episodio.MotivoInformado(c.motivoId(), c.motivoDetalhe()),
                protocolo(c.protocoloSistema(), c.protocoloNumero()), c.justificativa());
        return dto(episodios.mudarEtapa(usuario(), origem(req), id, c.versao(), cmd));
    }

    @PutMapping("/episodios/{id}/motivo")
    EpisodioDtos.Resultado motivo(@PathVariable UUID id, @Valid @RequestBody EpisodioDtos.MotivoRequest c,
                                  HttpServletRequest req) {
        var motivo = c.motivoId() == null ? null : new Episodio.MotivoInformado(c.motivoId(), c.detalhe());
        return dto(episodios.definirMotivo(usuario(), origem(req), id, c.versao(), motivo, momento(c.momento())));
    }

    @PutMapping("/episodios/{id}/protocolo")
    EpisodioDtos.Resultado protocolo(@PathVariable UUID id, @Valid @RequestBody EpisodioDtos.ProtocoloRequest c,
                                     HttpServletRequest req) {
        return dto(episodios.registrarProtocolo(usuario(), origem(req), id, c.versao(),
                new ProtocoloExterno(c.sistema(), c.numero()), momento(c.momento())));
    }

    @PutMapping("/episodios/{id}/destino")
    EpisodioDtos.Resultado destino(@PathVariable UUID id, @Valid @RequestBody EpisodioDtos.DestinoRequest c,
                                   HttpServletRequest req) {
        return dto(episodios.definirDestino(usuario(), origem(req), id, c.versao(), c.especialidadeId(),
                c.descricao(), momento(c.momento())));
    }

    @PutMapping("/episodios/{id}/setor")
    EpisodioDtos.Resultado setor(@PathVariable UUID id, @Valid @RequestBody EpisodioDtos.SetorRequest c,
                                 HttpServletRequest req) {
        return dto(episodios.transferirSetor(usuario(), origem(req), id, c.versao(), c.setorId(), momento(c.momento())));
    }

    @PostMapping("/episodios/{id}/observacoes")
    ResponseEntity<EpisodioDtos.Resultado> observacao(@PathVariable UUID id,
                                                      @Valid @RequestBody EpisodioDtos.ObservacaoRequest c,
                                                      HttpServletRequest req) {
        var r = episodios.registrarObservacao(usuario(), origem(req), id, c.texto());
        return criado("/api/episodios/" + id, r);
    }

    // --------------------------------------------------------------------- pendências

    @PostMapping("/episodios/{id}/pendencias")
    ResponseEntity<EpisodioDtos.Resultado> criarPendencia(@PathVariable UUID id,
                                                          @Valid @RequestBody EpisodioDtos.CriarPendenciaRequest c,
                                                          HttpServletRequest req) {
        var r = pendencias.criar(usuario(), origem(req), id, new Pendencia.ComandoCriacao(c.categoria(),
                c.descricao(), c.responsavel().paraDominio(), c.prazo(), c.criticidade()));
        return criado("/api/episodios/" + id, r);
    }

    @PatchMapping("/pendencias/{id}")
    EpisodioDtos.Resultado atualizarPendencia(@PathVariable UUID id,
                                              @Valid @RequestBody EpisodioDtos.AtualizarPendenciaRequest c,
                                              HttpServletRequest req) {
        var novoResponsavel = c.responsavel() == null ? null : c.responsavel().paraDominio();
        return dto(pendencias.atualizar(usuario(), origem(req), id, c.versao(),
                new ServicoPendencias.Atualizacao(novoResponsavel, c.prazo())));
    }

    @PostMapping("/pendencias/{id}/resolucao")
    EpisodioDtos.Resultado resolver(@PathVariable UUID id, @Valid @RequestBody EpisodioDtos.EncerrarPendenciaRequest c,
                                    HttpServletRequest req) {
        return dto(pendencias.resolver(usuario(), origem(req), id, c.versao(), c.texto()));
    }

    @PostMapping("/pendencias/{id}/cancelamento")
    EpisodioDtos.Resultado cancelar(@PathVariable UUID id, @Valid @RequestBody EpisodioDtos.EncerrarPendenciaRequest c,
                                    HttpServletRequest req) {
        return dto(pendencias.cancelar(usuario(), origem(req), id, c.versao(), c.texto()));
    }

    // ----------------------------------------------------------------------------

    private UsuarioAutenticado usuario() {
        return provedor.usuario().orElseThrow(() -> new AcessoNegadoException(null));
    }

    private ContextoOrigem origem(HttpServletRequest req) {
        return provedor.origem(req);
    }

    private static MomentoInformado momento(EpisodioDtos.Momento m) {
        return m == null ? null : m.paraDominio();
    }

    /** Protocolo é opcional na mudança de etapa, mas sistema e número andam juntos. */
    private static ProtocoloExterno protocolo(String sistema, String numero) {
        if (sistema == null && numero == null) {
            return null;
        }
        RegraVioladaException.exigir(sistema != null && numero != null, "PROTOCOLO_INVALIDO",
                "Informe o sistema e o número do protocolo");
        return new ProtocoloExterno(sistema, numero);
    }

    private static EpisodioDtos.Resultado dto(ServicoEpisodios.Resultado r) {
        return new EpisodioDtos.Resultado(r.id(), r.versao());
    }

    private static ResponseEntity<EpisodioDtos.Resultado> criado(String local, ServicoEpisodios.Resultado r) {
        return ResponseEntity.created(URI.create(local)).body(dto(r));
    }
}
