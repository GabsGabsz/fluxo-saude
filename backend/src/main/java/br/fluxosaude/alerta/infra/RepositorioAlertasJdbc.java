package br.fluxosaude.alerta.infra;

import br.fluxosaude.alerta.aplicacao.RepositorioAlertas;
import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Alertas sobre o PostgreSQL (V13). SQL sempre parametrizado; RLS restringe tudo à unidade
 * ativa; só episódios ABERTOS são lidos (RN-008). Nenhum dado clínico é lido.
 */
final class RepositorioAlertasJdbc implements RepositorioAlertas {

    private static final String SELECT_REGRA = """
            SELECT id, nome, tipo::text, etapa_id, categoria::text, extract(epoch FROM limite)::bigint, acao_esperada,
                   ativa, versao
              FROM fluxo.regra_alerta
            """;

    private static final String SELECT_EPISODIO = """
            SELECT e.id, e.etapa_id, e.entrada_em, e.etapa_desde, e.bloqueio_desde, m.categoria::text,
                   (SELECT max(ev.registrado_em) FROM fluxo.evento_episodio ev WHERE ev.episodio_id = e.id),
                   p.nome, s.nome, et.nome, m.descricao
              FROM fluxo.episodio e
              JOIN fluxo.paciente p ON p.id = e.paciente_id
              JOIN fluxo.setor s ON s.id = e.setor_id
              JOIN fluxo.etapa et ON et.id = e.etapa_id
              LEFT JOIN fluxo.motivo_bloqueio m ON m.id = e.motivo_bloqueio_id
             WHERE e.encerrado_em IS NULL
            """;

    private static final String SELECT_PENDENCIA = """
            SELECT pd.episodio_id, pd.id, pd.categoria::text, pd.descricao, pd.prazo,
                   coalesce(u.nome, s.nome, pd.responsavel_papel::text)
              FROM fluxo.pendencia pd
              JOIN fluxo.episodio e ON e.id = pd.episodio_id AND e.encerrado_em IS NULL
              LEFT JOIN fluxo.usuario u ON u.id = pd.responsavel_usuario_id
              LEFT JOIN fluxo.setor s ON s.id = pd.responsavel_setor_id
             WHERE pd.status = 'ABERTA'
            """;

    private final JdbcClient jdbc;

    RepositorioAlertasJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------- regras

    @Override
    public List<RegraAlerta> regras(boolean somenteAtivas) {
        return jdbc.sql(SELECT_REGRA + (somenteAtivas ? " WHERE ativa" : "") + " ORDER BY nome, id")
            .query(RepositorioAlertasJdbc::regra)
            .list();
    }

    @Override
    public Optional<RegraAlerta> regra(UUID id) {
        return jdbc.sql(SELECT_REGRA + " WHERE id = ?").param(id).query(RepositorioAlertasJdbc::regra).optional();
    }

