package br.fluxosaude.relatorio.infra;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.indicador.infra.RepositorioIndicadoresJdbc;
import br.fluxosaude.relatorio.aplicacao.RepositorioRelatorios;
import br.fluxosaude.relatorio.dominio.LinhaRelatorio;
import br.fluxosaude.relatorio.dominio.PendenciaOperacional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Relatórios sobre as funções fluxo.rel_* (V19). SQL sempre parametrizado; RLS por unidade. Regras
 * e situações dos abertos reutilizam exatamente as consultas dos indicadores (mesma definição).
 */
final class RepositorioRelatoriosJdbc implements RepositorioRelatorios {

    private static final String COLUNAS = "secao, chave, rotulo, grupo, quantidade, parte, base, episodios, minutos, "
            + "media, mediana, p90, maximo";

    private final JdbcClient jdbc;
    private final RepositorioIndicadoresJdbc indicadores;

    RepositorioRelatoriosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.indicadores = new RepositorioIndicadoresJdbc(jdbc);
    }

    @Override
    public Unidade unidade(UUID id) {
        List<Unidade> u = jdbc.sql("SELECT id, nome, fuso_horario FROM fluxo.unidade WHERE id = ?").param(id)
            .query((rs, n) -> new Unidade(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        if (u.isEmpty()) {
            throw new IllegalStateException("unidade ativa não visível no contexto");
        }
        return u.get(0);
    }

    @Override
    public Optional<String> nomeSetor(UUID setor) {
        return jdbc.sql("SELECT nome FROM fluxo.setor WHERE id = ?").param(setor).query((rs, n) -> rs.getString(1))
            .list().stream().findFirst();
    }

    @Override
    public Optional<String> nomeEtapa(UUID etapa) {
        return jdbc.sql("SELECT nome FROM fluxo.etapa WHERE id = ?").param(etapa).query((rs, n) -> rs.getString(1))
            .list().stream().findFirst();
    }

    @Override
    public Periodo periodo(String fuso, LocalDate inicio, LocalDate fim) {
        var p = indicadores.periodo(fuso, inicio, fim);
        return new Periodo(p.inicio(), p.fim());
    }

    @Override
    public List<LinhaRelatorio> resumo(UUID unidade, Periodo p, Instant agora, UUID setor) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.rel_resumo(?, ?, ?, ?, CAST(? AS uuid)) ORDER BY secao, chave")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(ts(agora)).param(texto(setor))
            .query(RepositorioRelatoriosJdbc::linha).list();
    }

    @Override
    public List<LinhaRelatorio> gargalos(UUID unidade, Periodo p, Instant agora, UUID setor, UUID etapa, String categoria) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.rel_gargalos(?, ?, ?, ?, CAST(? AS uuid), CAST(? AS uuid), "
                + "CAST(? AS text)) ORDER BY secao, chave")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(ts(agora)).param(texto(setor)).param(texto(etapa))
            .param(categoria)
            .query(RepositorioRelatoriosJdbc::linha).list();
    }

    @Override
    public List<LinhaRelatorio> pendencias(UUID unidade, Periodo p, Instant agora, UUID setor, String categoria) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.rel_pendencias(?, ?, ?, ?, CAST(? AS uuid), CAST(? AS text)) "
                + "ORDER BY secao, chave")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(ts(agora)).param(texto(setor)).param(categoria)
            .query(RepositorioRelatoriosJdbc::linha).list();
    }

    @Override
    public List<PendenciaOperacional> listaPendencias(UUID unidade, Instant agora, UUID setor, String categoria, int limite) {
        return jdbc.sql("""
                SELECT pendencia_id, episodio_id, paciente_nome, setor_nome, etapa_nome, motivo_descricao, descricao,
                       categoria, criticidade, responsavel_tipo, responsavel_nome, prazo, criada_em, vencida
                  FROM fluxo.rel_pendencias_lista(?, ?, CAST(? AS uuid), CAST(? AS text), ?)
                """)
            .param(unidade).param(ts(agora)).param(texto(setor)).param(categoria).param(limite)
            .query((rs, n) -> new PendenciaOperacional(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getString(9), rs.getString(10), rs.getString(11), instante(rs, 12), instante(rs, 13), rs.getBoolean(14)))
            .list();
    }

    @Override
    public List<LinhaRelatorio> metricasPeriodo(UUID unidade, Periodo p, Instant agora, UUID setor) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.rel_metricas_periodo(?, ?, ?, ?, CAST(? AS uuid)) "
                + "ORDER BY secao, chave")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(ts(agora)).param(texto(setor))
            .query(RepositorioRelatoriosJdbc::linha).list();
    }

    @Override
    public MudancasRegras mudancasRegras(UUID unidade, Instant desde, Instant ate) {
        return jdbc.sql("SELECT alteracoes, historico_desde FROM fluxo.rel_mudancas_regras(?, ?, ?)")
            .param(unidade).param(ts(desde)).param(ts(ate))
            .query((rs, n) -> new MudancasRegras(rs.getLong(1), instante(rs, 2))).list().get(0);
    }

    @Override
    public List<LinhaRelatorio> qualidade(UUID unidade, Periodo p, Instant agora, UUID setor) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.rel_qualidade(?, ?, ?, ?, CAST(? AS uuid)) ORDER BY secao, chave")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(ts(agora)).param(texto(setor))
            .query(RepositorioRelatoriosJdbc::linha).list();
    }

    @Override
    public List<RegraAlerta> regrasAtivas() {
        return indicadores.regrasAtivas();
    }

    @Override
    public List<SituacaoEpisodio> situacoesAbertas(UUID setor, int limite) {
        return indicadores.situacoesAbertas(setor, limite);
    }

    // ----------------------------------------------------------------------------

    private static LinhaRelatorio linha(ResultSet rs, int n) throws SQLException {
        return new LinhaRelatorio(null, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), lng(rs, 5),
                lng(rs, 6), lng(rs, 7), lng(rs, 8), dbl(rs, 9), dbl(rs, 10), dbl(rs, 11), dbl(rs, 12), dbl(rs, 13));
    }

    private static Long lng(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    private static Double dbl(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String texto(UUID id) {
        return id == null ? null : id.toString();
    }

    private static Instant instante(ResultSet rs, int i) throws SQLException {
        OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
