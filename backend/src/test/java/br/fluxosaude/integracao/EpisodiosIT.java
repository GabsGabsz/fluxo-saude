package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cenário da ERS §11 de ponta a ponta (HTTP → serviço → PostgreSQL com RLS e auditoria):
 * entrada 08:12, decisão 10:02, solicitação 10:14, aceite no dia seguinte 11:20 (25h06),
 * saída 13:10 (1h50 após o aceite). Os horários até o transporte são lançados como AJUSTE
 * MANUAL (fato ocorrido antes do registro, RNF-017); a saída é carimbada pelo relógio do
 * servidor, como no uso real — a cronologia do episódio não admite fato anterior ao último
 * registro (pendências e observação são lançadas "agora"). Cobre ainda: controle
 * otimista (409), isolamento entre unidades (404), perfis (403), painel pseudonimizado,
 * pendências encerradas pelo desfecho, observação e auditoria da consulta do caso.
 */
class EpisodiosIT extends IntegracaoBase {

    static final String SENHA = "frase secreta do cenario onze";
    static final String COORDENACAO = "coord.cenario";
    static final String DIRECAO = "direcao.cenario";
    static final String OUTRA_UNIDADE = "coord.outra.unidade";
    static final String NOME_PACIENTE = "Paciente Cenario Onze";
    static final String PRONTUARIO = "PRONT-0011";

    static UUID unidade;
    static UUID setor;
    static UUID setorOutraUnidade;
    private static final AtomicBoolean PREPARADO = new AtomicBoolean();

    @Autowired
    Environment env;

    /** Garante que as migrações rodaram antes da preparação (ver SessaoIT). */
    @Autowired
    Flyway flyway;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void prepararUmaVez() throws Exception {
        if (PREPARADO.compareAndSet(false, true)) {
            preparar();
        }
    }

    private static void preparar() throws Exception {
        String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
        try (Connection c = conexaoDono()) {
            c.setAutoCommit(false);
            // Retroatividade ampliada (máx. do banco: 7 dias) para lançar o cenário de ~29 h.
            unidade = criarUnidade(c, "UPA_CENARIO", "interval '7 days'");
            UUID outra = criarUnidade(c, "UPA_OUTRA", "interval '24 hours'");
            setor = criarSetor(c, unidade, "OBSERVACAO_ADULTO", "Observação adulto");
            setorOutraUnidade = criarSetor(c, outra, "OBSERVACAO", "Observação");
            criarUsuario(c, COORDENACAO, "Coordenação Cenário", hash, unidade, "COORDENACAO_FLUXO");
            criarUsuario(c, DIRECAO, "Direção Cenário", hash, unidade, "DIRECAO");
            criarUsuario(c, OUTRA_UNIDADE, "Coordenação Outra", hash, outra, "COORDENACAO_FLUXO");
            c.commit();
        }
    }

    @Test
    void cenarioDaErsDePontaAPonta() throws Exception {
        ClienteHttp coord = cliente(COORDENACAO);
        // "08:12" escolhido para que a saída prevista (13:10 do dia seguinte) seja "agora".
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(28).plusMinutes(58));

        // Abertura com novo cadastro (horário retroativo justificado)
        HttpResponse<String> aberto = exigir(201, coord.enviar("POST", "/api/episodios", """
                {"novoPaciente":{"nome":"%s","identificadorInstitucional":"%s"},
                 "setorId":"%s","momento":%s}""".formatted(NOME_PACIENTE, PRONTUARIO, setor, momento(t0))));
        JsonNode r = json.readTree(aberto.body());
        UUID episodio = UUID.fromString(texto(r.get("id")));
        assertEquals(0, r.get("versao").asInt());
        assertTrue(aberto.headers().firstValue("Location").orElse("").endsWith("/api/episodios/" + episodio));

        // RF-003: o mesmo identificador não gera segundo cadastro (usa-se o existente)
        exigir(422, coord.enviar("POST", "/api/episodios", """
                {"novoPaciente":{"nome":"%s","identificadorInstitucional":"%s"},"setorId":"%s"}"""
                .formatted(NOME_PACIENTE, PRONTUARIO, setor)));

        Instant decisao = t0.plus(Duration.ofMinutes(110));                     // 10:02
        Instant solicitacao = t0.plus(Duration.ofMinutes(122));                 // 10:14
        Instant semLeito = solicitacao.plus(Duration.ofHours(24));              // 10:14 (+1 dia)
        Instant aceite = semLeito.plus(Duration.ofMinutes(66));                 // 11:20
        Instant transporte = aceite.plus(Duration.ofMinutes(2));                // 11:22

