package br.fluxosaude.episodio.aplicacao;

import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.episodio.dominio.Pseudonimo;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Leitura para as telas (RF-010, RF-012, RF-014, RF-038). Visão nominal exige
 * {@code EPISODIO_VER}; o painel coletivo só devolve pseudônimos ({@code PAINEL_COLETIVO_VER}).
 * Abrir um caso nominal é auditado (RNF-002, consulta sensível).
 */
public final class ServicoConsultas {

    public static final int LIMITE_MAXIMO = 500;

    /**
     * Linha do painel coletivo: sem nome, sem CNS (RNF-015). {@code emAlerta}: viola alguma regra
     * operacional da unidade (sem detalhar qual — o detalhe é nominal, em /api/travados).
     */
    public record LinhaPainelPseudonimizada(String identificacao, String setor, String etapa, String natureza,
                                            Instant entradaEm, Instant etapaDesde, String categoriaBloqueio,
                                            Instant bloqueioDesde, int pendenciasVencidas, boolean emAlerta) {
    }

    private final Transacao transacao;

    public ServicoConsultas(Transacao transacao) {
        this.transacao = Objects.requireNonNull(transacao);
    }

    public List<Consultas.LinhaTorre> torre(UsuarioAutenticado u, ContextoOrigem origem, Consultas.FiltroTorre filtro) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_VER);
        Consultas.FiltroTorre f = normalizar(filtro);
        return transacao.executar(u, origem, r -> r.consultas().torre(f));
    }

    public Consultas.Caso caso(UsuarioAutenticado u, ContextoOrigem origem, UUID episodioId) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_VER);
        return transacao.executar(u, origem, r -> {
            Consultas.Caso caso = r.consultas().caso(episodioId)
                    .orElseThrow(() -> new RecursoNaoEncontradoException("Episódio"));
            r.consultas().registrarConsultaDeCaso(episodioId, u.unidadeAtiva());
            return caso;
        });
    }

    public List<LinhaPainelPseudonimizada> painel(UsuarioAutenticado u, ContextoOrigem origem) {
        return painel(u, origem, ids -> java.util.Set.of());
    }

    /**
     * @param emAlerta dado o conjunto de episódios do painel, devolve os que violam regras ativas
     *                 (calculado pelo módulo de alertas exatamente sobre esses episódios)
     */
    public List<LinhaPainelPseudonimizada> painel(UsuarioAutenticado u, ContextoOrigem origem,
                                                  java.util.function.Function<java.util.Collection<UUID>,
                                                          java.util.Set<UUID>> emAlerta) {
        AcessoNegadoException.exigir(u, Permissao.PAINEL_COLETIVO_VER);
        Objects.requireNonNull(emAlerta);
        List<Consultas.LinhaPainel> linhas = transacao.executar(u, origem, r -> r.consultas().painel(LIMITE_MAXIMO));
        java.util.Set<UUID> alerta = emAlerta.apply(linhas.stream().map(Consultas.LinhaPainel::episodioId).toList());
        return linhas.stream()
                .map(l -> new LinhaPainelPseudonimizada(Pseudonimo.de(l.pacienteNome(), l.episodioId()), l.setorNome(),
                        l.etapaNome(), l.natureza(), l.entradaEm(), l.etapaDesde(), l.categoriaBloqueio(),
                        l.bloqueioDesde(), l.pendenciasVencidas(), alerta.contains(l.episodioId())))
                .toList();
    }

    /**
     * Catálogo da unidade ativa para a interface (setores, etapas e transições, motivos,
     * especialidades). Exige alguma permissão na unidade; a lista de profissionais (nomes) só
     * vai para quem atua nos casos ({@code EPISODIO_VER}) ou gere usuários.
     */
    public Consultas.Catalogo catalogo(UsuarioAutenticado u, ContextoOrigem origem) {
        if (u == null || u.permissoes().isEmpty() || u.deveTrocarSenha()) {
            throw new AcessoNegadoException(null);
        }
        boolean profissionais = u.pode(Permissao.EPISODIO_VER) || u.pode(Permissao.USUARIO_GERENCIAR);
        return transacao.executar(u, origem, r -> r.consultas().catalogo(profissionais));
    }

    /**
     * RF-003: localiza cadastro existente por CNS OU identificador institucional EXATO (para
     * abrir episódio sem duplicar cadastro). Cada cadastro devolvido é auditado (RNF-002).
     */
    public List<Consultas.PacienteEncontrado> pacientes(UsuarioAutenticado u, ContextoOrigem origem, String cns,
                                                        String identificador) {
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_ABRIR);
        String c = cns == null || cns.isBlank() ? null : cns.replaceAll("[\\s.-]", "");
        String i = identificador == null || identificador.isBlank() ? null : identificador.strip();
        RegraVioladaException.exigir((c == null) != (i == null), "BUSCA_INVALIDA",
                "Informe o CNS ou o identificador institucional (apenas um)");
        RegraVioladaException.exigir(c == null || br.fluxosaude.episodio.dominio.Cns.valido(c), "CNS_INVALIDO",
                "CNS inválido");
        RegraVioladaException.exigir(i == null || i.length() <= 40, "IDENTIFICADOR_INVALIDO",
                "Identificador institucional inválido");
        return transacao.executar(u, origem, r -> {
            List<Consultas.PacienteEncontrado> achados = r.consultas().pacientes(c, i);
            achados.forEach(p -> r.consultas().registrarConsultaDePaciente(p.id(), u.unidadeAtiva()));
            return achados;
        });
    }

    private static Consultas.FiltroTorre normalizar(Consultas.FiltroTorre f) {
        if (f == null) {
            return new Consultas.FiltroTorre(null, null, null, null, null, null, null, null,
                    Consultas.Ordem.TEMPO_NA_ETAPA, true, LIMITE_MAXIMO);
        }
        int limite = f.limite() <= 0 ? LIMITE_MAXIMO : Math.min(f.limite(), LIMITE_MAXIMO);
        Integer minutos = f.minutosMinimosNaEtapa() == null ? null : Math.max(0, f.minutosMinimosNaEtapa());
        return new Consultas.FiltroTorre(f.setorId(), f.etapaId(), f.motivoId(), f.categoriaBloqueio(),
                f.especialidadeId(), f.responsavelUsuarioId(), minutos, f.somenteComPendenciaVencida(),
                f.ordem() == null ? Consultas.Ordem.TEMPO_NA_ETAPA : f.ordem(), f.decrescente(), limite);
    }
}
