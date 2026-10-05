package br.fluxosaude.episodio.aplicacao;

import static br.fluxosaude.compartilhado.RegraVioladaException.exigir;

import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.episodio.dominio.Episodio;
import br.fluxosaude.episodio.dominio.EventoEpisodio;
import br.fluxosaude.episodio.dominio.Pendencia;
import br.fluxosaude.episodio.dominio.Responsavel;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Casos de uso de pendências (RF-007, RF-013, RN-005). Permissão: {@code PENDENCIA_GERENCIAR}. */
public final class ServicoPendencias {

    /** Alteração de pendência aberta: só os campos informados mudam. */
    public record Atualizacao(Responsavel novoResponsavel, Instant novoPrazo) {
    }

    private final Transacao transacao;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public ServicoPendencias(Transacao transacao, Clock clock, Supplier<UUID> ids) {
        this.transacao = Objects.requireNonNull(transacao);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public ServicoEpisodios.Resultado criar(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId,
                                           Pendencia.ComandoCriacao cmd) {
        AcessoNegadoException.exigir(u, Permissao.PENDENCIA_GERENCIAR);
        return transacao.executar(u, origem, r -> {
            Episodio ep = r.episodio(episodioId).orElseThrow(() -> new RecursoNaoEncontradoException("Episódio"));
            validarResponsavel(r, u, cmd.responsavel());
            Pendencia p = Pendencia.criar(ep, cmd, u.usuarioId(), clock, ids);
            r.inserir(p);
            r.inserirEventos(p.retirarEventos());
            return new ServicoEpisodios.Resultado(p.id(), 0);
        });
    }

    public ServicoEpisodios.Resultado atualizar(UsuarioAutenticado u, ContextoOrigem origem, UUID pendenciaId,
                                               int versaoLida, Atualizacao a) {
        AcessoNegadoException.exigir(u, Permissao.PENDENCIA_GERENCIAR);
        exigir(a.novoResponsavel() != null || a.novoPrazo() != null, "NADA_A_ALTERAR",
                "Informe o novo responsável e/ou o novo prazo");
        return transacao.executar(u, origem, r -> {
            Pendencia p = carregar(r, pendenciaId, versaoLida);
            if (a.novoResponsavel() != null) {
                validarResponsavel(r, u, a.novoResponsavel());
                p.reatribuir(a.novoResponsavel(), u.usuarioId(), clock, ids);
            }
            if (a.novoPrazo() != null) {
                p.alterarPrazo(a.novoPrazo(), u.usuarioId(), clock, ids);
            }
            return salvar(r, p);
        });
    }

    /** RN-005: resolução obrigatória. */
    public ServicoEpisodios.Resultado resolver(UsuarioAutenticado u, ContextoOrigem origem, UUID pendenciaId,
                                              int versaoLida, String resolucao) {
        AcessoNegadoException.exigir(u, Permissao.PENDENCIA_GERENCIAR);
        return transacao.executar(u, origem, r -> {
            Pendencia p = carregar(r, pendenciaId, versaoLida);
            p.resolver(resolucao, u.usuarioId(), clock, ids);
            return salvar(r, p);
        });
    }

    /** RN-005: cancelamento com justificativa. */
    public ServicoEpisodios.Resultado cancelar(UsuarioAutenticado u, ContextoOrigem origem, UUID pendenciaId,
                                              int versaoLida, String justificativa) {
        AcessoNegadoException.exigir(u, Permissao.PENDENCIA_GERENCIAR);
        return transacao.executar(u, origem, r -> {
            Pendencia p = carregar(r, pendenciaId, versaoLida);
            p.cancelar(justificativa, u.usuarioId(), clock, ids);
            return salvar(r, p);
        });
    }

    private static ServicoEpisodios.Resultado salvar(Repositorios r, Pendencia p) {
        List<EventoEpisodio> eventos = p.retirarEventos();
        if (eventos.isEmpty()) {
            return new ServicoEpisodios.Resultado(p.id(), p.versao());
        }
        r.atualizar(p);
        r.inserirEventos(eventos);
        return new ServicoEpisodios.Resultado(p.id(), p.versao() + 1);
    }

    private static Pendencia carregar(Repositorios r, UUID pendenciaId, int versaoLida) {
        Pendencia p = r.pendencia(pendenciaId).orElseThrow(() -> new RecursoNaoEncontradoException("Pendência"));
        if (p.versao() != versaoLida) {
            throw new ConflitoDeVersaoException();
        }
        return p;
    }

    private static void validarResponsavel(Repositorios r, UsuarioAutenticado u, Responsavel resp) {
        if (resp instanceof Responsavel.Usuario usuario) {
            exigir(r.usuarioLotadoNaUnidade(usuario.usuarioId(), u.unidadeAtiva()), "RESPONSAVEL_INVALIDO",
                    "O usuário responsável não pertence à unidade");
        } else if (resp instanceof Responsavel.Setor setor) {
            exigir(r.setorExiste(setor.setorId()), "RESPONSAVEL_INVALIDO", "O setor responsável não pertence à unidade");
        }
    }
}
