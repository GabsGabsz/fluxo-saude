package br.fluxosaude.plantao.infra;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.compartilhado.ConflitoDeEstadoException;
import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import br.fluxosaude.episodio.dominio.NaturezaEtapa;
import br.fluxosaude.plantao.aplicacao.RepositorioPlantao;
import br.fluxosaude.plantao.dominio.CasoAtual;
import br.fluxosaude.plantao.dominio.ConteudoPassagem;
import br.fluxosaude.plantao.dominio.PendenciaAtual;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passagem de plantão sobre o PostgreSQL (V16). SQL sempre parametrizado; RLS restringe à
 * unidade ativa e aos papéis com PLANTAO_GERENCIAR. O estado atual é lido numa ÚNICA consulta
 * (instantâneo coerente de episódios e pendências). O conteúdo é gravado e relido como jsonb sem
 * nenhum nome; nomes e descrições vêm do estado atual, só para exibição.
 */
final class RepositorioPlantaoJdbc implements RepositorioPlantao {

    private static final String SELECT_PASSAGEM = """
            SELECT p.id, p.status::text, p.periodo_inicio, p.entregue_por, ue.nome, p.entregue_em, p.assinatura,
                   p.total_casos, p.total_criticos, p.total_transferencias, p.total_pendencias, p.total_vencidas,
                   p.observacao, p.recebida_por, ur.nome, p.recebida_em,
                   p.diferencas_recebimento IS NOT NULL,
                   (p.diferencas_recebimento ->> 'casosEncerrados')::int, (p.diferencas_recebimento ->> 'casosNovos')::int,
                   (p.diferencas_recebimento ->> 'casosAlterados')::int,
                   (p.diferencas_recebimento ->> 'pendenciasEncerradas')::int,
                   (p.diferencas_recebimento ->> 'pendenciasNovas')::int,
                   (p.diferencas_recebimento ->> 'pendenciasAlteradas')::int,
                   p.cancelada_por, uc.nome, p.cancelada_em, p.justificativa_cancelamento, p.versao
              FROM fluxo.passagem_plantao p
              LEFT JOIN fluxo.usuario ue ON ue.id = p.entregue_por
              LEFT JOIN fluxo.usuario ur ON ur.id = p.recebida_por
              LEFT JOIN fluxo.usuario uc ON uc.id = p.cancelada_por
            """;

    private static final List<String> CHAVES_DIFERENCAS = List.of("casosEncerrados", "casosNovos", "casosAlterados",
            "pendenciasEncerradas", "pendenciasNovas", "pendenciasAlteradas");

    private final JdbcClient jdbc;

    RepositorioPlantaoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------- estado atual

    private record LinhaCaso(UUID id, int versao, UUID etapaId, String natureza, UUID setorId, UUID motivoId,
                             String categoria, Instant bloqueioDesde, Instant entrada, Instant etapaDesde,
                             Instant ultimoRegistro, boolean protocolo, boolean destino, PendenciaAtual pendencia) {
    }

