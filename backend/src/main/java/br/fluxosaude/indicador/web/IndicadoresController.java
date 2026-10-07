package br.fluxosaude.indicador.web;

import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.indicador.aplicacao.ServicoIndicadores;
import br.fluxosaude.indicador.dominio.DefinicaoIndicador;
import br.fluxosaude.infra.web.ProvedorContexto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Indicadores (M07: RF-019, RF-020, CA-09) e dicionário de cálculo (RF-039). Exige
 * INDICADORES_VER. Resposta SÓ agregada: sem nome, CNS ou identificador de paciente/episódio,
 * e sem filtros que levem a detalhe nominal (apto ao perfil Direção).
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api/indicadores")
class IndicadoresController {

    private final ServicoIndicadores servico;
    private final ProvedorContexto provedor;

    IndicadoresController(ServicoIndicadores servico, ProvedorContexto provedor) {
        this.servico = servico;
        this.provedor = provedor;
    }

    /** {@code inicio}/{@code fim}: datas LOCAIS da unidade (AAAA-MM-DD), inclusive. */
    @GetMapping
    ServicoIndicadores.Resultado consultar(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
                                           @RequestParam(required = false) UUID setor, HttpServletRequest req) {
        return servico.consultar(usuario(), provedor.origem(req), inicio, fim, setor);
    }

    @GetMapping("/dicionario")
    List<DefinicaoIndicador> dicionario() {
        return servico.dicionario(usuario());
    }

    private UsuarioAutenticado usuario() {
        return provedor.usuario().orElseThrow(() -> new AcessoNegadoException(null));
    }
}
