package br.fluxosaude.episodio.infra;

import static br.fluxosaude.episodio.infra.RepositoriosJdbc.instante;
import static br.fluxosaude.episodio.infra.RepositoriosJdbc.uuid;

import br.fluxosaude.episodio.aplicacao.Consultas;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Consultas de leitura (Torre, Caso, Painel). Filtros por parâmetros nomeados com CAST
 * explícito; ordenação só por fragmentos fixos (enum → SQL constante), nunca texto do cliente.
 */
final class ConsultasJdbc implements Consultas {

    /** Mapeamento fechado da ordenação (RF-010). "Mais tempo" = instante mais antigo. */
    private static final Map<Ordem, String> ORDEM_DESC = Map.of(
            Ordem.TEMPO_TOTAL, "e.entrada_em ASC",
            Ordem.TEMPO_NA_ETAPA, "e.etapa_desde ASC",
            Ordem.TEMPO_BLOQUEADO, "e.bloqueio_desde ASC NULLS LAST",
            Ordem.CRITICIDADE, "pa.maior_criticidade DESC NULLS LAST",
            Ordem.SETOR, "s.nome DESC",
            Ordem.ETAPA, "et.ordem DESC",
            Ordem.MOTIVO, "m.descricao DESC NULLS LAST",
            Ordem.PRAZO, "pa.proximo_prazo DESC NULLS LAST");
    private static final Map<Ordem, String> ORDEM_ASC = Map.of(
            Ordem.TEMPO_TOTAL, "e.entrada_em DESC",
            Ordem.TEMPO_NA_ETAPA, "e.etapa_desde DESC",
            Ordem.TEMPO_BLOQUEADO, "e.bloqueio_desde DESC NULLS LAST",
            Ordem.CRITICIDADE, "pa.maior_criticidade ASC NULLS LAST",
            Ordem.SETOR, "s.nome ASC",
            Ordem.ETAPA, "et.ordem ASC",
            Ordem.MOTIVO, "m.descricao ASC NULLS LAST",
            Ordem.PRAZO, "pa.proximo_prazo ASC NULLS LAST");

    static final String SELECT_LINHA = """
            SELECT e.id, e.versao, p.id, p.nome, s.id, s.nome, et.id, et.codigo, et.nome, et.natureza::text,
                   e.entrada_em, e.etapa_desde, m.id, m.codigo, m.descricao, m.categoria::text, e.bloqueio_desde,
                   e.protocolo_sistema, e.protocolo_numero, esp.nome,
                   coalesce(pa.abertas, 0), coalesce(pa.vencidas, 0), pa.proximo_prazo, pa.maior_criticidade::text
              FROM fluxo.episodio e
              JOIN fluxo.paciente p ON p.id = e.paciente_id
              JOIN fluxo.setor s ON s.id = e.setor_id
              JOIN fluxo.etapa et ON et.id = e.etapa_id
              LEFT JOIN fluxo.motivo_bloqueio m ON m.id = e.motivo_bloqueio_id
              LEFT JOIN fluxo.especialidade esp ON esp.id = e.especialidade_requerida_id
              LEFT JOIN LATERAL (
                    SELECT count(*) AS abertas,
                           count(*) FILTER (WHERE pd.prazo < clock_timestamp()) AS vencidas,
                           min(pd.prazo) AS proximo_prazo,
                           max(pd.criticidade_operacional) AS maior_criticidade
                      FROM fluxo.pendencia pd
                     WHERE pd.episodio_id = e.id AND pd.status = 'ABERTA') pa ON true
            """;

    private final JdbcClient jdbc;

    ConsultasJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LinhaTorre> torre(FiltroTorre f) {
        String ordem = (f.decrescente() ? ORDEM_DESC : ORDEM_ASC).get(f.ordem());
        return jdbc.sql(SELECT_LINHA + """
                 WHERE e.encerrado_em IS NULL
                   AND (CAST(:setor AS uuid) IS NULL OR e.setor_id = CAST(:setor AS uuid))
                   AND (CAST(:etapa AS uuid) IS NULL OR e.etapa_id = CAST(:etapa AS uuid))
                   AND (CAST(:motivo AS uuid) IS NULL OR e.motivo_bloqueio_id = CAST(:motivo AS uuid))
                   AND (CAST(:categoria AS text) IS NULL OR m.categoria::text = CAST(:categoria AS text))
                   AND (CAST(:especialidade AS uuid) IS NULL OR e.especialidade_requerida_id = CAST(:especialidade AS uuid))
                   AND (CAST(:responsavel AS uuid) IS NULL OR EXISTS (
                          SELECT 1 FROM fluxo.pendencia px
                           WHERE px.episodio_id = e.id AND px.status = 'ABERTA'
                             AND px.responsavel_usuario_id = CAST(:responsavel AS uuid)))
                   AND (CAST(:minutos AS integer) IS NULL
                        OR e.etapa_desde <= clock_timestamp() - make_interval(mins => CAST(:minutos AS integer)))
                   AND (CAST(:vencidas AS boolean) IS NOT TRUE OR coalesce(pa.vencidas, 0) > 0)
                """ + " ORDER BY " + ordem + ", e.id LIMIT :limite")
            .param("setor", texto(f.setorId()))
            .param("etapa", texto(f.etapaId()))
            .param("motivo", texto(f.motivoId()))
            .param("categoria", f.categoriaBloqueio())
            .param("especialidade", texto(f.especialidadeId()))
            .param("responsavel", texto(f.responsavelUsuarioId()))
            .param("minutos", f.minutosMinimosNaEtapa())
            .param("vencidas", f.somenteComPendenciaVencida())
            .param("limite", f.limite())
            .query(ConsultasJdbc::linha)
            .list();
    }

    @Override
    public Optional<Caso> caso(UUID episodioId) {
        Optional<LinhaTorre> resumo = jdbc.sql(SELECT_LINHA + " WHERE e.id = :id")
            .param("id", episodioId)
            .query(ConsultasJdbc::linha)
            .optional();
        if (resumo.isEmpty()) {
            return Optional.empty();
        }
        record Complemento(String cns, String identificador, java.time.LocalDate nascimento, String destino,
                           String motivoDetalhe, String desfecho, java.time.Instant encerradoEm,
                           String justificativaEncerramento, String justificativaDuplicidade) {
        }
        Complemento c = jdbc.sql("""
                SELECT p.cns, p.identificador_institucional, p.data_nascimento, e.destino_descricao, e.motivo_detalhe,
                       e.desfecho::text, e.encerrado_em, e.justificativa_encerramento, e.justificativa_duplicidade
                  FROM fluxo.episodio e JOIN fluxo.paciente p ON p.id = e.paciente_id
                 WHERE e.id = :id
                """)
            .param("id", episodioId)
            .query((rs, n) -> new Complemento(rs.getString(1), rs.getString(2),
                    rs.getObject(3, java.time.LocalDate.class), rs.getString(4), rs.getString(5), rs.getString(6),
                    instante(rs, 7), rs.getString(8), rs.getString(9)))
            .single();
        List<LinhaPendencia> pendencias = jdbc.sql("""
                SELECT pd.id, pd.versao, pd.categoria::text, pd.descricao, pd.responsavel_usuario_id, u.nome,
                       pd.responsavel_setor_id, s.nome, pd.responsavel_papel::text, pd.prazo,
                       pd.criticidade_operacional::text, pd.status::text, pd.criada_em, pd.resolucao, pd.encerrada_em
                  FROM fluxo.pendencia pd
                  LEFT JOIN fluxo.usuario u ON u.id = pd.responsavel_usuario_id
                  LEFT JOIN fluxo.setor s ON s.id = pd.responsavel_setor_id
                 WHERE pd.episodio_id = :id
                 ORDER BY (pd.status = 'ABERTA') DESC, (CASE WHEN pd.status = 'ABERTA' THEN pd.prazo END),
                          pd.encerrada_em DESC NULLS LAST, pd.id
                 LIMIT :limite
                """)
            .param("id", episodioId)
            .param("limite", MAX_PENDENCIAS_CASO + 1)
            .query((rs, n) -> new LinhaPendencia(uuid(rs, 1), rs.getInt(2), rs.getString(3), rs.getString(4),
                    uuid(rs, 5), rs.getString(6), uuid(rs, 7), rs.getString(8), rs.getString(9), instante(rs, 10),
                    rs.getString(11), rs.getString(12), instante(rs, 13), rs.getString(14), instante(rs, 15)))
            .list();
        List<LinhaEvento> eventos = jdbc.sql("""
                SELECT ev.id, ev.tipo, ev.ocorrido_em, ev.registrado_em, ev.autor_id, u.nome, ev.dados::text,
                       ev.corrige_evento_id
                  FROM fluxo.evento_episodio ev
                  LEFT JOIN fluxo.usuario u ON u.id = ev.autor_id
                 WHERE ev.episodio_id = :id
                 ORDER BY ev.ocorrido_em DESC, ev.registrado_em DESC, ev.id DESC
                 LIMIT :limite
                """)
            .param("id", episodioId)
            .param("limite", MAX_EVENTOS_CASO + 1)
            .query((rs, n) -> new LinhaEvento(uuid(rs, 1), rs.getString(2), instante(rs, 3), instante(rs, 4),
                    uuid(rs, 5), rs.getString(6), rs.getString(7), uuid(rs, 8)))
            .list();
        List<Observacao> observacoes = jdbc.sql("""
                SELECT o.id, o.texto, o.autor_id, u.nome, o.registrada_em
                  FROM fluxo.observacao o
                  LEFT JOIN fluxo.usuario u ON u.id = o.autor_id
                 WHERE o.episodio_id = :id
                 ORDER BY o.registrada_em DESC, o.id DESC
                 LIMIT :limite
                """)
            .param("id", episodioId)
            .param("limite", MAX_OBSERVACOES_CASO + 1)
            .query((rs, n) -> new Observacao(uuid(rs, 1), rs.getString(2), uuid(rs, 3), rs.getString(4), instante(rs, 5)))
            .list();
        boolean truncado = pendencias.size() > MAX_PENDENCIAS_CASO || eventos.size() > MAX_EVENTOS_CASO
                || observacoes.size() > MAX_OBSERVACOES_CASO;
        return Optional.of(new Caso(resumo.get(), c.cns(), c.identificador(), c.nascimento(), c.destino(),
                c.motivoDetalhe(), c.desfecho(), c.encerradoEm(), c.justificativaEncerramento(),
                c.justificativaDuplicidade(), limitar(pendencias, MAX_PENDENCIAS_CASO, false),
                limitar(eventos, MAX_EVENTOS_CASO, true), limitar(observacoes, MAX_OBSERVACOES_CASO, true), truncado));
    }

