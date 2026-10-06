package br.fluxosaude.identidade.web;

import br.fluxosaude.identidade.aplicacao.RepositorioUsuarios;
import br.fluxosaude.identidade.aplicacao.ServicoGestaoUsuarios;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.web.ProvedorContexto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administração de usuários e lotações da UNIDADE ATIVA (M08, ADR-0006). Exige
 * {@code USUARIO_GERENCIAR}; o banco repete as regras de alcance (V11).
 * <pre>
 * GET  /api/admin/usuarios                          usuários lotados na unidade ativa
 * GET  /api/admin/usuarios/{id}                     um usuário (404 se não estiver lotado na unidade)
 * GET  /api/admin/usuarios/busca?login=             conta existente (para vincular à unidade)
 * POST /api/admin/usuarios                          cria conta + papéis; devolve a senha provisória
 * PUT  /api/admin/usuarios/{id}/papeis              papéis NESTA unidade ([] = revoga o acesso nela)
 * PUT  /api/admin/usuarios/{id}/conta               nome, e-mail, registro (exige alcance sobre a conta)
 * PUT  /api/admin/usuarios/{id}/situacao            ativa/desativa a conta (exige alcance sobre a conta)
 * POST /api/admin/usuarios/{id}/senha-provisoria    nova senha provisória (exige alcance sobre a conta)
 * </pre>
 * Toda alteração exige a {@code versao} lida (409 se mudou). Senha provisória: só nesta
 * resposta, com {@code Cache-Control: no-store}; nunca em log ou auditoria.
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api/admin/usuarios")
class AdminUsuariosController {

    private static final int MAX_PAPEIS = 7; // quantidade de papéis (Papel)

    record UsuarioDto(UUID id, String login, String nome, String email, String registroProfissional, boolean ativo,
                      boolean deveTrocarSenha, int versao, List<Papel> papeis, boolean possuiOutrasUnidades,
                      boolean contaGerenciavel) {
        static UsuarioDto de(RepositorioUsuarios.Usuario u) {
            return new UsuarioDto(u.id(), u.login(), u.nome(), u.email(), u.registroProfissional(), u.ativo(),
                    u.deveTrocarSenha(), u.versao(), u.papeis().stream().sorted().toList(), u.possuiOutrasUnidades(),
                    u.contaGerenciavel());
        }
    }

    record ListaResponse(List<UsuarioDto> itens, boolean truncado) {
    }

    /** Mínimo para vincular conta existente (sem nome, e-mail ou registro de outra unidade). */
    record LocalizadoDto(UUID id, int versao, boolean lotadoNaUnidade, boolean vinculavel) {
    }

    record CriarRequest(@NotNull @Size(max = 64) String login, @NotNull @Size(max = 200) String nome,
                        @Size(max = 254) String email, @Size(max = 40) String registroProfissional,
                        @NotNull @Size(min = 1, max = MAX_PAPEIS) List<Papel> papeis) {
    }

    record PapeisRequest(@NotNull @Min(0) Integer versao, @NotNull @Size(max = MAX_PAPEIS) List<Papel> papeis) {
    }

    record ContaRequest(@NotNull @Min(0) Integer versao, @NotNull @Size(max = 200) String nome,
                        @Size(max = 254) String email, @Size(max = 40) String registroProfissional) {
    }

    record SituacaoRequest(@NotNull @Min(0) Integer versao, @NotNull Boolean ativo) {
    }

    record VersaoRequest(@NotNull @Min(0) Integer versao) {
    }

    record ResultadoDto(UUID id, int versao) {
    }

    record ProvisionadoDto(UUID id, int versao, String senhaProvisoria) {
        @Override
        public String toString() {
            return "ProvisionadoDto[id=" + id + "]";
        }
    }

    private final ServicoGestaoUsuarios servico;
    private final ProvedorContexto provedor;

    AdminUsuariosController(ServicoGestaoUsuarios servico, ProvedorContexto provedor) {
        this.servico = servico;
        this.provedor = provedor;
    }

    @GetMapping
    ListaResponse listar(HttpServletRequest req) {
        var lista = servico.listar(adm(), provedor.origem(req));
        return new ListaResponse(lista.itens().stream().map(UsuarioDto::de).toList(), lista.truncado());
    }

    @GetMapping("/{id}")
    UsuarioDto obter(@PathVariable UUID id, HttpServletRequest req) {
        return UsuarioDto.de(servico.obter(adm(), provedor.origem(req), id));
    }

    @GetMapping("/busca")
    LocalizadoDto localizar(@RequestParam @Size(max = 64) String login, HttpServletRequest req) {
        var l = servico.localizarPorLogin(adm(), provedor.origem(req), login);
        return new LocalizadoDto(l.id(), l.versao(), l.lotadoNaUnidade(), l.vinculavel());
    }

    @PostMapping
    ResponseEntity<ProvisionadoDto> criar(@Valid @RequestBody CriarRequest c, HttpServletRequest req) {
        var p = servico.criar(adm(), provedor.origem(req), new ServicoGestaoUsuarios.NovoUsuario(c.login(), c.nome(),
                c.email(), c.registroProfissional(), papeis(c.papeis())));
        return ResponseEntity.created(URI.create("/api/admin/usuarios/" + p.id()))
                .cacheControl(CacheControl.noStore())
                .body(new ProvisionadoDto(p.id(), p.versao(), p.senhaProvisoria()));
    }

    @PutMapping("/{id}/papeis")
    ResultadoDto papeis(@PathVariable UUID id, @Valid @RequestBody PapeisRequest c, HttpServletRequest req) {
        var r = servico.definirPapeis(adm(), provedor.origem(req), id, c.versao(), papeis(c.papeis()));
        return new ResultadoDto(r.id(), r.versao());
    }

    @PutMapping("/{id}/conta")
    ResultadoDto conta(@PathVariable UUID id, @Valid @RequestBody ContaRequest c, HttpServletRequest req) {
        var r = servico.alterarConta(adm(), provedor.origem(req), id, c.versao(),
                new ServicoGestaoUsuarios.DadosConta(c.nome(), c.email(), c.registroProfissional()));
        return new ResultadoDto(r.id(), r.versao());
    }

    @PutMapping("/{id}/situacao")
    ResultadoDto situacao(@PathVariable UUID id, @Valid @RequestBody SituacaoRequest c, HttpServletRequest req) {
        var r = servico.definirSituacao(adm(), provedor.origem(req), id, c.versao(), c.ativo());
        return new ResultadoDto(r.id(), r.versao());
    }

    @PostMapping("/{id}/senha-provisoria")
    ResponseEntity<ProvisionadoDto> senha(@PathVariable UUID id, @Valid @RequestBody VersaoRequest c,
                                          HttpServletRequest req) {
        var p = servico.redefinirSenha(adm(), provedor.origem(req), id, c.versao());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ProvisionadoDto(p.id(), p.versao(), p.senhaProvisoria()));
    }

    // ----------------------------------------------------------------------------

    private UsuarioAutenticado adm() {
        return provedor.usuario().orElseThrow(() -> new AcessoNegadoException(null));
    }

    /** Lista do JSON → conjunto (nulos viram erro de regra no serviço). */
    private static Set<Papel> papeis(List<Papel> lista) {
        return new HashSet<>(lista);
    }
}
