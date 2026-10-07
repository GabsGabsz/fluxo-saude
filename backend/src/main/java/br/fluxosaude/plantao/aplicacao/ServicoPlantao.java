package br.fluxosaude.plantao.aplicacao;

import br.fluxosaude.compartilhado.ConflitoDeEstadoException;
import br.fluxosaude.compartilhado.RecursoNaoEncontradoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.compartilhado.Textos;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.plantao.dominio.Assinatura;
import br.fluxosaude.plantao.dominio.CasoAtual;
import br.fluxosaude.plantao.dominio.ComposicaoPassagem;
import br.fluxosaude.plantao.dominio.ConteudoPassagem;
import br.fluxosaude.plantao.dominio.Diferencas;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Passagem de plantão (M06: RF-016, RF-017, CA-07; ERS §10.4).
 *
 * <ol>
 *   <li><b>Prévia</b>: conteúdo gerado do estado atual (todos os episódios abertos) + assinatura.</li>
 *   <li><b>Entrega</b>: o servidor recompõe na mesma transação; só grava se a assinatura for igual à
 *       que o usuário viu (senão 409 PASSAGEM_DESATUALIZADA, nada gravado).</li>
 *   <li><b>Recebimento</b> por OUTRO profissional: vê o conteúdo entregue e as diferenças desde a
 *       entrega; confirma pela assinatura de ambos (senão 409 RECEBIMENTO_DESATUALIZADO).</li>
 * </ol>
 * A passagem não altera episódio, pendência, responsável nem ciência de alerta. Exige
 * PLANTAO_GERENCIAR e acesso nominal (EPISODIO_VER), pois o conteúdo identifica pacientes.
 */
public final class ServicoPlantao {

    /** Limite técnico: acima dele a passagem NÃO é gerada (nunca uma passagem parcial). */
    public static final int LIMITE_CASOS = 2000;
    public static final int LIMITE_HISTORICO = 30;

    public record Previa(Instant agora, ConteudoPassagem conteudo, String assinatura, Instant periodoInicio,
                         RepositorioPlantao.Passagem pendente, RepositorioPlantao.Nomes nomes) {
    }

    /**
     * @param integra       o conteúdo gravado confere com a assinatura gravada
     * @param diferencas    só para passagem ENTREGUE (aguardando recebimento): mudanças desde a entrega
     * @param assinaturaRecebimento o que o recebedor deve devolver para confirmar
     */
    public record Detalhe(Instant agora, RepositorioPlantao.Passagem passagem, ConteudoPassagem conteudo, boolean integra,
                          Diferencas diferencas, String assinaturaRecebimento, ConteudoPassagem atual,
                          RepositorioPlantao.Nomes nomes) {
    }

    private final TransacaoPlantao transacao;
    private final Clock relogio;
    private final Supplier<UUID> ids;

    public ServicoPlantao(TransacaoPlantao transacao, Clock relogio, Supplier<UUID> ids) {
        this.transacao = Objects.requireNonNull(transacao);
        this.relogio = Objects.requireNonNull(relogio);
        this.ids = Objects.requireNonNull(ids);
    }

    public Previa previa(UsuarioAutenticado u, ContextoOrigem origem) {
        exigirPermissao(u);
        return transacao.executar(u, origem, r -> {
            Instant agora = relogio.instant();
            ConteudoPassagem conteudo = compor(r, agora);
            return new Previa(agora, conteudo, conteudo.assinatura(), r.ultimaRecebidaEm().orElse(null),
                    r.pendente().orElse(null), nomes(r, List.of(conteudo)));
        });
    }

    public UUID entregar(UsuarioAutenticado u, ContextoOrigem origem, String assinaturaVista, String observacao) {
        exigirPermissao(u);
        RegraVioladaException.exigir(Assinatura.valida(assinaturaVista), "ASSINATURA_INVALIDA",
                "Assinatura do conteúdo ausente ou inválida");
        String obs = Textos.opcional(observacao, "Observação da passagem", 3, 500);
        return transacao.executar(u, origem, r -> {
            if (r.pendente().isPresent()) {
                throw new ConflitoDeEstadoException("PASSAGEM_PENDENTE",
                        "Já existe uma passagem aguardando recebimento nesta unidade.");
            }
            ConteudoPassagem conteudo = compor(r, relogio.instant());
            if (!conteudo.assinatura().equals(assinaturaVista)) {
                throw new ConflitoDeEstadoException("PASSAGEM_DESATUALIZADA",
                        "O conteúdo da passagem mudou desde que você o viu. Nada foi entregue: "
                        + "recarregue, confira e entregue novamente.");
            }
            UUID id = ids.get();
            r.inserir(id, conteudo, obs);
            r.auditar("PASSAGEM_ENTREGUE", id, totais(conteudo));
            return id;
        });
    }

    public Detalhe obter(UsuarioAutenticado u, ContextoOrigem origem, UUID id) {
        exigirPermissao(u);
        return transacao.executar(u, origem, r -> {
            Instant agora = relogio.instant();
            RepositorioPlantao.Passagem p = r.passagem(id).orElseThrow(() -> new RecursoNaoEncontradoException("Passagem"));
            ConteudoPassagem entregue = r.conteudo(id).orElseThrow(() -> new RecursoNaoEncontradoException("Passagem"));
            boolean integra = entregue.assinatura().equals(p.assinatura());
            if (!"ENTREGUE".equals(p.status())) {
                return new Detalhe(agora, p, entregue, integra, null, null, null, nomes(r, List.of(entregue)));
            }
            ConteudoPassagem atual = compor(r, agora);
            Diferencas d = Diferencas.entre(entregue, atual);
            return new Detalhe(agora, p, entregue, integra, d, d.assinaturaRecebimento(p.assinatura(), atual.assinatura()), atual,
                    nomes(r, List.of(entregue, atual)));
        });
    }