    @Override
    public boolean etapaDaUnidade(UUID etapaId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM fluxo.etapa WHERE id = ?)").param(etapaId)
            .query(Boolean.class).single();
    }

    @Override
    public void inserirRegra(RegraAlerta r) {
        int linhas = jdbc.sql("""
                INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, etapa_id, categoria, limite, acao_esperada, ativa)
                SELECT ?, u.id, ?, CAST(? AS fluxo.tipo_regra_alerta), CAST(? AS uuid),
                       CAST(? AS fluxo.categoria_bloqueio), CAST(? AS bigint) * interval '1 second', CAST(? AS text), ?
                  FROM unnest(fluxo.ctx_unidades()) AS u(id)
                """)
            .param(r.id()).param(r.nome()).param(r.tipo().name()).param(texto(r.etapaId()))
            .param(r.categoria() == null ? null : r.categoria().name()).param(segundos(r.limite()))
            .param(r.acaoEsperada()).param(r.ativa())
            .update();
        if (linhas != 1) {
            throw new IllegalStateException("regra de alerta não inserida (contexto sem unidade ativa)");
        }
    }

    @Override
    public void atualizarRegra(RegraAlerta r, int versaoLida) {
        int linhas = jdbc.sql("""
                UPDATE fluxo.regra_alerta
                   SET nome = ?, etapa_id = CAST(? AS uuid), categoria = CAST(? AS fluxo.categoria_bloqueio),
                       limite = CAST(? AS bigint) * interval '1 second', acao_esperada = CAST(? AS text), ativa = ?,
                       versao = versao + 1
                 WHERE id = ? AND versao = ?
                """)
            .param(r.nome()).param(texto(r.etapaId())).param(r.categoria() == null ? null : r.categoria().name())
            .param(segundos(r.limite())).param(r.acaoEsperada()).param(r.ativa()).param(r.id()).param(versaoLida)
            .update();
        if (linhas != 1) {
            throw new ConflitoDeVersaoException();
        }
    }

    // ------------------------------------------------------------------- episódios abertos

    @Override
    public List<EpisodioMonitorado> episodiosAbertos(int limite) {
        List<Linha> linhas = jdbc.sql(SELECT_EPISODIO + " ORDER BY e.entrada_em, e.id LIMIT ?")
            .param(limite)
            .query(RepositorioAlertasJdbc::linha)
            .list();
        return montar(linhas, jdbc.sql(SELECT_PENDENCIA + " ORDER BY pd.prazo, pd.id")
            .query(RepositorioAlertasJdbc::pendencia)
            .list());
    }

    @Override
    public List<EpisodioMonitorado> episodiosAbertos(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String arr = arrayDeUuids(ids);
        List<Linha> linhas = jdbc.sql(SELECT_EPISODIO + " AND e.id = ANY (CAST(? AS uuid[])) ORDER BY e.entrada_em, e.id")
            .param(arr)
            .query(RepositorioAlertasJdbc::linha)
            .list();
        if (linhas.isEmpty()) {
            return List.of();
        }
        return montar(linhas, jdbc.sql(SELECT_PENDENCIA + " AND pd.episodio_id = ANY (CAST(? AS uuid[])) ORDER BY pd.prazo, pd.id")
            .param(arr)
            .query(RepositorioAlertasJdbc::pendencia)
            .list());
    }

    // ------------------------------------------------------------------- ciência

    @Override
    public Map<Ocorrencia, Ciencia> ciencias(Collection<UUID> episodioIds) {
        if (episodioIds.isEmpty()) {
            return Map.of();
        }
        String ids = arrayDeUuids(episodioIds);
        record Par(Ocorrencia ocorrencia, Ciencia ciencia) {
        }
        Map<Ocorrencia, Ciencia> resultado = new HashMap<>();
        jdbc.sql("""
                SELECT c.episodio_id, c.regra_id, c.regra_versao, c.referencia_em, c.pendencia_id, u.nome, c.registrada_em
                  FROM fluxo.ciencia_alerta c
                  LEFT JOIN fluxo.usuario u ON u.id = c.autor_id
                 WHERE c.episodio_id = ANY (CAST(? AS uuid[]))
                """)
            .param(ids)
            .query((rs, n) -> new Par(new Ocorrencia(uuid(rs, 1), uuid(rs, 2), rs.getInt(3), instante(rs, 4),
                    uuid(rs, 5)), new Ciencia(rs.getString(6), instante(rs, 7))))
            .list()
            .forEach(p -> resultado.put(p.ocorrencia(), p.ciencia()));
        return resultado;
    }

    @Override
    public Optional<EstadoRegra> travarRegra(UUID regraId) {
        return jdbc.sql("SELECT versao, ativa FROM fluxo.travar_regra_alerta(?)")
            .param(regraId)
            .query((rs, n) -> new EstadoRegra(rs.getInt(1), rs.getBoolean(2)))
            .optional();
    }

    @Override
    public boolean registrarCiencia(UUID id, Ocorrencia o) {
        try {
            return inserirCiencia(id, o);
        } catch (DataAccessException e) {
            // FX409 (V14): a regra não está mais na versão enviada.
            if (e.getMostSpecificCause() instanceof SQLException s && "FX409".equals(s.getSQLState())) {
                throw new ConflitoDeVersaoException();
            }
            throw e;
        }
    }

    private boolean inserirCiencia(UUID id, Ocorrencia o) {
        return jdbc.sql("""
                INSERT INTO fluxo.ciencia_alerta (id, unidade_id, episodio_id, regra_id, regra_versao, referencia_em,
                                                  pendencia_id)
                SELECT ?, e.unidade_id, e.id, ?, ?, ?, CAST(? AS uuid)
                  FROM fluxo.episodio e
                 WHERE e.id = ?
                ON CONFLICT DO NOTHING
                """)
            .param(id).param(o.regraId()).param(o.regraVersao())
            .param(OffsetDateTime.ofInstant(o.referenciaEm(), ZoneOffset.UTC))
            .param(texto(o.pendenciaId())).param(o.episodioId())
            .update() == 1;
    }

    // ----------------------------------------------------------------------------

    private record Linha(UUID id, UUID etapaId, Instant entrada, Instant etapaDesde, Instant bloqueioDesde,
                         String categoria, Instant ultimoRegistro, String paciente, String setor, String etapa,
                         String motivo) {
    }

    private record Pend(UUID episodioId, UUID id, String categoria, String descricao, Instant prazo,
                        String responsavel) {
    }

    private static List<EpisodioMonitorado> montar(List<Linha> linhas, List<Pend> pendencias) {
        Map<UUID, List<Pend>> porEpisodio = new HashMap<>();
        for (Pend p : pendencias) {
            porEpisodio.computeIfAbsent(p.episodioId(), k -> new ArrayList<>()).add(p);
        }
        List<EpisodioMonitorado> resultado = new ArrayList<>(linhas.size());
        for (Linha l : linhas) {
            List<Pend> ps = porEpisodio.getOrDefault(l.id(), List.of());
            CategoriaBloqueio cat = l.categoria() == null ? null : CategoriaBloqueio.valueOf(l.categoria());
            SituacaoEpisodio s = new SituacaoEpisodio(l.id(), l.etapaId(), l.entrada(), l.etapaDesde(), l.bloqueioDesde(),
                    cat, l.ultimoRegistro(), ps.stream().map(p -> new SituacaoEpisodio.PendenciaAberta(p.id(),
                            CategoriaBloqueio.valueOf(p.categoria()), p.prazo())).toList());
            resultado.add(new EpisodioMonitorado(s, l.paciente(), l.setor(), l.etapa(), l.motivo(), l.categoria(),
                    ps.stream().map(p -> new PendenciaResumo(p.id(), p.descricao(), p.responsavel(), p.prazo())).toList()));
        }
        return resultado;
    }

    private static Linha linha(ResultSet rs, int n) throws SQLException {
        return new Linha(uuid(rs, 1), uuid(rs, 2), instante(rs, 3), instante(rs, 4), instante(rs, 5), rs.getString(6),
                instante(rs, 7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11));
    }

    private static Pend pendencia(ResultSet rs, int n) throws SQLException {
        return new Pend(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getString(4), instante(rs, 5), rs.getString(6));
    }

    private static RegraAlerta regra(ResultSet rs, int n) throws SQLException {
        long seg = rs.getLong(6);
        Duration limite = rs.wasNull() ? null : Duration.ofSeconds(seg);
        String cat = rs.getString(5);
        return new RegraAlerta(uuid(rs, 1), rs.getString(2), TipoRegraAlerta.valueOf(rs.getString(3)), uuid(rs, 4),
                cat == null ? null : CategoriaBloqueio.valueOf(cat), limite, rs.getString(7), rs.getBoolean(8),
                rs.getInt(9));
    }

    private static UUID uuid(ResultSet rs, int i) throws SQLException {
        return rs.getObject(i, UUID.class);
    }

    private static Instant instante(ResultSet rs, int i) throws SQLException {
        OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    /** Literal de array só com UUIDs (tipados), sempre passado como parâmetro. */
    private static String arrayDeUuids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private static String texto(UUID id) {
        return id == null ? null : id.toString();
    }

    private static Long segundos(Duration d) {
        return d == null ? null : d.toSeconds();
    }
}
