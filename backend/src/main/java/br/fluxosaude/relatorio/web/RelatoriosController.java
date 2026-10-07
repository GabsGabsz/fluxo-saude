package br.fluxosaude.relatorio.web;

import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.indicador.dominio.DefinicaoIndicador;
import br.fluxosaude.infra.web.ProvedorContexto;
import br.fluxosaude.relatorio.aplicacao.ServicoRelatorios;
import br.fluxosaude.relatorio.dominio.DicionarioRelatorios;
import br.fluxosaude.relatorio.dominio.TipoRelatorio;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios gerenciais (extensão aprovada, issue #9). Exige INDICADORES_VER; a lista nominal de
 * pendências exige também EPISODIO_VER (autorizado no servidor). A exportação NÃO recalcula: o
 * navegador gera CSV/impressão a partir do resultado já exibido e registra a exportação aqui, com o
 * comprovante emitido pelo servidor, ANTES de liberar o arquivo.
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api/relatorios")
class RelatoriosController {

    record TipoDto(String codigo, String titulo, boolean aceitaEtapa, boolean aceitaCategoria) {
    }

    record DicionarioDto(String versao, List<TipoDto> tipos, List<DefinicaoIndicador> definicoes) {
    }

    record ExportacaoRequest(@NotNull @Size(max = 2048) String comprovante, @NotNull ServicoRelatorios.Formato formato) {
    }

    private final ServicoRelatorios servico;
    private final ProvedorContexto provedor;

    RelatoriosController(ServicoRelatorios servico, ProvedorContexto provedor) {
        this.servico = servico;
        this.provedor = provedor;
    }

    /** {@code inicio}/{@code fim}: datas LOCAIS da unidade (AAAA-MM-DD), inclusive. */
    @GetMapping("/{tipo}")
    ServicoRelatorios.Resultado consultar(@PathVariable String tipo,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
                                          @RequestParam(required = false) UUID setor,
                                          @RequestParam(required = false) UUID etapa,
                                          @RequestParam(required = false) @Size(max = 40) String categoria,
                                          HttpServletRequest req) {
        return servico.consultar(usuario(), provedor.origem(req), tipoDe(tipo), inicio, fim, setor, etapa, categoria);
    }

    @GetMapping("/dicionario")
    DicionarioDto dicionario() {
        AcessoNegadoException.exigir(usuario(), Permissao.INDICADORES_VER);
        return new DicionarioDto(DicionarioRelatorios.VERSAO,
                Arrays.stream(TipoRelatorio.values())
                    .map(t -> new TipoDto(t.name(), t.titulo(), t.aceitaEtapa(), t.aceitaCategoria())).toList(),
                DicionarioRelatorios.definicoes());
    }

    @PostMapping("/exportacoes")
    ResponseEntity<ServicoRelatorios.Exportacao> exportar(@Valid @RequestBody ExportacaoRequest corpo, HttpServletRequest req) {
        ServicoRelatorios.Exportacao e = servico.registrarExportacao(usuario(), provedor.origem(req), corpo.comprovante(),
                corpo.formato());
        return ResponseEntity.created(java.net.URI.create("/api/relatorios/exportacoes/" + e.registro())).body(e);
    }

    private static TipoRelatorio tipoDe(String t) {
        try {
            return TipoRelatorio.valueOf(t.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RegraVioladaException("RELATORIO_INEXISTENTE", "Relatório inexistente");
        }
    }

    private UsuarioAutenticado usuario() {
        return provedor.usuario().orElseThrow(() -> new AcessoNegadoException(null));
    }
}
