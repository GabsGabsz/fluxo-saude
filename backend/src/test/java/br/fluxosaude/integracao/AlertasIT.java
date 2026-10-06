package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * Alertas e "Pacientes travados" de ponta a ponta (HTTP → serviço → PostgreSQL com RLS):
 * regras configuradas só pelo Administrador, casos que violam/não violam, episódio encerrado
 * fora do painel, destaque na Torre, painel coletivo sem nomes, ciência sem encerrar
 * pendência, desativação de regra, permissões, isolamento entre unidades e auditoria.
 * As fronteiras exatas de tempo são testadas com relógio controlado em MotorDeAlertasTest;
 * aqui os tempos ficam longe das fronteiras (lançados como ajuste manual, RNF-017).
 */
class AlertasIT extends IntegracaoBase {

    static final String SENHA = "frase secreta dos alertas da upa";
    static final String ADM = "adm.alertas";
    static final String COORD = "coord.alertas";
    static final String DIRECAO = "direcao.alertas";
    static final String OUTRA = "coord.outra.alertas";

    static UUID unidade;
    static UUID setor;
    private static final AtomicBoolean PREPARADO = new AtomicBoolean();

    @Autowired
    Environment env;

    @Autowired
    Flyway flyway;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void prepararUmaVez() throws Exception {
        if (PREPARADO.compareAndSet(false, true)) {
            String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
            try (Connection c = conexaoDono()) {
                c.setAutoCommit(false);
                unidade = criarUnidade(c, "UPA_ALERTAS");
                UUID outra = criarUnidade(c, "UPA_ALERTAS_B");
                setor = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES (?, ?, 'OBS', 'Observacao')")) {
                    ps.setObject(1, setor);
                    ps.setObject(2, unidade);
                    ps.executeUpdate();
                }
                criarUsuario(c, ADM, hash, unidade, "ADMINISTRADOR");
                criarUsuario(c, COORD, hash, unidade, "COORDENACAO_FLUXO");
                criarUsuario(c, DIRECAO, hash, unidade, "DIRECAO");
                criarUsuario(c, OUTRA, hash, outra, "COORDENACAO_FLUXO");
                c.commit();
            }
        }
    }

    @Test
    void travadosDePontaAPonta() throws Exception {
        ClienteHttp adm = cliente(ADM);
        ClienteHttp coord = cliente(COORD);
        ClienteHttp direcao = cliente(DIRECAO);
        ClienteHttp outra = cliente(OUTRA);

        // ------------------------------------------------------------ sem regras, ninguém travado (RN-014)
        assertEquals(0, json.readTree(exigir(200, coord.enviar("GET", "/api/travados", null)).body()).get("itens").size());

        // ------------------------------------------------------------ configuração (só Administrador)
        exigir(403, coord.enviar("POST", "/api/config/regras-alerta",
                "{\"nome\":\"Tempo total\",\"tipo\":\"TEMPO_TOTAL\",\"limiteMinutos\":60}"));
        exigir(422, adm.enviar("POST", "/api/config/regras-alerta", "{\"nome\":\"Sem limite\",\"tipo\":\"TEMPO_TOTAL\"}"));
        exigir(400, adm.enviar("POST", "/api/config/regras-alerta",
                "{\"nome\":\"Zero\",\"tipo\":\"TEMPO_TOTAL\",\"limiteMinutos\":0}"));
        JsonNode rTransporte = json.readTree(exigir(201, adm.enviar("POST", "/api/config/regras-alerta", """
                {"nome":"Transporte atrasado (ilustrativo)","tipo":"TEMPO_NA_ETAPA","etapaId":"%s","limiteMinutos":120,
                 "acaoEsperada":"Acionar a central de transporte"}""".formatted(idEtapa("AGUARDANDO_TRANSPORTE")))).body());
        JsonNode rPendencia = json.readTree(exigir(201, adm.enviar("POST", "/api/config/regras-alerta",
                "{\"nome\":\"Pendencia vencida\",\"tipo\":\"PENDENCIA_VENCIDA\"}")).body());
        JsonNode rTotal = json.readTree(exigir(201, adm.enviar("POST", "/api/config/regras-alerta",
                "{\"nome\":\"Permanencia total (ilustrativo)\",\"tipo\":\"TEMPO_TOTAL\",\"limiteMinutos\":1440}")).body());
        assertEquals(3, json.readTree(exigir(200, coord.enviar("GET", "/api/config/regras-alerta", null)).body()).size(),
                "quem acompanha os casos lê as regras");
        assertEquals(0, json.readTree(exigir(200, outra.enviar("GET", "/api/config/regras-alerta", null)).body()).size(),
                "outra unidade não vê as regras");

        // ------------------------------------------------------------ casos
        Instant agora = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // E1: em "aguardando transporte" há 3 h (limite 2 h) → travado
        UUID e1 = abrir(coord, "Paciente Transporte Atrasado", agora.minus(Duration.ofHours(5)));
        int v1 = ateTransporte(coord, e1, agora.minus(Duration.ofHours(5)), agora.minus(Duration.ofHours(3)));
        // E2: em "aguardando transporte" há 1 h → não travado
        UUID e2 = abrir(coord, "Paciente Transporte No Prazo", agora.minus(Duration.ofHours(3)));
        ateTransporte(coord, e2, agora.minus(Duration.ofHours(3)), agora.minus(Duration.ofHours(1)));
        // E3: aberto há 30 h (limite 24 h) → travado
        UUID e3 = abrir(coord, "Paciente Permanencia Longa", agora.minus(Duration.ofHours(30)));
        // E4: pendência com prazo já vencido (dentro da tolerância de registro) → travado
        UUID e4 = abrir(coord, "Paciente Pendencia Vencida", agora.minus(Duration.ofMinutes(30)));
        Instant prazo = Instant.now().minus(Duration.ofMinutes(3)).truncatedTo(ChronoUnit.SECONDS);
        UUID pend = UUID.fromString(texto(json.readTree(exigir(201, coord.enviar("POST", "/api/episodios/" + e4 + "/pendencias", """
                {"categoria":"LOGISTICA","descricao":"Confirmar ambulancia","responsavel":{"setorId":"%s"},
                 "prazo":"%s","criticidade":"ALTA"}""".formatted(setor, prazo))).body()).get("id")));
        // E5: aberto há 30 h mas ENCERRADO → fora do painel (RN-008)
        UUID e5 = abrir(coord, "Paciente Encerrado", agora.minus(Duration.ofHours(30)));
        exigir(200, coord.enviar("PUT", "/api/episodios/" + e5 + "/etapa",
                "{\"versao\":0,\"etapaId\":\"" + idEtapa("ALTA") + "\"}"));

        // ------------------------------------------------------------ painel "Pacientes travados" (CA-06)
        JsonNode travados = json.readTree(exigir(200, coord.enviar("GET", "/api/travados", null)).body());
        List<String> ids = new ArrayList<>();
        travados.get("itens").forEach(i -> ids.add(texto(i.get("episodioId"))));
        assertEquals(List.of(e3.toString(), e1.toString(), e4.toString()), ids,
                "só os que violam regra, do limite atingido há mais tempo ao mais recente");
        JsonNode caso1 = item(travados, e1);
        assertEquals("Paciente Transporte Atrasado", texto(caso1.get("pacienteNome")));
        assertEquals("Ambulância/transporte pendente", texto(caso1.get("motivoBloqueio")), "motivo");
        JsonNode alerta1 = caso1.get("alertas").get(0);
        assertEquals(texto(rTransporte.get("id")), texto(alerta1.get("regraId")));
        assertEquals("Acionar a central de transporte", texto(alerta1.get("acaoEsperada")), "ação esperada");
        assertEquals(agora.minus(Duration.ofHours(3)), Instant.parse(texto(alerta1.get("referenciaEm"))), "tempo na etapa");
        assertEquals(agora.minus(Duration.ofHours(1)), Instant.parse(texto(alerta1.get("atingidoEm"))));
        JsonNode semCiencia = alerta1.get("ciencia");
        assertTrue(semCiencia == null || "null".equals(semCiencia.toString()), "ainda sem ciência");
        JsonNode caso4 = item(travados, e4);
        assertEquals(pend.toString(), texto(caso4.get("alertas").get(0).get("pendenciaId")));
        assertEquals("Observacao", texto(caso4.get("pendencias").get(0).get("responsavel")), "responsável");
        assertEquals(texto(rTotal.get("id")), texto(item(travados, e3).get("alertas").get(0).get("regraId")));

        // ------------------------------------------------------------ destaque na Torre (CA-05)
        JsonNode torre = json.readTree(exigir(200, coord.enviar("GET", "/api/episodios", null)).body());
        JsonNode destaques = torre.get("alertas");
        assertTrue(destaques.has(e1.toString()) && destaques.has(e3.toString()) && destaques.has(e4.toString()));
        assertFalse(destaques.has(e2.toString()), "dentro do limite: sem destaque");

        // ------------------------------------------------------------ painel coletivo pseudonimizado
        String painel = exigir(200, direcao.enviar("GET", "/api/painel", null)).body();
        int emAlerta = 0;
        for (JsonNode l : json.readTree(painel).get("itens")) {
            emAlerta += "true".equals(l.get("emAlerta").toString()) ? 1 : 0;
        }
        assertTrue(emAlerta >= 3, "painel indica quais estão em alerta");
        assertFalse(painel.contains("Paciente Transporte Atrasado"), "sem nomes no painel coletivo");
        assertFalse(painel.contains("Acionar a central"), "sem detalhes da regra no painel coletivo");

        // ------------------------------------------------------------ permissões e isolamento
        exigir(403, direcao.enviar("GET", "/api/travados", null));
        exigir(403, adm.enviar("GET", "/api/travados", null));
        JsonNode daOutra = json.readTree(exigir(200, outra.enviar("GET", "/api/travados", null)).body());
        assertEquals(0, daOutra.get("itens").size(), "outra unidade não vê os travados");
        // O alerta lido traz a versão da regra (Torre e /api/travados)
        assertEquals(0, alerta1.get("regraVersao").asInt());
        assertEquals(0, destaques.get(e1.toString()).get(0).get("regraVersao").asInt());
        String idTransporte = texto(rTransporte.get("id"));
        String referencia1 = texto(alerta1.get("referenciaEm"));
        String pedidoV0 = corpoCiencia(idTransporte, "0", referencia1, null);
        exigir(404, outra.enviar("POST", "/api/episodios/" + e1 + "/alertas/ciencia", pedidoV0));
        exigir(403, direcao.enviar("POST", "/api/episodios/" + e1 + "/alertas/ciencia", pedidoV0));

        // ------------------------------------------------------------ ciência na versão vista (RF-022)
        // O administrador altera a ação esperada → regra na versão 1; alerta ativo, mesma referência
        exigir(200, adm.enviar("PUT", "/api/config/regras-alerta/" + idTransporte, """
                {"versao":0,"nome":"Transporte atrasado (ilustrativo)","etapaId":"%s","limiteMinutos":120,
                 "acaoEsperada":"Acionar a central e avisar o plantao","ativa":true}"""
                .formatted(idEtapa("AGUARDANDO_TRANSPORTE"))));
        // Tela antiga (versão 0): 409, nada registrado
        HttpResponse<String> desatualizado = coord.enviar("POST", "/api/episodios/" + e1 + "/alertas/ciencia", pedidoV0);
        exigir(409, desatualizado);
        assertTrue(desatualizado.body().contains("CONFLITO_DE_VERSAO"));
        JsonNode relido = item(json.readTree(exigir(200, coord.enviar("GET", "/api/travados", null)).body()), e1)
                .get("alertas").get(0);
        assertEquals(1, relido.get("regraVersao").asInt());
        assertEquals(referencia1, texto(relido.get("referenciaEm")), "mesma ocorrência");
        assertEquals("Acionar a central e avisar o plantao", texto(relido.get("acaoEsperada")));
        JsonNode semCienciaV1 = relido.get("ciencia");
        assertTrue(semCienciaV1 == null || "null".equals(semCienciaV1.toString()), "versão 1 continua sem ciência");
        // Versão ausente ou negativa: 400
        exigir(400, coord.enviar("POST", "/api/episodios/" + e1 + "/alertas/ciencia",
                corpoCiencia(idTransporte, null, referencia1, null)));
        exigir(400, coord.enviar("POST", "/api/episodios/" + e1 + "/alertas/ciencia",
                corpoCiencia(idTransporte, "-1", referencia1, null)));
        // Relida, confirma a versão 1; repetir é idempotente
        String corpoCiencia = corpoCiencia(idTransporte, "1", referencia1, null);
        exigir(201, coord.enviar("POST", "/api/episodios/" + e1 + "/alertas/ciencia", corpoCiencia));
        exigir(200, coord.enviar("POST", "/api/episodios/" + e1 + "/alertas/ciencia", corpoCiencia));
        exigir(422, coord.enviar("POST", "/api/episodios/" + e2 + "/alertas/ciencia",
                corpoCiencia(idTransporte, "1", agora.toString(), null)));
        String cienciaPend = corpoCiencia(texto(rPendencia.get("id")),
                String.valueOf(caso4.get("alertas").get(0).get("regraVersao").asInt()),
                texto(caso4.get("alertas").get(0).get("referenciaEm")), pend.toString());
        exigir(201, coord.enviar("POST", "/api/episodios/" + e4 + "/alertas/ciencia", cienciaPend));
        travados = json.readTree(exigir(200, coord.enviar("GET", "/api/travados", null)).body());
        JsonNode ciencia = item(travados, e1).get("alertas").get(0).get("ciencia");
        assertNotNull(ciencia.get("registradaEm"));
        assertEquals("Coordenacao alertas", texto(ciencia.get("autorNome")));
        assertNotNull(item(travados, e4), "ciência não tira do painel nem encerra a pendência");
        JsonNode casoE4 = json.readTree(exigir(200, coord.enviar("GET", "/api/episodios/" + e4, null)).body());
        assertEquals("ABERTA", texto(casoE4.get("pendencias").get(0).get("status")));

        // ------------------------------------------------------------ encerramento interrompe (RN-008)
        exigir(200, coord.enviar("PUT", "/api/episodios/" + e1 + "/etapa",
                "{\"versao\":" + v1 + ",\"etapaId\":\"" + idEtapa("TRANSFERIDO") + "\"}"));
        travados = json.readTree(exigir(200, coord.enviar("GET", "/api/travados", null)).body());
        assertTrue(item(travados, e1) == null && item(travados, e5) == null, "encerrados fora do painel");
        exigir(404, coord.enviar("POST", "/api/episodios/" + e1 + "/alertas/ciencia", corpoCiencia));

        // ------------------------------------------------------------ desativar regra (versão obrigatória)
        String desativa = """
                {"versao":%d,"nome":"Permanencia total (ilustrativo)","limiteMinutos":1440,"ativa":false}""";
        exigir(200, adm.enviar("PUT", "/api/config/regras-alerta/" + texto(rTotal.get("id")), desativa.formatted(0)));
        exigir(409, adm.enviar("PUT", "/api/config/regras-alerta/" + texto(rTotal.get("id")),
                desativa.formatted(0).replace("\"ativa\":false", "\"ativa\":true")));
        exigir(403, coord.enviar("PUT", "/api/config/regras-alerta/" + texto(rTotal.get("id")), desativa.formatted(1)));
        travados = json.readTree(exigir(200, coord.enviar("GET", "/api/travados", null)).body());
        assertTrue(item(travados, e3) == null, "regra desativada deixa de alertar");

        // ------------------------------------------------------------ auditoria
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FILTER (WHERE recurso = 'fluxo.regra_alerta' AND unidade_id = ?),
                            count(*) FILTER (WHERE recurso = 'fluxo.ciencia_alerta' AND unidade_id = ?),
                            (SELECT count(*) FROM auditoria.verificar_cadeia())
                       FROM auditoria.registro""")) {
            ps.setObject(1, unidade);
            ps.setObject(2, unidade);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(5, rs.getLong(1), "3 regras criadas + 2 alterações auditadas");
                assertEquals(2, rs.getLong(2), "ciências auditadas (a repetida e a recusada não gravam)");
                assertEquals(0, contarCienciasNaVersao(e1, 0), "nenhuma ciência na versão 0 (não vista após a alteração)");
                assertEquals(0, rs.getLong(3), "cadeia íntegra");
            }
        }
    }

    // ----------------------------------------------------------------------------

    private static String corpoCiencia(String regraId, String versao, String referencia, String pendencia) {
        return "{\"regraId\":\"" + regraId + "\""
                + (versao == null ? "" : ",\"regraVersao\":" + versao)
                + ",\"referenciaEm\":\"" + referencia + "\""
                + (pendencia == null ? "" : ",\"pendenciaId\":\"" + pendencia + "\"")
                + "}";
    }

    private static long contarCienciasNaVersao(UUID episodio, int versao) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM fluxo.ciencia_alerta WHERE episodio_id = ? AND regra_versao = ?")) {
            ps.setObject(1, episodio);
            ps.setInt(2, versao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private UUID abrir(ClienteHttp c, String nome, Instant entrada) throws Exception {
        return UUID.fromString(texto(json.readTree(exigir(201, c.enviar("POST", "/api/episodios", """
                {"novoPaciente":{"nome":"%s"},"setorId":"%s","momento":%s}""".formatted(nome, setor, momento(entrada))))
                .body()).get("id")));
    }

    /** EM_ATENDIMENTO → AGUARDANDO_RECURSO_LEITO → ACEITO → AGUARDANDO_TRANSPORTE (em {@code transporte}). */
    private int ateTransporte(ClienteHttp c, UUID ep, Instant entrada, Instant transporte) throws Exception {
        int v = etapa(c, ep, 0, "AGUARDANDO_RECURSO_LEITO", "SEM_LEITO_ESPECIALIDADE", entrada.plus(Duration.ofMinutes(10)));
        v = etapa(c, ep, v, "ACEITO", null, entrada.plus(Duration.ofMinutes(20)));
        return etapa(c, ep, v, "AGUARDANDO_TRANSPORTE", "TRANSPORTE_PENDENTE", transporte);
    }

    private int etapa(ClienteHttp c, UUID ep, int versao, String etapa, String motivo, Instant quando) throws Exception {
        String corpo = "{\"versao\":" + versao + ",\"etapaId\":\"" + idEtapa(etapa) + "\""
                + (motivo == null ? "" : ",\"motivoId\":\"" + idMotivo(motivo) + "\"")
                + ",\"momento\":" + momento(quando) + "}";
        return json.readTree(exigir(200, c.enviar("PUT", "/api/episodios/" + ep + "/etapa", corpo)).body())
                .get("versao").asInt();
    }

    private static String momento(Instant quando) {
        return "{\"ocorridoEm\":\"" + quando + "\",\"justificativaAjuste\":\"Lancamento retroativo do teste\"}";
    }

    private JsonNode item(JsonNode travados, UUID ep) {
        for (JsonNode i : travados.get("itens")) {
            if (ep.toString().equals(texto(i.get("episodioId")))) {
                return i;
            }
        }
        return null;
    }

    private ClienteHttp cliente(String login) throws Exception {
        ClienteHttp c = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        c.entrar(login, SENHA);
        return c;
    }

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
                assertTrue(rs.next(), codigo);
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static UUID criarUnidade(Connection c, String codigo) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fluxo.unidade (id, codigo, nome, tipo, "
                + "retroatividade_maxima) VALUES (?, ?, 'UPA de teste', 'UPA', interval '7 days')")) {
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

    private static void criarUsuario(Connection c, String login, String hash, UUID unidadeId, String papel)
            throws Exception {
        UUID id = UUID.randomUUID();
        String nome = papel.equals("COORDENACAO_FLUXO") ? "Coordenacao alertas" : "Usuario " + papel.toLowerCase();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fluxo.usuario (id, login, nome, senha_hash, "
                + "deve_trocar_senha, unidade_gestora_id) VALUES (?, ?, ?, ?, false, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, login);
            ps.setString(3, nome);
            ps.setString(4, hash);
            ps.setObject(5, unidadeId);
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