    @Override
    public List<CasoAtual> casosAbertos(int limite) {
        List<LinhaCaso> linhas = jdbc.sql("""
                WITH eps AS (
                    SELECT e.id, e.versao, e.etapa_id, et.natureza::text AS natureza, e.setor_id, e.motivo_bloqueio_id,
                           m.categoria::text AS categoria, e.bloqueio_desde, e.entrada_em, e.etapa_desde,
                           (SELECT max(ev.registrado_em) FROM fluxo.evento_episodio ev WHERE ev.episodio_id = e.id) AS ultimo,
                           e.protocolo_numero IS NOT NULL AS protocolo,
                           (e.especialidade_requerida_id IS NOT NULL OR e.destino_descricao IS NOT NULL) AS destino
                      FROM fluxo.episodio e
                      JOIN fluxo.etapa et ON et.id = e.etapa_id
                      LEFT JOIN fluxo.motivo_bloqueio m ON m.id = e.motivo_bloqueio_id
                     WHERE e.encerrado_em IS NULL
                     ORDER BY e.entrada_em, e.id
                     LIMIT ?
                )
                SELECT eps.id, eps.versao, eps.etapa_id, eps.natureza, eps.setor_id, eps.motivo_bloqueio_id, eps.categoria,
                       eps.bloqueio_desde, eps.entrada_em, eps.etapa_desde, eps.ultimo, eps.protocolo, eps.destino,
                       pd.id, pd.versao, pd.categoria::text, pd.criticidade_operacional::text, pd.prazo,
                       pd.responsavel_usuario_id, pd.responsavel_setor_id, pd.responsavel_papel::text
                  FROM eps
                  LEFT JOIN fluxo.pendencia pd ON pd.episodio_id = eps.id AND pd.status = 'ABERTA'
                 ORDER BY eps.entrada_em, eps.id, pd.id
                """)
            .param(limite)
            .query((rs, n) -> {
                UUID pid = uuid(rs, 14);
                PendenciaAtual p = pid == null ? null : new PendenciaAtual(pid, rs.getInt(15),
                        CategoriaBloqueio.valueOf(rs.getString(16)), CriticidadeOperacional.valueOf(rs.getString(17)),
                        instante(rs, 18), uuid(rs, 19), uuid(rs, 20), rs.getString(21));
                return new LinhaCaso(uuid(rs, 1), rs.getInt(2), uuid(rs, 3), rs.getString(4), uuid(rs, 5), uuid(rs, 6),
                        rs.getString(7), instante(rs, 8), instante(rs, 9), instante(rs, 10), instante(rs, 11),
                        rs.getBoolean(12), rs.getBoolean(13), p);
            })
            .list();
        Map<UUID, List<LinhaCaso>> porEpisodio = new LinkedHashMap<>();
        linhas.forEach(l -> porEpisodio.computeIfAbsent(l.id(), k -> new ArrayList<>()).add(l));
        List<CasoAtual> casos = new ArrayList<>(porEpisodio.size());
        porEpisodio.forEach((id, ls) -> {
            LinhaCaso l = ls.get(0);
            casos.add(new CasoAtual(l.id(), l.versao(), l.etapaId(), NaturezaEtapa.valueOf(l.natureza()), l.setorId(),
                    l.motivoId(), l.categoria() == null ? null : CategoriaBloqueio.valueOf(l.categoria()), l.bloqueioDesde(),
                    l.entrada(), l.etapaDesde(), l.ultimoRegistro(), l.protocolo(), l.destino(),
                    ls.stream().map(LinhaCaso::pendencia).filter(p -> p != null).toList()));
        });
        return casos;
    }

    @Override
    public List<RegraAlerta> regrasAtivas() {
        return jdbc.sql("""
                SELECT id, nome, tipo::text, etapa_id, categoria::text, extract(epoch FROM limite)::bigint, acao_esperada,
                       ativa, versao
                  FROM fluxo.regra_alerta WHERE ativa ORDER BY nome, id
                """)
            .query((rs, n) -> {
                long seg = rs.getLong(6);
                Duration limite = rs.wasNull() ? null : Duration.ofSeconds(seg);
                String cat = rs.getString(5);
                return new RegraAlerta(uuid(rs, 1), rs.getString(2), TipoRegraAlerta.valueOf(rs.getString(3)), uuid(rs, 4),
                        cat == null ? null : CategoriaBloqueio.valueOf(cat), limite, rs.getString(7), rs.getBoolean(8),
                        rs.getInt(9));
            })
            .list();
    }

    // ------------------------------------------------------------------- passagens

    @Override
    public Optional<Passagem> pendente() {
        return jdbc.sql(SELECT_PASSAGEM + " WHERE p.status = 'ENTREGUE'").query(RepositorioPlantaoJdbc::passagem).optional();
    }