        int v = mudarEtapa(coord, episodio, 0, "AGUARDANDO_SOLICITACAO_TRANSFERENCIA", "SOLICITACAO_NAO_ENVIADA",
                "", decisao);
        assertEquals(1, v);

        // RF-036: versão desatualizada → 409, nada é sobrescrito
        HttpResponse<String> conflito = coord.enviar("PUT", "/api/episodios/" + episodio + "/etapa",
                corpoEtapa(0, "EM_ATENDIMENTO", null, "", null));
        exigir(409, conflito);
        assertTrue(conflito.body().contains("CONFLITO_DE_VERSAO"));

        // Transferência solicitada exige protocolo externo (RF-009)
        exigir(422, coord.enviar("PUT", "/api/episodios/" + episodio + "/etapa",
                corpoEtapa(v, "TRANSFERENCIA_SOLICITADA", "AGUARDANDO_ANALISE_ACEITE", "", solicitacao)));
        v = mudarEtapa(coord, episodio, v, "TRANSFERENCIA_SOLICITADA", "AGUARDANDO_ANALISE_ACEITE",
                ",\"protocoloSistema\":\"REGULA_PI\",\"protocoloNumero\":\"2026-000123\"", solicitacao);
        v = mudarEtapa(coord, episodio, v, "AGUARDANDO_RECURSO_LEITO", "SEM_LEITO_ESPECIALIDADE", "", semLeito);
        v = mudarEtapa(coord, episodio, v, "ACEITO", null, "", aceite);
        v = mudarEtapa(coord, episodio, v, "AGUARDANDO_TRANSPORTE", "TRANSPORTE_PENDENTE", "", transporte);

        // Observação operacional (RF-028)
        exigir(201, coord.enviar("POST", "/api/episodios/" + episodio + "/observacoes",
                "{\"texto\":\"Familia informada sobre a transferencia.\"}"));

        // Pendências: uma para o setor, outra para a própria coordenação (reatribuída e resolvida)
        Instant prazo = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        exigir(201, coord.enviar("POST", "/api/episodios/" + episodio + "/pendencias", """
                {"categoria":"LOGISTICA","descricao":"Confirmar ambulancia com a central",
                 "responsavel":{"setorId":"%s"},"prazo":"%s","criticidade":"ALTA"}""".formatted(setor, prazo)));
        UUID coordId = idUsuario(COORDENACAO);
        JsonNode p2 = json.readTree(exigir(201, coord.enviar("POST", "/api/episodios/" + episodio + "/pendencias", """
                {"categoria":"ADMINISTRATIVO","descricao":"Copia do protocolo para a equipe de transporte",
                 "responsavel":{"usuarioId":"%s"},"prazo":"%s","criticidade":"MEDIA"}""".formatted(coordId, prazo)))
                .body());
        String p2Id = texto(p2.get("id"));
        JsonNode p2v1 = json.readTree(exigir(200, coord.enviar("PATCH", "/api/pendencias/" + p2Id,
                "{\"versao\":0,\"prazo\":\"" + prazo.plus(Duration.ofHours(1)) + "\"}")).body());
        exigir(200, coord.enviar("POST", "/api/pendencias/" + p2Id + "/resolucao",
                "{\"versao\":" + p2v1.get("versao").asInt() + ",\"texto\":\"Copia entregue ao motorista\"}"));
        // Responsável de OUTRA unidade é recusado (não vaza existência: regra genérica)
        exigir(422, coord.enviar("POST", "/api/episodios/" + episodio + "/pendencias", """
                {"categoria":"LOGISTICA","descricao":"Pendencia invalida","responsavel":{"setorId":"%s"},
                 "prazo":"%s","criticidade":"BAIXA"}""".formatted(setorOutraUnidade, prazo)));
        // Mais de um responsável → 422
        exigir(422, coord.enviar("POST", "/api/episodios/" + episodio + "/pendencias", """
                {"categoria":"LOGISTICA","descricao":"Pendencia invalida","responsavel":{"setorId":"%s","papel":"MEDICO"},
                 "prazo":"%s","criticidade":"BAIXA"}""".formatted(setor, prazo)));