    public void receber(UsuarioAutenticado u, ContextoOrigem origem, UUID id, int versaoLida, String assinaturaVista) {
        exigirPermissao(u);
        RegraVioladaException.exigir(Assinatura.valida(assinaturaVista), "ASSINATURA_INVALIDA",
                "Assinatura do recebimento ausente ou inválida");
        transacao.executar(u, origem, r -> {
            RepositorioPlantao.Passagem p = r.passagem(id).orElseThrow(() -> new RecursoNaoEncontradoException("Passagem"));
            if (!"ENTREGUE".equals(p.status())) {
                throw new ConflitoDeEstadoException("PASSAGEM_JA_ENCERRADA",
                        "Esta passagem já foi recebida ou cancelada. Recarregue.");
            }
            RegraVioladaException.exigir(!p.entreguePor().equals(u.usuarioId()), "RECEBEDOR_E_ENTREGADOR",
                    "Quem entregou a passagem não pode confirmar o recebimento (RF-017).");
            ConteudoPassagem entregue = r.conteudo(id).orElseThrow(() -> new RecursoNaoEncontradoException("Passagem"));
            RegraVioladaException.exigir(entregue.assinatura().equals(p.assinatura()), "PASSAGEM_INTEGRIDADE",
                    "O conteúdo gravado não confere com a assinatura da entrega.");
            ConteudoPassagem atual = compor(r, relogio.instant());
            Diferencas d = Diferencas.entre(entregue, atual);
            if (!d.assinaturaRecebimento(p.assinatura(), atual.assinatura()).equals(assinaturaVista)) {
                throw new ConflitoDeEstadoException("RECEBIMENTO_DESATUALIZADO",
                        "A situação mudou desde que você abriu a passagem. Nada foi confirmado: "
                        + "recarregue, confira as diferenças e confirme novamente.");
            }
            r.receber(id, versaoLida, assinaturaVista, d.contagens());
            Map<String, Object> dados = new LinkedHashMap<>(d.contagens());
            r.auditar("PASSAGEM_RECEBIDA", id, dados);
            return null;
        });
    }

    public void cancelar(UsuarioAutenticado u, ContextoOrigem origem, UUID id, int versaoLida, String justificativa) {
        exigirPermissao(u);
        String texto = Textos.obrigatorio(justificativa, "Justificativa do cancelamento", 3, 500);
        transacao.executar(u, origem, r -> {
            RepositorioPlantao.Passagem p = r.passagem(id).orElseThrow(() -> new RecursoNaoEncontradoException("Passagem"));
            if (!"ENTREGUE".equals(p.status())) {
                throw new ConflitoDeEstadoException("PASSAGEM_JA_ENCERRADA",
                        "Esta passagem já foi recebida ou cancelada. Recarregue.");
            }
            RegraVioladaException.exigir(p.entreguePor().equals(u.usuarioId()), "SO_AUTOR_CANCELA",
                    "Só quem entregou pode cancelar a passagem.");
            r.cancelar(id, versaoLida, texto);
            r.auditar("PASSAGEM_CANCELADA", id, Map.of());
            return null;
        });
    }

    public List<RepositorioPlantao.Passagem> historico(UsuarioAutenticado u, ContextoOrigem origem) {
        exigirPermissao(u);
        return transacao.executar(u, origem, r -> r.historico(LIMITE_HISTORICO));
    }

    // ----------------------------------------------------------------------------

    private static void exigirPermissao(UsuarioAutenticado u) {
        AcessoNegadoException.exigir(u, Permissao.PLANTAO_GERENCIAR);
        AcessoNegadoException.exigir(u, Permissao.EPISODIO_VER);
    }

    private static ConteudoPassagem compor(RepositorioPlantao r, Instant agora) {
        List<CasoAtual> casos = r.casosAbertos(LIMITE_CASOS + 1);
        RegraVioladaException.exigir(casos.size() <= LIMITE_CASOS, "PASSAGEM_GRANDE_DEMAIS",
                "A unidade tem mais de " + LIMITE_CASOS + " episódios abertos: a passagem não pode ser gerada "
                + "por completo. Nenhuma passagem parcial é gerada.");
        return ComposicaoPassagem.compor(casos, r.regrasAtivas(), agora);
    }

    private static RepositorioPlantao.Nomes nomes(RepositorioPlantao r, List<ConteudoPassagem> conteudos) {
        Set<UUID> eps = new HashSet<>();
        Set<UUID> pends = new HashSet<>();
        for (ConteudoPassagem c : conteudos) {
            c.casos().forEach(caso -> {
                eps.add(caso.episodioId());
                caso.pendencias().forEach(p -> pends.add(p.id()));
            });
        }
        return r.nomes(eps, pends);
    }

    private static Map<String, Object> totais(ConteudoPassagem c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("casos", c.totalCasos());
        m.put("criticos", c.totalCriticos());
        m.put("transferencias", c.totalTransferencias());
        m.put("pendencias", c.totalPendencias());
        m.put("vencidas", c.totalVencidas());
        return m;
    }
}