    /**
     * Mesma regra do gatilho {@code tg_passagem_entrega} (início do período = entrega da última
     * passagem RECEBIDA da unidade). Sem passagem recebida não há linha (vazio), em vez de um
     * agregado nulo: {@code JdbcClient.single()} recusa resultado nulo. {@code entregue_em} é NOT
     * NULL, então o mapeamento nunca devolve nulo. Índice: {@code passagem_historico_idx}.
     */
    @Override
    public Optional<Instant> ultimaRecebidaEm() {
        List<Instant> ultima = jdbc.sql("""
                SELECT entregue_em FROM fluxo.passagem_plantao
                 WHERE status = 'RECEBIDA'
                 ORDER BY entregue_em DESC
                 LIMIT 1
                """)
            .query((rs, n) -> instante(rs, 1))
            .list();
        return ultima.isEmpty() ? Optional.empty() : Optional.of(ultima.get(0));
    }

    @Override
    public void inserir(UUID id, ConteudoPassagem c, String observacao) {
        try {
            int linhas = jdbc.sql("""
                    INSERT INTO fluxo.passagem_plantao (id, unidade_id, assinatura, total_casos, total_criticos,
                                                        total_transferencias, total_pendencias, total_vencidas, observacao)
                    SELECT ?, u.id, ?, ?, ?, ?, ?, ?, CAST(? AS text) FROM unnest(fluxo.ctx_unidades()) AS u(id)
                    """)
                .param(id).param(c.assinatura()).param(c.totalCasos()).param(c.totalCriticos())
                .param(c.totalTransferencias()).param(c.totalPendencias()).param(c.totalVencidas()).param(observacao)
                .update();
            if (linhas != 1) {
                throw new IllegalStateException("passagem não inserida (contexto sem unidade ativa)");
            }
        } catch (DuplicateKeyException e) {
            throw new ConflitoDeEstadoException("PASSAGEM_PENDENTE",
                    "Já existe uma passagem aguardando recebimento nesta unidade.");
        }
        jdbc.sql("""
                INSERT INTO fluxo.passagem_conteudo (passagem_id, unidade_id, conteudo)
                SELECT p.id, p.unidade_id, CAST(? AS jsonb) FROM fluxo.passagem_plantao p WHERE p.id = ?
                """)
            .param(c.canonico()).param(id)
            .update();
    }

    @Override
    public Optional<Passagem> passagem(UUID id) {
        return jdbc.sql(SELECT_PASSAGEM + " WHERE p.id = ?").param(id).query(RepositorioPlantaoJdbc::passagem).optional();
    }

    @Override
    public List<Passagem> historico(int limite) {
        return jdbc.sql(SELECT_PASSAGEM + " ORDER BY p.entregue_em DESC, p.id LIMIT ?").param(limite)
            .query(RepositorioPlantaoJdbc::passagem).list();
    }