        // Torre: filtros e ordenação por lista fechada
        assertTrue(torre(coord, "?categoria=LOGISTICA&ordem=PRAZO").contains(episodio.toString()));
        assertFalse(torre(coord, "?categoria=REGULACAO").contains(episodio.toString()));
        assertFalse(torre(coord, "?somenteVencidas=true").contains(episodio.toString()));
        exigir(400, coord.enviar("GET", "/api/episodios?ordem=nome;DROP", null));
        exigir(400, coord.enviar("GET", "/api/episodios?limite=0", null));
        exigir(400, coord.enviar("GET", "/api/episodios?setor=nao-e-uuid", null));
        exigir(400, coord.enviar("GET", "/api/episodios/nao-e-uuid", null));
        exigir(404, coord.enviar("GET", "/api/episodios/" + UUID.randomUUID(), null));

        // Isolamento entre unidades: para a outra unidade o episódio "não existe"
        ClienteHttp outra = cliente(OUTRA_UNIDADE);
        exigir(404, outra.enviar("GET", "/api/episodios/" + episodio, null));
        exigir(404, outra.enviar("PUT", "/api/episodios/" + episodio + "/etapa",
                corpoEtapa(v, "EM_ATENDIMENTO", null, "", null)));
        assertFalse(torre(outra, "").contains(episodio.toString()));

        // Direção: sem acesso nominal; painel coletivo pseudonimizado (RNF-015)
        ClienteHttp direcao = cliente(DIRECAO);
        exigir(403, direcao.enviar("GET", "/api/episodios", null));
        exigir(403, direcao.enviar("GET", "/api/episodios/" + episodio, null));
        String painel = exigir(200, direcao.enviar("GET", "/api/painel", null)).body();
        assertTrue(json.readTree(painel).get("itens").size() >= 1);
        assertFalse(painel.contains(NOME_PACIENTE), "painel não expõe o nome");
        assertFalse(painel.contains(PRONTUARIO), "painel não expõe identificadores");
        assertFalse(painel.contains(episodio.toString()), "painel não expõe o ID do episódio");

        // Saída "agora" (relógio do servidor): encerra o episódio e as pendências abertas (RN-008)
        v = mudarEtapa(coord, episodio, v, "TRANSFERIDO", null, "", null);
        exigir(422, coord.enviar("PUT", "/api/episodios/" + episodio + "/motivo",
                "{\"versao\":" + v + ",\"motivoId\":\"" + idMotivo("SEM_VAGA") + "\"}"));
        exigir(422, coord.enviar("POST", "/api/episodios/" + episodio + "/observacoes", "{\"texto\":\"Depois do fim\"}"));
        assertFalse(torre(coord, "").contains(episodio.toString()), "encerrado sai da Torre");

        // Caso completo: tempos da ERS reproduzidos a partir do que o servidor gravou
        JsonNode caso = json.readTree(exigir(200, coord.enviar("GET", "/api/episodios/" + episodio, null)).body());
        assertEquals("TRANSFERENCIA", texto(caso.get("desfecho")));
        Instant saida = Instant.parse(texto(caso.get("encerradoEm")));
        assertEquals(t0, Instant.parse(texto(caso.get("resumo").get("entradaEm"))));
        assertEquals("2026-000123", texto(caso.get("resumo").get("protocoloNumero")));
        Set<Instant> etapas = new HashSet<>();
        boolean ajusteMarcado = false;
        for (JsonNode ev : caso.get("linhaDoTempo")) {
            if ("ETAPA_ALTERADA".equals(texto(ev.get("tipo")))) {
                etapas.add(Instant.parse(texto(ev.get("ocorridoEm"))));
            }
            ajusteMarcado |= ev.get("dados").has("ajuste_manual");
        }
        assertTrue(etapas.containsAll(Set.of(decisao, solicitacao, semLeito, aceite, transporte, saida)), etapas::toString);
        // Saída prevista = 13:10 ("agora" no início do teste); a real é alguns segundos depois.
        Duration atrasoDoTeste = Duration.between(transporte.plus(Duration.ofMinutes(108)), saida);
        assertFalse(atrasoDoTeste.isNegative(), "saída carimbada pelo servidor, não antes do previsto");
        assertTrue(atrasoDoTeste.compareTo(Duration.ofMinutes(5)) < 0, "relógio do servidor coerente");
        assertTrue(ajusteMarcado, "horários retroativos marcados como ajuste manual na linha do tempo");
        assertEquals(Duration.ofHours(25).plusMinutes(6), Duration.between(solicitacao, aceite));
        assertEquals(2, caso.get("pendencias").size());
        for (JsonNode p : caso.get("pendencias")) {
            assertFalse("ABERTA".equals(texto(p.get("status"))), "nenhuma pendência aberta após o desfecho");
        }
        assertEquals(1, caso.get("observacoes").size());

