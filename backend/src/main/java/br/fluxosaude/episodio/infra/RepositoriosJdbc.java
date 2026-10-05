package br.fluxosaude.episodio.infra;

import br.fluxosaude.compartilhado.ConflitoDeVersaoException;
import br.fluxosaude.compartilhado.JsonPlano;
import br.fluxosaude.episodio.aplicacao.Consultas;
import br.fluxosaude.episodio.aplicacao.Repositorios;
import br.fluxosaude.episodio.dominio.Bloqueio;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import br.fluxosaude.episodio.dominio.Episodio;
import br.fluxosaude.episodio.dominio.Etapa;
import br.fluxosaude.episodio.dominio.EventoEpisodio;
import br.fluxosaude.episodio.dominio.FluxoConfigurado;
import br.fluxosaude.episodio.dominio.MotivoBloqueio;
import br.fluxosaude.episodio.dominio.NaturezaEtapa;
import br.fluxosaude.episodio.dominio.NovoPaciente;
import br.fluxosaude.episodio.dominio.Pendencia;
import br.fluxosaude.episodio.dominio.PoliticaTempo;
import br.fluxosaude.episodio.dominio.ProtocoloExterno;
import br.fluxosaude.episodio.dominio.Responsavel;
import br.fluxosaude.episodio.dominio.StatusPendencia;
import br.fluxosaude.episodio.dominio.TipoDesfecho;
import br.fluxosaude.identidade.dominio.Papel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Repositórios sobre JDBC, ligados à transação aberta pelo {@link TransacaoJdbc} (contexto e
 * RLS já aplicados). Regras de revisão: SQL sempre parametrizado; casts explícitos em
 * parâmetros que podem ser nulos; instantes sempre em UTC ({@code timestamptz}).
 */
final class RepositoriosJdbc implements Repositorios {

    private final JdbcClient jdbc;
    private final ConsultasJdbc consultas;

    RepositoriosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.consultas = new ConsultasJdbc(jdbc);
    }

    // ------------------------------------------------------------ configuração

    @Override
    public FluxoConfigurado fluxoDaUnidade(UUID unidadeId) {
        List<Etapa> etapas = jdbc.sql("""
                SELECT id, codigo, nome, natureza::text, desfecho::text, inicial, exige_motivo_bloqueio,
                       exige_protocolo_externo, exige_justificativa, ativa
                  FROM fluxo.etapa WHERE unidade_id = ?
                """)
            .param(unidadeId)
            .query((rs, n) -> new Etapa(uuid(rs, 1), rs.getString(2), rs.getString(3),
                    NaturezaEtapa.valueOf(rs.getString(4)),
                    rs.getString(5) == null ? null : TipoDesfecho.valueOf(rs.getString(5)),
                    rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9), rs.getBoolean(10)))
            .list();
        List<FluxoConfigurado.Transicao> transicoes = jdbc.sql(
                "SELECT origem_id, destino_id FROM fluxo.transicao_etapa WHERE unidade_id = ?")
            .param(unidadeId)
            .query((rs, n) -> new FluxoConfigurado.Transicao(uuid(rs, 1), uuid(rs, 2)))
            .list();
        List<MotivoBloqueio> motivos = jdbc.sql("""
                SELECT id, categoria::text, codigo, descricao, exige_detalhe, ativo
                  FROM fluxo.motivo_bloqueio WHERE unidade_id = ?
                """)
            .param(unidadeId)
            .query((rs, n) -> new MotivoBloqueio(uuid(rs, 1), CategoriaBloqueio.valueOf(rs.getString(2)),
                    rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6)))
            .list();
        PoliticaTempo politica = jdbc.sql("""
                SELECT extract(epoch FROM retroatividade_maxima)::bigint, extract(epoch FROM limiar_ajuste_manual)::bigint
                  FROM fluxo.unidade WHERE id = ?
                """)
            .param(unidadeId)
            // Tolerância de futuro de 1 min (banco: 2 min) — a aplicação é mais estrita que o
            // banco, para que diferença de relógio/latência nunca faça o banco recusar (V10).
            .query((rs, n) -> new PoliticaTempo(Duration.ofMinutes(1), Duration.ofSeconds(rs.getLong(1)),
                    Duration.ofSeconds(rs.getLong(2))))
            .single();
        return new FluxoConfigurado(unidadeId, etapas, transicoes, motivos, politica);
    }

    // --------------------------------------------------------------- pacientes

    @Override
    public Optional<UUID> pacientePorCns(UUID unidadeId, String cns) {
        return jdbc.sql("SELECT id FROM fluxo.paciente WHERE unidade_id = ? AND cns = ?")
            .param(unidadeId).param(cns)
            .query((rs, n) -> uuid(rs, 1)).optional();
    }

    @Override
    public Optional<UUID> pacientePorIdentificador(UUID unidadeId, String identificador) {
        return jdbc.sql("SELECT id FROM fluxo.paciente WHERE unidade_id = ? AND identificador_institucional = ?")
            .param(unidadeId).param(identificador)
            .query((rs, n) -> uuid(rs, 1)).optional();
    }

    @Override
    public SituacaoPaciente situacaoPaciente(UUID pacienteId) {
        return jdbc.sql("SELECT reconciliado_com_id IS NULL FROM fluxo.paciente WHERE id = ?")
            .param(pacienteId)
            .query((rs, n) -> rs.getBoolean(1) ? SituacaoPaciente.ATIVO : SituacaoPaciente.RECONCILIADO)
            .optional()
            .orElse(SituacaoPaciente.INEXISTENTE);
    }

    @Override
    public UUID inserirPaciente(UUID id, UUID unidadeId, NovoPaciente p) {
        jdbc.sql("""
                INSERT INTO fluxo.paciente (id, unidade_id, nome, data_nascimento, cns, identificador_institucional)
                VALUES (?, ?, ?, CAST(? AS date), CAST(? AS text), CAST(? AS text))
                """)
            .param(id).param(unidadeId).param(p.nome())
            .param(p.dataNascimento() == null ? null : p.dataNascimento().toString())
            .param(p.cns()).param(p.identificadorInstitucional())
            .update();
        return id;
    }

    // --------------------------------------------------------------- episódios

    @Override
    public boolean existeEpisodioAtivo(UUID unidadeId, UUID pacienteId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM fluxo.episodio
                                WHERE unidade_id = ? AND paciente_id = ? AND encerrado_em IS NULL)
                """)
            .param(unidadeId).param(pacienteId)
            .query(Boolean.class).single();
    }

    /** FOR UPDATE: o caso de uso trabalha sobre a linha travada até o COMMIT (sem corrida entre ler e gravar). */
    @Override
    public Optional<Episodio> episodio(UUID id) {
        return jdbc.sql("""
                SELECT e.id, e.unidade_id, e.paciente_id, e.setor_id, e.entrada_em, e.etapa_id, e.etapa_desde,
                       e.motivo_bloqueio_id, e.motivo_detalhe, e.bloqueio_desde, e.protocolo_sistema, e.protocolo_numero,
                       e.especialidade_requerida_id, e.destino_descricao, e.desfecho::text, e.encerrado_em,
                       e.justificativa_encerramento, e.justificativa_duplicidade,
                       (SELECT max(ev.ocorrido_em) FROM fluxo.evento_episodio ev WHERE ev.episodio_id = e.id),
                       e.versao
                  FROM fluxo.episodio e
                 WHERE e.id = ?
                   FOR UPDATE OF e
                """)
            .param(id)
            .query((rs, n) -> Episodio.reconstituir(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4),
                    instante(rs, 5), uuid(rs, 6), instante(rs, 7),
                    uuid(rs, 8) == null ? null : new Bloqueio(uuid(rs, 8), rs.getString(9), instante(rs, 10)),
                    rs.getString(11) == null ? null : new ProtocoloExterno(rs.getString(11), rs.getString(12)),
                    uuid(rs, 13), rs.getString(14),
                    rs.getString(15) == null ? null : TipoDesfecho.valueOf(rs.getString(15)),
                    instante(rs, 16), rs.getString(17), rs.getString(18), instante(rs, 19), rs.getInt(20)))
            .optional();
    }

    @Override
    public void inserir(Episodio e) {
        jdbc.sql("""
                INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde,
                                            justificativa_duplicidade)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS text))
                """)
            .param(e.id()).param(e.unidadeId()).param(e.pacienteId()).param(e.setorId())
            .param(ts(e.entradaEm())).param(e.etapaId()).param(ts(e.etapaDesde()))
            .param(e.justificativaDuplicidade().orElse(null))
            .update();
    }

    @Override
    public void atualizar(Episodio e) {
        Bloqueio b = e.bloqueio().orElse(null);
        ProtocoloExterno p = e.protocolo().orElse(null);
        int linhas = jdbc.sql("""
                UPDATE fluxo.episodio
                   SET setor_id = ?, etapa_id = ?, etapa_desde = ?,
                       motivo_bloqueio_id = CAST(? AS uuid), motivo_detalhe = CAST(? AS text),
                       bloqueio_desde = CAST(? AS timestamptz),
                       protocolo_sistema = CAST(? AS text), protocolo_numero = CAST(? AS text),
                       especialidade_requerida_id = CAST(? AS uuid), destino_descricao = CAST(? AS text),
                       desfecho = CAST(? AS fluxo.tipo_desfecho), encerrado_em = CAST(? AS timestamptz),
                       justificativa_encerramento = CAST(? AS text),
                       versao = versao + 1
                 WHERE id = ? AND versao = ?
                """)
            .param(e.setorId()).param(e.etapaId()).param(ts(e.etapaDesde()))
            .param(b == null ? null : b.motivoId().toString()).param(b == null ? null : b.detalhe())
            .param(b == null ? null : ts(b.desde()))
            .param(p == null ? null : p.sistema()).param(p == null ? null : p.numero())
            .param(e.especialidadeRequeridaId().map(UUID::toString).orElse(null))
            .param(e.destinoDescricao().orElse(null))
            .param(e.desfecho().map(Enum::name).orElse(null))
            .param(e.encerradoEm().map(RepositoriosJdbc::ts).orElse(null))
            .param(e.justificativaEncerramento().orElse(null))
            .param(e.id()).param(e.versao())
            .update();
        if (linhas != 1) {
            throw new ConflitoDeVersaoException();
        }
    }

    // --------------------------------------------------------------- pendências

    private static final String COLUNAS_PENDENCIA = """
            id, unidade_id, episodio_id, categoria::text, descricao, responsavel_usuario_id, responsavel_setor_id,
            responsavel_papel::text, prazo, criticidade_operacional::text, status::text, criada_em, resolucao, versao
            """;

    @Override
    public Optional<Pendencia> pendencia(UUID id) {
        return jdbc.sql("SELECT " + COLUNAS_PENDENCIA + " FROM fluxo.pendencia WHERE id = ? FOR UPDATE")
            .param(id)
            .query(RepositoriosJdbc::pendencia)
            .optional();
    }

    @Override
    public List<Pendencia> pendenciasAbertas(UUID episodioId) {
        return jdbc.sql("SELECT " + COLUNAS_PENDENCIA
                        + " FROM fluxo.pendencia WHERE episodio_id = ? AND status = 'ABERTA' ORDER BY criada_em FOR UPDATE")
            .param(episodioId)
            .query(RepositoriosJdbc::pendencia)
            .list();
    }

    @Override
    public void inserir(Pendencia p) {
        Responsavel r = p.responsavel();
        jdbc.sql("""
                INSERT INTO fluxo.pendencia (id, unidade_id, episodio_id, categoria, descricao, responsavel_usuario_id,
                                             responsavel_setor_id, responsavel_papel, prazo, criticidade_operacional)
                VALUES (?, ?, ?, CAST(? AS fluxo.categoria_bloqueio), ?, CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS fluxo.papel), ?, CAST(? AS fluxo.criticidade_operacional))
                """)
            .param(p.id()).param(p.unidadeId()).param(p.episodioId()).param(p.categoria().name()).param(p.descricao())
            .param(r instanceof Responsavel.Usuario u ? u.usuarioId().toString() : null)
            .param(r instanceof Responsavel.Setor s ? s.setorId().toString() : null)
            .param(r instanceof Responsavel.Perfil pf ? pf.papel().name() : null)
            .param(ts(p.prazo())).param(p.criticidade().name())
            .update();
    }

    /** Encerramento: autor e instante são definidos pelo banco (trigger), não pela aplicação. */
    @Override
    public void atualizar(Pendencia p) {
        Responsavel r = p.responsavel();
        int linhas = jdbc.sql("""
                UPDATE fluxo.pendencia
                   SET responsavel_usuario_id = CAST(? AS uuid), responsavel_setor_id = CAST(? AS uuid),
                       responsavel_papel = CAST(? AS fluxo.papel), prazo = ?,
                       status = CAST(? AS fluxo.status_pendencia), resolucao = CAST(? AS text),
                       versao = versao + 1
                 WHERE id = ? AND versao = ?
                """)
            .param(r instanceof Responsavel.Usuario u ? u.usuarioId().toString() : null)
            .param(r instanceof Responsavel.Setor s ? s.setorId().toString() : null)
            .param(r instanceof Responsavel.Perfil pf ? pf.papel().name() : null)
            .param(ts(p.prazo())).param(p.status().name()).param(p.resolucao())
            .param(p.id()).param(p.versao())
            .update();
        if (linhas != 1) {
            throw new ConflitoDeVersaoException();
        }
    }

    @Override
    public boolean setorExiste(UUID setorId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM fluxo.setor WHERE id = ? AND ativo)")
            .param(setorId).query(Boolean.class).single();
    }

    @Override
    public boolean especialidadeAtiva(UUID especialidadeId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM fluxo.especialidade WHERE id = ? AND ativa)")
            .param(especialidadeId).query(Boolean.class).single();
    }

    @Override
    public boolean usuarioLotadoNaUnidade(UUID usuarioId, UUID unidadeId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM fluxo.lotacao WHERE usuario_id = ? AND unidade_id = ?)")
            .param(usuarioId).param(unidadeId).query(Boolean.class).single();
    }

    // ---------------------------------------------------- linha do tempo e observações

    /** A unidade vem do próprio episódio (sem confiar em parâmetro do cliente). */
    @Override
    public void inserirEventos(List<EventoEpisodio> eventos) {
        for (EventoEpisodio ev : eventos) {
            int linhas = jdbc.sql("""
                    INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados, corrige_evento_id)
                    SELECT ?, e.unidade_id, e.id, ?, ?, CAST(? AS jsonb), CAST(? AS uuid)
                      FROM fluxo.episodio e WHERE e.id = ?
                    """)
                .param(ev.id()).param(ev.tipo().name()).param(ts(ev.ocorridoEm())).param(JsonPlano.de(ev.dados()))
                .param(ev.corrigeEventoId() == null ? null : ev.corrigeEventoId().toString())
                .param(ev.episodioId())
                .update();
            if (linhas != 1) {
                throw new IllegalStateException("evento sem episódio visível");
            }
        }
    }

    @Override
    public void inserirObservacao(UUID id, UUID episodioId, UUID unidadeId, String texto) {
        jdbc.sql("INSERT INTO fluxo.observacao (id, unidade_id, episodio_id, texto) VALUES (?, ?, ?, ?)")
            .param(id).param(unidadeId).param(episodioId).param(texto)
            .update();
    }

    @Override
    public Consultas consultas() {
        return consultas;
    }

    // ------------------------------------------------------------------ mapeamento

    private static Pendencia pendencia(ResultSet rs, int n) throws SQLException {
        Responsavel r;
        if (uuid(rs, 6) != null) {
            r = new Responsavel.Usuario(uuid(rs, 6));
        } else if (uuid(rs, 7) != null) {
            r = new Responsavel.Setor(uuid(rs, 7));
        } else {
            r = new Responsavel.Perfil(Papel.valueOf(rs.getString(8)));
        }
        return Pendencia.reconstituir(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), CategoriaBloqueio.valueOf(rs.getString(4)),
                rs.getString(5), r, instante(rs, 9), CriticidadeOperacional.valueOf(rs.getString(10)),
                StatusPendencia.valueOf(rs.getString(11)), instante(rs, 12), rs.getString(13), rs.getInt(14));
    }

    static UUID uuid(ResultSet rs, int i) throws SQLException {
        return rs.getObject(i, UUID.class);
    }

    static Instant instante(ResultSet rs, int i) throws SQLException {
        OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