    /** Relê o conteúdo gravado (jsonb) para o modelo do domínio, campo a campo. */
    @Override
    public Optional<ConteudoPassagem> conteudo(UUID id) {
        // Sem .single() sobre valor possivelmente nulo: formato ausente vira -1 (recusado abaixo).
        List<Integer> formato = jdbc.sql("SELECT (conteudo ->> 'formato')::int FROM fluxo.passagem_conteudo WHERE passagem_id = ?")
            .param(id)
            .query((rs, n) -> {
                int v = rs.getInt(1);
                return rs.wasNull() ? -1 : v;
            })
            .list();
        if (formato.isEmpty()) {
            return Optional.empty();
        }
        if (formato.get(0) != ConteudoPassagem.VERSAO_FORMATO) {
            throw new IllegalStateException("formato de conteúdo de passagem desconhecido");
        }
        Map<UUID, List<ConteudoPassagem.AlertaPassagem>> alertas = new HashMap<>();
        jdbc.sql("""
                SELECT (c ->> 'episodioId')::uuid, (a ->> 'regraId')::uuid, (a ->> 'regraVersao')::int, a ->> 'tipo',
                       a ->> 'referenciaEm', a ->> 'atingidoEm', (a ->> 'pendenciaId')::uuid
                  FROM fluxo.passagem_conteudo pc,
                       jsonb_array_elements(pc.conteudo -> 'casos') AS c,
                       jsonb_array_elements(c -> 'alertas') AS a
                 WHERE pc.passagem_id = ?
                """)
            .param(id)
            .query((rs, n) -> {
                alertas.computeIfAbsent(uuid(rs, 1), k -> new ArrayList<>()).add(new ConteudoPassagem.AlertaPassagem(
                        uuid(rs, 2), rs.getInt(3), TipoRegraAlerta.valueOf(rs.getString(4)), Instant.parse(rs.getString(5)),
                        Instant.parse(rs.getString(6)), uuid(rs, 7)));
                return null;
            })
            .list();
        Map<UUID, List<ConteudoPassagem.PendenciaPassagem>> pendencias = new HashMap<>();
        jdbc.sql("""
                SELECT (c ->> 'episodioId')::uuid, (p ->> 'id')::uuid, (p ->> 'versao')::int, p ->> 'categoria',
                       p ->> 'criticidade', p ->> 'prazo', (p ->> 'vencida')::boolean, (p ->> 'responsavelUsuarioId')::uuid,
                       (p ->> 'responsavelSetorId')::uuid, p ->> 'responsavelPapel'
                  FROM fluxo.passagem_conteudo pc,
                       jsonb_array_elements(pc.conteudo -> 'casos') AS c,
                       jsonb_array_elements(c -> 'pendencias') AS p
                 WHERE pc.passagem_id = ?
                """)
            .param(id)
            .query((rs, n) -> {
                pendencias.computeIfAbsent(uuid(rs, 1), k -> new ArrayList<>()).add(new ConteudoPassagem.PendenciaPassagem(
                        uuid(rs, 2), rs.getInt(3), CategoriaBloqueio.valueOf(rs.getString(4)),
                        CriticidadeOperacional.valueOf(rs.getString(5)), Instant.parse(rs.getString(6)), rs.getBoolean(7),
                        uuid(rs, 8), uuid(rs, 9), rs.getString(10)));
                return null;
            })
            .list();
        List<ConteudoPassagem.CasoPassagem> casos = jdbc.sql("""
                SELECT (c ->> 'episodioId')::uuid, (c ->> 'versao')::int, (c ->> 'etapaId')::uuid, (c ->> 'setorId')::uuid,
                       (c ->> 'motivoId')::uuid, c ->> 'categoria', c ->> 'bloqueioDesde', c ->> 'entradaEm',
                       c ->> 'etapaDesde', (c ->> 'critico')::boolean, (c ->> 'transferencia')::boolean
                  FROM fluxo.passagem_conteudo pc, jsonb_array_elements(pc.conteudo -> 'casos') AS c
                 WHERE pc.passagem_id = ?
                """)
            .param(id)
            .query((rs, n) -> {
                UUID ep = uuid(rs, 1);
                String cat = rs.getString(6);
                String bloq = rs.getString(7);
                return new ConteudoPassagem.CasoPassagem(ep, rs.getInt(2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5),
                        cat == null ? null : CategoriaBloqueio.valueOf(cat), bloq == null ? null : Instant.parse(bloq),
                        Instant.parse(rs.getString(8)), Instant.parse(rs.getString(9)), rs.getBoolean(10), rs.getBoolean(11),
                        alertas.getOrDefault(ep, List.of()), pendencias.getOrDefault(ep, List.of()));
            })
            .list();
        return Optional.of(new ConteudoPassagem(casos));
    }

    @Override
    public void receber(UUID id, int versaoLida, String assinaturaRecebimento, Map<String, Integer> diferencas) {
        int linhas = jdbc.sql("""
                UPDATE fluxo.passagem_plantao
                   SET status = 'RECEBIDA', assinatura_recebimento = ?, diferencas_recebimento = CAST(? AS jsonb),
                       versao = versao + 1
                 WHERE id = ? AND versao = ? AND status = 'ENTREGUE'
                """)
            .param(assinaturaRecebimento).param(json(diferencas)).param(id).param(versaoLida)
            .update();
        if (linhas != 1) {
            throw new ConflitoDeVersaoException();
        }
    }