        // Auditoria: consulta do caso registrada e cadeia íntegra
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FILTER (WHERE acao = 'CONSULTA_CASO' AND recurso_id = ?),
                            (SELECT count(*) FROM auditoria.verificar_cadeia())
                       FROM auditoria.registro""")) {
            ps.setString(1, episodio.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getLong(1) >= 1, "consulta nominal do caso auditada");
                assertEquals(0, rs.getLong(2), "cadeia de auditoria íntegra");
            }
        }
    }

    // ----------------------------------------------------------------------------

    private ClienteHttp cliente(String login) throws Exception {
        ClienteHttp c = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        c.entrar(login, SENHA);
        return c;
    }

    private int mudarEtapa(ClienteHttp c, UUID episodio, int versao, String etapa, String motivo, String extra,
                           Instant quando) throws Exception {
        HttpResponse<String> r = exigir(200, c.enviar("PUT", "/api/episodios/" + episodio + "/etapa",
                corpoEtapa(versao, etapa, motivo, extra, quando)));
        int nova = json.readTree(r.body()).get("versao").asInt();
        assertEquals(versao + 1, nova, "cada alteração incrementa a versão");
        return nova;
    }

    private static String corpoEtapa(int versao, String etapa, String motivo, String extra, Instant quando)
            throws Exception {
        return "{\"versao\":" + versao + ",\"etapaId\":\"" + idEtapa(etapa) + "\""
                + (motivo == null ? "" : ",\"motivoId\":\"" + idMotivo(motivo) + "\"")
                + extra
                + (quando == null ? "" : ",\"momento\":" + momento(quando))
                + "}";
    }

    private static String momento(Instant quando) {
        return "{\"ocorridoEm\":\"" + quando + "\",\"justificativaAjuste\":\"Lancamento retroativo do cenario\"}";
    }

    private String torre(ClienteHttp c, String filtros) throws Exception {
        return exigir(200, c.enviar("GET", "/api/episodios" + filtros, null)).body();
    }

    /** Valor textual de um nó JSON (independe de mudanças de nome da API entre versões do Jackson). */
    private static String texto(JsonNode n) {
        String s = n.toString();
        return s.length() >= 2 && s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static UUID idEtapa(String codigo) throws Exception {
        return id("SELECT id FROM fluxo.etapa WHERE unidade_id = ? AND codigo = ?", codigo);
    }

    private static UUID idMotivo(String codigo) throws Exception {
        return id("SELECT id FROM fluxo.motivo_bloqueio WHERE unidade_id = ? AND codigo = ?", codigo);
    }

    private static UUID id(String sql, String codigo) throws Exception {
        try (Connection c = conexaoDono(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, unidade);
            ps.setString(2, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "cadastro inexistente: " + codigo);
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static UUID idUsuario(String login) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM fluxo.usuario WHERE login = ?")) {
            ps.setString(1, login);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static UUID criarUnidade(Connection c, String codigo, String retroatividade) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fluxo.unidade (id, codigo, nome, tipo, "
                + "retroatividade_maxima) VALUES (?, ?, 'UPA de teste', 'UPA', " + retroatividade + ")")) {
            ps.setObject(1, id);
            ps.setString(2, codigo);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT fluxo.provisionar_unidade(?)")) {
            ps.setObject(1, id);
            ps.execute();
        }
        return id;
    }

    private static UUID criarSetor(Connection c, UUID unidadeId, String codigo, String nome) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, unidadeId);
            ps.setString(3, codigo);
            ps.setString(4, nome);
            ps.executeUpdate();
        }
        return id;
    }

    private static void criarUsuario(Connection c, String login, String nome, String hash, UUID unidadeId,
                                     String papel) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fluxo.usuario (id, login, nome, senha_hash, "
                + "deve_trocar_senha) VALUES (?, ?, ?, ?, false)")) {
            ps.setObject(1, id);
            ps.setString(2, login);
            ps.setString(3, nome);
            ps.setString(4, hash);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, CAST(? AS fluxo.papel))")) {
            ps.setObject(1, id);
            ps.setObject(2, unidadeId);
            ps.setString(3, papel);
            ps.executeUpdate();
        }
    }
}
