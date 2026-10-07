package br.fluxosaude.indicador.infra;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.indicador.aplicacao.RepositorioIndicadores;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Indicadores sobre as funções fluxo.ind_* (V16). Só agregados: nenhuma consulta daqui devolve
 * nome, CNS ou identificador de paciente/episódio. SQL sempre parametrizado; RLS por unidade.
 */
final class RepositorioIndicadoresJdbc implements RepositorioIndicadores {

    private final JdbcClient jdbc;

    RepositorioIndicadoresJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String fusoDaUnidade(UUID unidade) {
        return jdbc.sql("SELECT fuso_horario FROM fluxo.unidade WHERE id = ?").param(unidade).query(String.class).single();
    }

    @Override
    public boolean setorDaUnidade(UUID setor) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM fluxo.setor WHERE id = ?)").param(setor)
            .query(Boolean.class).single());
    }

    @Override
    public Periodo periodo(String fuso, LocalDate inicio, LocalDate fim) {
        return jdbc.sql("SELECT inicio, fim FROM fluxo.ind_periodo(?, CAST(? AS date), CAST(? AS date))")
            .param(fuso).param(inicio.toString()).param(fim.toString())
            .query((rs, n) -> new Periodo(instante(rs, 1), instante(rs, 2))).single();
    }

    @Override
    public Duracoes permanencia(UUID unidade, Periodo p, UUID setor) {
        return jdbc.sql("SELECT incluidos, excluidos, media_min, mediana_min, minimo_min, maximo_min "
                + "FROM fluxo.ind_permanencia(?, ?, ?, CAST(? AS uuid))")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(texto(setor))
            .query((rs, n) -> new Duracoes(rs.getLong(1), rs.getLong(2), dbl(rs, 3), dbl(rs, 4), dbl(rs, 5), dbl(rs, 6)))
            .single();
    }

    @Override
    public List<AcimaDoLimite> acimaDosLimites(UUID unidade, Periodo p, UUID setor) {
        return jdbc.sql("SELECT regra_id, regra_nome, regra_versao, limite_min, populacao, acima "
                + "FROM fluxo.ind_acima_dos_limites(?, ?, ?, CAST(? AS uuid))")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(texto(setor))
            .query((rs, n) -> new AcimaDoLimite(uuid(rs, 1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getLong(5),
                    rs.getLong(6)))
            .list();
    }

    @Override
    public List<Desfecho> desfechos(UUID unidade, Periodo p, UUID setor) {
        return jdbc.sql("SELECT desfecho, quantidade FROM fluxo.ind_desfechos(?, ?, ?, CAST(? AS uuid))")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(texto(setor))
            .query((rs, n) -> new Desfecho(rs.getString(1), rs.getLong(2))).list();
    }

    @Override
    public Duracoes solicitacaoAceite(UUID unidade, Periodo p, UUID setor) {
        return tempos("fluxo.ind_solicitacao_aceite", unidade, p, setor);
    }

    @Override
    public Duracoes aceiteSaida(UUID unidade, Periodo p, UUID setor) {
        return tempos("fluxo.ind_aceite_saida", unidade, p, setor);
    }

    private Duracoes tempos(String funcao, UUID unidade, Periodo p, UUID setor) {
        return jdbc.sql("SELECT incluidos, sem_marco, media_min, mediana_min FROM " + funcao + "(?, ?, ?, CAST(? AS uuid))")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(texto(setor))
            .query((rs, n) -> new Duracoes(rs.getLong(1), rs.getLong(2), dbl(rs, 3), dbl(rs, 4), null, null))
            .single();
    }

    @Override
    public List<Motivo> motivos(UUID unidade, Periodo p, UUID setor, Instant agora) {
        return jdbc.sql("SELECT motivo_id, codigo, descricao, categoria, minutos, inicios, episodios "
                + "FROM fluxo.ind_motivos(?, ?, ?, CAST(? AS uuid), ?)")
            .param(unidade).param(ts(p.inicio())).param(ts(p.fim())).param(texto(setor)).param(ts(agora))
            .query((rs, n) -> new Motivo(uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5),
                    rs.getLong(6), rs.getLong(7)))
            .list();
    }

    @Override
    public List<Dia> volumeDiario(UUID unidade, String fuso, LocalDate inicio, LocalDate fim, UUID setor) {
        return jdbc.sql("SELECT dia, entradas, saidas FROM fluxo.ind_volume_diario(?, ?, CAST(? AS date), CAST(? AS date), "
                + "CAST(? AS uuid))")
            .param(unidade).param(fuso).param(inicio.toString()).param(fim.toString()).param(texto(setor))
            .query((rs, n) -> new Dia(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3))).list();
    }

    @Override
    public List<ItemRetrato> retrato(UUID unidade, UUID setor, Instant agora) {
        return jdbc.sql("SELECT dimensao, chave, nome, quantidade FROM fluxo.ind_retrato(?, CAST(? AS uuid), ?)")
            .param(unidade).param(texto(setor)).param(ts(agora))
            .query((rs, n) -> new ItemRetrato(rs.getString(1), uuid(rs, 2), rs.getString(3), rs.getLong(4))).list();
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

    private record Linha(UUID id, UUID etapaId, Instant entrada, Instant etapaDesde, Instant bloqueioDesde, String categoria,
                         Instant ultimo, UUID pendenciaId, String pendenciaCategoria, Instant prazo) {
    }

    /** Uma única consulta (instantâneo coerente), sem nomes: só o necessário para o motor de alertas. */
    @Override
    public List<SituacaoEpisodio> situacoesAbertas(UUID setor, int limite) {
        List<Linha> linhas = jdbc.sql("""
                WITH eps AS (
                    SELECT e.id, e.etapa_id, e.entrada_em, e.etapa_desde, e.bloqueio_desde, m.categoria::text AS categoria,
                           (SELECT max(ev.registrado_em) FROM fluxo.evento_episodio ev WHERE ev.episodio_id = e.id) AS ultimo
                      FROM fluxo.episodio e
                      LEFT JOIN fluxo.motivo_bloqueio m ON m.id = e.motivo_bloqueio_id
                     WHERE e.encerrado_em IS NULL AND (CAST(? AS uuid) IS NULL OR e.setor_id = CAST(? AS uuid))
                     ORDER BY e.entrada_em, e.id
                     LIMIT ?
                )
                SELECT eps.id, eps.etapa_id, eps.entrada_em, eps.etapa_desde, eps.bloqueio_desde, eps.categoria, eps.ultimo,
                       pd.id, pd.categoria::text, pd.prazo
                  FROM eps LEFT JOIN fluxo.pendencia pd ON pd.episodio_id = eps.id AND pd.status = 'ABERTA'
                 ORDER BY eps.entrada_em, eps.id, pd.id
                """)
            .param(texto(setor)).param(texto(setor)).param(limite)
            .query((rs, n) -> new Linha(uuid(rs, 1), uuid(rs, 2), instante(rs, 3), instante(rs, 4), instante(rs, 5),
                    rs.getString(6), instante(rs, 7), uuid(rs, 8), rs.getString(9), instante(rs, 10)))
            .list();
        Map<UUID, List<Linha>> porEpisodio = new LinkedHashMap<>();
        linhas.forEach(l -> porEpisodio.computeIfAbsent(l.id(), k -> new ArrayList<>()).add(l));
        List<SituacaoEpisodio> resultado = new ArrayList<>(porEpisodio.size());
        porEpisodio.forEach((id, ls) -> {
            Linha l = ls.get(0);
            resultado.add(new SituacaoEpisodio(l.id(), l.etapaId(), l.entrada(), l.etapaDesde(), l.bloqueioDesde(),
                    l.categoria() == null ? null : CategoriaBloqueio.valueOf(l.categoria()), l.ultimo(),
                    ls.stream().filter(x -> x.pendenciaId() != null).map(x -> new SituacaoEpisodio.PendenciaAberta(
                            x.pendenciaId(), CategoriaBloqueio.valueOf(x.pendenciaCategoria()), x.prazo())).toList()));
        });
        return resultado;
    }

    // ----------------------------------------------------------------------------

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String texto(UUID id) {
        return id == null ? null : id.toString();
    }

    private static Double dbl(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    private static UUID uuid(ResultSet rs, int i) throws SQLException {
        return rs.getObject(i, UUID.class);
    }

    private static Instant instante(ResultSet rs, int i) throws SQLException {
        OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