    @Override
    public void cancelar(UUID id, int versaoLida, String justificativa) {
        int linhas = jdbc.sql("""
                UPDATE fluxo.passagem_plantao
                   SET status = 'CANCELADA', justificativa_cancelamento = ?, versao = versao + 1
                 WHERE id = ? AND versao = ? AND status = 'ENTREGUE'
                """)
            .param(justificativa).param(id).param(versaoLida)
            .update();
        if (linhas != 1) {
            throw new ConflitoDeVersaoException();
        }
    }

    /** Uma única consulta (instantâneo coerente); RLS limita tudo à unidade ativa. */
    @Override
    public Nomes nomes(Referencias ref) {
        Map<String, Map<UUID, String>> porTipo = new HashMap<>();
        for (String t : List.of("P", "D", "S", "E", "M", "U")) {
            porTipo.put(t, new HashMap<>());
        }
        jdbc.sql("""
                SELECT 'P', e.id, pa.nome FROM fluxo.episodio e JOIN fluxo.paciente pa ON pa.id = e.paciente_id
                 WHERE e.id = ANY (CAST(? AS uuid[]))
                UNION ALL SELECT 'D', id, descricao FROM fluxo.pendencia WHERE id = ANY (CAST(? AS uuid[]))
                UNION ALL SELECT 'S', id, nome FROM fluxo.setor WHERE id = ANY (CAST(? AS uuid[]))
                UNION ALL SELECT 'E', id, nome FROM fluxo.etapa WHERE id = ANY (CAST(? AS uuid[]))
                UNION ALL SELECT 'M', id, descricao FROM fluxo.motivo_bloqueio WHERE id = ANY (CAST(? AS uuid[]))
                UNION ALL SELECT 'U', id, nome FROM fluxo.usuario WHERE id = ANY (CAST(? AS uuid[]))
                """)
            .param(array(ref.episodios())).param(array(ref.pendencias())).param(array(ref.setores()))
            .param(array(ref.etapas())).param(array(ref.motivos())).param(array(ref.profissionais()))
            .query((rs, n) -> {
                String valor = rs.getString(3);
                if (valor != null) {
                    porTipo.get(rs.getString(1)).put(uuid(rs, 2), valor);
                }
                return null;
            })
            .list();
        // Regras dos alertas: dados da VERSÃO gravada (histórico imutável, V18) e, à parte, a situação atual.
        Map<RegraVersaoId, RegraNaVersao> versoes = new HashMap<>();
        Map<UUID, RegraAtual> atuais = new HashMap<>();
        if (!ref.regras().isEmpty()) {
            List<RegraVersaoId> pedidas = List.copyOf(ref.regras());
            jdbc.sql("""
                    SELECT v.regra_id, v.versao, v.nome, v.tipo::text, (extract(epoch FROM v.limite) / 60)::bigint,
                           v.acao_esperada, v.ativa, et.nome, v.categoria::text
                      FROM unnest(CAST(? AS uuid[]), CAST(? AS int[])) AS q(regra_id, versao)
                      JOIN fluxo.regra_alerta_versao v ON v.regra_id = q.regra_id AND v.versao = q.versao
                      LEFT JOIN fluxo.etapa et ON et.id = v.etapa_id
                    """)
                .param(array(pedidas.stream().map(RegraVersaoId::regraId).toList()))
                .param(pedidas.stream().map(x -> Integer.toString(x.versao())).collect(Collectors.joining(",", "{", "}")))
                .query((rs, n) -> {
                    long lim = rs.getLong(5);
                    Long limite = rs.wasNull() ? null : lim;
                    RegraNaVersao v = new RegraNaVersao(uuid(rs, 1), rs.getInt(2), rs.getString(3), rs.getString(4), limite,
                            rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getString(9));
                    versoes.put(new RegraVersaoId(v.regraId(), v.versao()), v);
                    return null;
                })
                .list();
            jdbc.sql("SELECT id, versao, ativa FROM fluxo.regra_alerta WHERE id = ANY (CAST(? AS uuid[]))")
                .param(array(pedidas.stream().map(RegraVersaoId::regraId).collect(Collectors.toSet())))
                .query((rs, n) -> atuais.put(uuid(rs, 1), new RegraAtual(uuid(rs, 1), rs.getInt(2), rs.getBoolean(3))))
                .list();
        }
        return new Nomes(porTipo.get("P"), porTipo.get("D"), porTipo.get("S"), porTipo.get("E"), porTipo.get("M"),
                porTipo.get("U"), versoes, atuais);
    }