    @Override
    public List<LinhaPainel> painel(int limite) {
        return jdbc.sql(SELECT_LINHA + """
                 WHERE e.encerrado_em IS NULL
                 ORDER BY e.etapa_desde ASC, e.id
                 LIMIT :limite
                """)
            .param("limite", limite)
            .query((rs, n) -> new LinhaPainel(uuid(rs, 1), rs.getString(4), rs.getString(6), rs.getString(9),
                    rs.getString(10), instante(rs, 11), instante(rs, 12), rs.getString(16), instante(rs, 17), rs.getInt(22)))
            .list();
    }

    @Override
    public void registrarConsultaDeCaso(UUID episodioId, UUID unidadeId) {
        jdbc.sql("SELECT auditoria.registrar('CONSULTA_CASO', 'fluxo.episodio', :recurso, '{}'::jsonb, CAST(:unidade AS uuid))")
            .param("recurso", episodioId.toString())
            .param("unidade", unidadeId.toString())
            .query(Long.class)
            .single();
    }

    @Override
    public Catalogo catalogo(boolean incluirProfissionais) {
        UnidadeInfo unidade = jdbc.sql("""
                SELECT id, codigo, nome, fuso_horario FROM fluxo.unidade
                 WHERE id = ANY (fluxo.ctx_unidades()) ORDER BY id LIMIT 1
                """)
            .query((rs, n) -> new UnidadeInfo(uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4)))
            .single();
        List<SetorInfo> setores = jdbc.sql("SELECT id, codigo, nome, ativo FROM fluxo.setor ORDER BY nome, id")
            .query((rs, n) -> new SetorInfo(uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
            .list();
        List<EtapaInfo> etapas = jdbc.sql("""
                SELECT id, codigo, nome, ordem, natureza::text, desfecho::text, inicial, exige_motivo_bloqueio,
                       exige_protocolo_externo, exige_justificativa, ativa
                  FROM fluxo.etapa ORDER BY ordem, nome, id
                """)
            .query((rs, n) -> new EtapaInfo(uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getInt(4),
                    rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9),
                    rs.getBoolean(10), rs.getBoolean(11)))
            .list();
        List<TransicaoInfo> transicoes = jdbc.sql("SELECT origem_id, destino_id FROM fluxo.transicao_etapa")
            .query((rs, n) -> new TransicaoInfo(uuid(rs, 1), uuid(rs, 2)))
            .list();
        List<MotivoInfo> motivos = jdbc.sql("""
                SELECT id, categoria::text, codigo, descricao, exige_detalhe, ativo
                  FROM fluxo.motivo_bloqueio ORDER BY categoria, descricao, id
                """)
            .query((rs, n) -> new MotivoInfo(uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getBoolean(5), rs.getBoolean(6)))
            .list();
        List<EspecialidadeInfo> especialidades = jdbc.sql(
                "SELECT id, codigo, nome FROM fluxo.especialidade WHERE ativa ORDER BY nome, id")
            .query((rs, n) -> new EspecialidadeInfo(uuid(rs, 1), rs.getString(2), rs.getString(3)))
            .list();
        List<Profissional> profissionais = !incluirProfissionais ? List.of() : jdbc.sql("""
                SELECT DISTINCT u.id, u.nome
                  FROM fluxo.usuario u
                  JOIN fluxo.lotacao l ON l.usuario_id = u.id AND l.unidade_id = ANY (fluxo.ctx_unidades())
                 WHERE u.ativo
                 ORDER BY u.nome, u.id
                """)
            .query((rs, n) -> new Profissional(uuid(rs, 1), rs.getString(2)))
            .list();
        return new Catalogo(unidade, setores, etapas, transicoes, motivos, especialidades, profissionais);
    }