    @Override
    public void auditar(String acao, UUID passagemId, Map<String, Object> dados) {
        jdbc.sql("SELECT auditoria.registrar(?, 'fluxo.passagem_plantao', ?, CAST(? AS jsonb), (fluxo.ctx_unidades())[1])")
            .param(acao).param(passagemId.toString()).param(json(dados))
            .query(Long.class).single();
    }

    /**
     * Leitura nominal: {@code auditoria.registrar_consulta} (V17) grava o conjunto de episódios uma
     * única vez por unidade, endereçado pelo SHA-256 da lista ordenada, e acrescenta à cadeia de
     * auditoria um registro com ator, unidade, origem, a passagem (se houver), o hash do conjunto e
     * a contagem. Nenhum nome ou descrição.
     */
    @Override
    public void auditarConsulta(String acao, UUID passagemId, Collection<UUID> episodios, Map<String, Object> dados) {
        jdbc.sql("""
                SELECT auditoria.registrar_consulta(?, 'fluxo.passagem_plantao', CAST(? AS text), CAST(? AS uuid[]),
                                                    CAST(? AS jsonb), (fluxo.ctx_unidades())[1])
                """)
            .param(acao).param(passagemId == null ? null : passagemId.toString()).param(array(episodios))
            .param(json(dados))
            .query(Long.class).single();
    }

    // ----------------------------------------------------------------------------

    private static Passagem passagem(ResultSet rs, int n) throws SQLException {
        Map<String, Integer> dif = null;
        if (rs.getBoolean(17)) {
            dif = new LinkedHashMap<>();
            for (int i = 0; i < CHAVES_DIFERENCAS.size(); i++) {
                int v = rs.getInt(18 + i);
                dif.put(CHAVES_DIFERENCAS.get(i), rs.wasNull() ? 0 : v);
            }
        }
        return new Passagem(uuid(rs, 1), rs.getString(2), instante(rs, 3), uuid(rs, 4), rs.getString(5), instante(rs, 6),
                rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getString(13),
                uuid(rs, 14), rs.getString(15), instante(rs, 16), dif, uuid(rs, 24), rs.getString(25), instante(rs, 26),
                rs.getString(27), rs.getInt(28));
    }

    /** JSON de um mapa de contagens/valores escalares (chaves fixas do código; sem texto livre). */
    private static String json(Map<String, ?> dados) {
        return dados.entrySet().stream()
            .map(e -> {
                Object v = e.getValue();
                String valor = v instanceof Number || v instanceof Boolean ? v.toString() : "\"" + v + "\"";
                if (!e.getKey().matches("^[A-Za-z]{1,40}$") || (!(v instanceof Number) && !(v instanceof Boolean)
                        && !String.valueOf(v).matches("^[A-Za-z0-9_:.-]{0,64}$"))) {
                    throw new IllegalArgumentException("dado de auditoria fora do formato");
                }
                return "\"" + e.getKey() + "\":" + valor;
            })
            .collect(Collectors.joining(",", "{", "}"));
    }

    private static String array(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private static UUID uuid(ResultSet rs, int i) throws SQLException {
        return rs.getObject(i, UUID.class);
    }

    private static Instant instante(ResultSet rs, int i) throws SQLException {
        OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