    @Override
    public List<PacienteEncontrado> pacientes(String cns, String identificador) {
        return jdbc.sql("""
                SELECT p.id, p.nome, p.data_nascimento, p.reconciliado_com_id IS NOT NULL, p.reconciliado_com_id,
                       EXISTS (SELECT 1 FROM fluxo.episodio e WHERE e.paciente_id = p.id AND e.encerrado_em IS NULL)
                  FROM fluxo.paciente p
                 WHERE (CAST(:cns AS text) IS NOT NULL AND p.cns = CAST(:cns AS text))
                    OR (CAST(:ident AS text) IS NOT NULL AND p.identificador_institucional = CAST(:ident AS text))
                 ORDER BY p.nome, p.id
                 LIMIT 10
                """)
            .param("cns", cns)
            .param("ident", identificador)
            .query((rs, n) -> new PacienteEncontrado(uuid(rs, 1), rs.getString(2),
                    rs.getObject(3, java.time.LocalDate.class), rs.getBoolean(4), uuid(rs, 5), rs.getBoolean(6)))
            .list();
    }

    @Override
    public void registrarConsultaDePaciente(UUID pacienteId, UUID unidadeId) {
        jdbc.sql("SELECT auditoria.registrar('CONSULTA_PACIENTE', 'fluxo.paciente', :recurso, '{}'::jsonb, CAST(:unidade AS uuid))")
            .param("recurso", pacienteId.toString())
            .param("unidade", unidadeId.toString())
            .query(Long.class)
            .single();
    }

    private static LinhaTorre linha(ResultSet rs, int n) throws SQLException {
        return new LinhaTorre(uuid(rs, 1), rs.getInt(2), uuid(rs, 3), rs.getString(4), uuid(rs, 5), rs.getString(6),
                uuid(rs, 7), rs.getString(8), rs.getString(9), rs.getString(10), instante(rs, 11), instante(rs, 12),
                uuid(rs, 13), rs.getString(14), rs.getString(15), rs.getString(16), instante(rs, 17),
                rs.getString(18), rs.getString(19), rs.getString(20), rs.getInt(21), rs.getInt(22),
                instante(rs, 23), rs.getString(24));
    }

    /** Mantém os {@code max} primeiros; se a consulta veio do mais recente, devolve em ordem cronológica. */
    private static <T> List<T> limitar(List<T> lista, int max, boolean inverter) {
        List<T> recorte = new java.util.ArrayList<>(lista.subList(0, Math.min(max, lista.size())));
        if (inverter) {
            java.util.Collections.reverse(recorte);
        }
        return List.copyOf(recorte);
    }

    private static String texto(UUID id) {
        return id == null ? null : id.toString();
    }
}
