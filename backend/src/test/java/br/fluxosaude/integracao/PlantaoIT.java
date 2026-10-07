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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Passagem de plantão via HTTP com PostgreSQL real: fluxo entrega → recebimento por outro
 * profissional, conflito entre leitura e confirmação (episódio alterado, pendência resolvida),
 * uma pendente por unidade, permissões, isolamento entre unidades, sessão revogada, ausência de
 * efeitos sobre episódios e conteúdo gravado sem nomes.
 */
class PlantaoIT extends IntegracaoBase {

    static final String SENHA = "frase secreta da passagem de plantao";
    static UUID unidadeP;
    static UUID unidadeQ;
    static UUID setorP1;
    static UUID setorP2;
    static UUID unidadeV;
    static UUID unidadeC;
    static UUID setorC;
    static UUID unidadeR;
    static UUID setorR1;
    static UUID unidadeA;
    static UUID setorA;
    static UUID unidadeL;
    static UUID setorL;
    private static final AtomicBoolean PREPARADO = new AtomicBoolean();

    @Autowired
    Environment env;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void prepararUmaVez() throws Exception {
        if (PREPARADO.compareAndSet(false, true)) {
            String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
            try (Connection c = conexaoDono()) {
                c.setAutoCommit(false);
                unidadeP = unidade(c, "UPA_PLANT_P", "UPA Plantao P");
                unidadeQ = unidade(c, "UPA_PLANT_Q", "UPA Plantao Q");
                setorP1 = setor(c, unidadeP, "OBS_P", "Observacao P");
                setorP2 = setor(c, unidadeP, "EMG_P", "Emergencia P");
                setor(c, unidadeQ, "OBS_Q", "Observacao Q");
                UUID adm = usuario(c, "adm.plantao", "Administracao Plantao", hash, unidadeP, "ADMINISTRADOR");
                usuario(c, "enf.plantao", "Enfermagem Plantao", hash, unidadeP, "ENFERMAGEM");
                usuario(c, "med.plantao", "Medicina Plantao", hash, unidadeP, "MEDICO");
                usuario(c, "dir.plantao", "Direcao Plantao", hash, unidadeP, "DIRECAO");
                UUID multi = usuario(c, "coord.plantao", "Coordenacao Plantao", hash, unidadeP, "COORDENACAO_FLUXO");
                lotar(c, multi, unidadeQ, "COORDENACAO_FLUXO");
                usuario(c, "coord.q.plantao", "Coordenacao Q", hash, unidadeQ, "COORDENACAO_FLUXO");
                // V: unidade sem nenhum episódio; C: período (sem recebida, só canceladas, recebida).
                unidadeV = unidade(c, "UPA_PLANT_V", "UPA Plantao V");
                unidadeC = unidade(c, "UPA_PLANT_C", "UPA Plantao C");
                setorC = setor(c, unidadeC, "OBS_C", "Observacao C");
                usuario(c, "enf.v.plantao", "Enfermagem V", hash, unidadeV, "ENFERMAGEM");
                usuario(c, "enf.c.plantao", "Enfermagem C", hash, unidadeC, "ENFERMAGEM");
                usuario(c, "med.c.plantao", "Medicina C", hash, unidadeC, "MEDICO");
                // R: recebimento com mudanças (antes/depois); A: registro das leituras nominais.
                unidadeR = unidade(c, "UPA_PLANT_R", "UPA Plantao R");
                setorR1 = setor(c, unidadeR, "OBS_R", "Observacao R");
                usuario(c, "enf.r.plantao", "Enfermagem R", hash, unidadeR, "ENFERMAGEM");
                usuario(c, "med.r.plantao", "Medicina R", hash, unidadeR, "MEDICO");
                // L: alertas no recebimento (regras trocadas, alteradas e desativadas depois da entrega).
                unidadeL = unidade(c, "UPA_PLANT_L", "UPA Plantao L");
                setorL = setor(c, unidadeL, "OBS_L", "Observacao L");
                usuario(c, "adm.l.plantao", "Administracao L", hash, unidadeL, "ADMINISTRADOR");
                usuario(c, "enf.l.plantao", "Enfermagem L", hash, unidadeL, "ENFERMAGEM");
                usuario(c, "med.l.plantao", "Medicina L", hash, unidadeL, "MEDICO");
                unidadeA = unidade(c, "UPA_PLANT_A", "UPA Plantao A");
                setorA = setor(c, unidadeA, "OBS_A", "Observacao A");
                usuario(c, "enf.a.plantao", "Enfermagem A", hash, unidadeA, "ENFERMAGEM");
                usuario(c, "med.a.plantao", "Medicina A", hash, unidadeA, "MEDICO");
                usuario(c, "dir.a.plantao", "Direcao A", hash, unidadeA, "DIRECAO");
                assertTrue(adm != null);
                c.commit();
            }
        }
    }

    @Test
    void fluxoConflitosPermissoesEIsolamento() throws Exception {
        ClienteHttp enf = cliente("enf.plantao");
        ClienteHttp med = cliente("med.plantao");

        // ---------------------------------------------------------------- estado: 2 casos, 1 pendência
        String ep1 = abrir(enf, "Paciente Ficticio Plantao Um", setorP1);
        String ep2 = abrir(enf, "Paciente Ficticio Plantao Dois", setorP1);
        String prazo = java.time.Instant.now().plusSeconds(4 * 3600).toString();
        String pend = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/episodios/" + ep1 + "/pendencias",
                "{\"categoria\":\"LOGISTICA\",\"descricao\":\"Acionar transporte\",\"responsavel\":{\"setorId\":\"" + setorP1
                + "\"},\"prazo\":\"" + prazo + "\",\"criticidade\":\"CRITICA\"}")).body()).get("id"));

        // ---------------------------------------------------------------- prévia (todos os abertos)
        JsonNode previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(2, previa.get("totais").get("casos").asInt());
        assertEquals(1, previa.get("totais").get("pendencias").asInt());
        assertEquals(1, previa.get("totais").get("criticos").asInt(), "pendência de criticidade operacional CRÍTICA");
        assertTrue(previa.get("periodoInicio").isNull(), "primeira passagem da unidade");
        assertTrue(previa.toString().contains("Paciente Ficticio Plantao Um"), "nome lido do estado atual");
        String versaoEp2 = texto(json.readTree(exigir(200, enf.enviar("GET", "/api/episodios/" + ep2, null)).body())
                .get("resumo").get("versao"));

        // ---------------------------------------------------------------- conflito: episódio alterado antes da entrega
        exigir(200, med.enviar("PUT", "/api/episodios/" + ep2 + "/setor",
                "{\"versao\":" + versaoEp2 + ",\"setorId\":\"" + setorP2 + "\"}"));
        HttpResponse<String> desatualizada = enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}");
        assertEquals(409, desatualizada.statusCode());
        assertTrue(desatualizada.body().contains("PASSAGEM_DESATUALIZADA"));
        assertEquals(0, json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/passagens", null)).body()).size(),
                "nada gravado");

        // ---------------------------------------------------------------- nova leitura + entrega
        previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        String versaoEp2Entregue = texto(json.readTree(exigir(200, enf.enviar("GET", "/api/episodios/" + ep2, null)).body())
                .get("resumo").get("versao"));
        String id = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\",\"observacao\":\"Leito 4 em higienizacao\"}"))
                .body()).get("id"));
        HttpResponse<String> segunda = enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}");
        assertEquals(409, segunda.statusCode());
        assertTrue(segunda.body().contains("PASSAGEM_PENDENTE"));

        // ---------------------------------------------------------------- quem entregou não recebe
        JsonNode detalheEnf = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        HttpResponse<String> proprio = enf.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + texto(detalheEnf.get("assinaturaRecebimento")) + "\"}");
        assertEquals(422, proprio.statusCode());
        assertTrue(proprio.body().contains("RECEBEDOR_E_ENTREGADOR"));

        // ---------------------------------------------------------------- recebimento com diferenças mudadas: 409
        JsonNode detalhe = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertTrue(detalhe.get("integra").asBoolean());
        assertEquals(0, detalhe.get("diferencas").get("pendenciasEncerradas").size());
        String versaoPend = texto(json.readTree(exigir(200, enf.enviar("GET", "/api/episodios/" + ep1, null)).body())
                .get("pendencias").get(0).get("versao"));
        exigir(200, enf.enviar("POST", "/api/pendencias/" + pend + "/resolucao",
                "{\"versao\":" + versaoPend + ",\"texto\":\"Transporte acionado\"}"));
        HttpResponse<String> velha = med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + texto(detalhe.get("assinaturaRecebimento")) + "\"}");
        assertEquals(409, velha.statusCode());
        assertTrue(velha.body().contains("RECEBIMENTO_DESATUALIZADO"));
        detalhe = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertEquals("ENTREGUE", texto(detalhe.get("passagem").get("status")), "nada confirmado");
        JsonNode encerrada = detalhe.get("diferencas").get("pendenciasEncerradas").get(0);
        assertEquals(pend, texto(encerrada.get("id")));
        assertEquals("ENCERRADO", texto(encerrada.get("tipo")));
        assertEquals(ep1, texto(encerrada.get("episodioId")), "pendência encerrada ligada ao caso");
        assertEquals("Acionar transporte", texto(encerrada.get("descricao")));
        assertTrue(encerrada.get("atual").isNull(), "encerrada: só o valor da entrega");
        assertEquals(setorP1.toString(), texto(encerrada.get("entregue").get("responsavelSetorId")));
        // Versão lida errada
        assertEquals(409, med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":5,\"assinatura\":\"" + texto(detalhe.get("assinaturaRecebimento")) + "\"}").statusCode());
        exigir(204, med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + texto(detalhe.get("assinaturaRecebimento")) + "\"}"));
        JsonNode recebida = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertEquals("RECEBIDA", texto(recebida.get("passagem").get("status")));
        assertEquals("Medicina Plantao", texto(recebida.get("passagem").get("recebidaPorNome")));
        assertEquals(1, recebida.get("passagem").get("diferencasRecebimento").get("pendenciasEncerradas").asInt());
        assertTrue(recebida.get("diferencas").isNull());

        // A passagem (entrega + recebimento) não alterou episódios: ep2 só foi mudado pelo usuário, antes da entrega
        JsonNode caso2 = json.readTree(exigir(200, med.enviar("GET", "/api/episodios/" + ep2, null)).body());
        assertEquals(versaoEp2Entregue, texto(caso2.get("resumo").get("versao")));
        assertTrue(caso2.get("encerradoEm").isNull());
        JsonNode caso1 = json.readTree(exigir(200, med.enviar("GET", "/api/episodios/" + ep1, null)).body());
        assertEquals("RESOLVIDA", texto(caso1.get("pendencias").get(0).get("status")), "só a ação do usuário mudou a pendência");

        // ---------------------------------------------------------------- próxima prévia: período desde a recebida
        JsonNode proxima = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(texto(recebida.get("passagem").get("entregueEm")), texto(proxima.get("periodoInicio")));

        // ---------------------------------------------------------------- permissões
        exigir(403, cliente("dir.plantao").enviar("GET", "/api/plantao/previa", null));
        exigir(403, cliente("adm.plantao").enviar("GET", "/api/plantao/passagens", null));

        // ---------------------------------------------------------------- isolamento e troca de unidade
        ClienteHttp coordQ = cliente("coord.q.plantao");
        exigir(404, coordQ.enviar("GET", "/api/plantao/passagens/" + id, null));
        assertEquals(0, json.readTree(exigir(200, coordQ.enviar("GET", "/api/plantao/passagens", null)).body()).size());
        ClienteHttp multi = cliente("coord.plantao");
        exigir(200, multi.enviar("PUT", "/api/sessao/unidade", "{\"unidadeId\":\"" + unidadeQ + "\"}"));
        JsonNode previaQ = json.readTree(exigir(200, multi.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(0, previaQ.get("totais").get("casos").asInt(), "na Q não aparecem casos da P");
        exigir(404, multi.enviar("GET", "/api/plantao/passagens/" + id, null));

        // ---------------------------------------------------------------- conteúdo gravado sem nomes; auditoria
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT conteudo::text FROM fluxo.passagem_conteudo WHERE passagem_id = ?")) {
            ps.setObject(1, UUID.fromString(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String conteudo = rs.getString(1);
                assertFalse(conteudo.contains("Paciente Ficticio"), "sem nome do paciente no registro da passagem");
                assertFalse(conteudo.contains("Acionar transporte"), "sem texto livre de pendência");
            }
        }
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT string_agg(acao, ',' ORDER BY id) FROM auditoria.registro "
                     + "WHERE recurso = 'fluxo.passagem_plantao' AND recurso_id = ? AND acao LIKE 'PASSAGEM_%'")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("PASSAGEM_ENTREGUE,PASSAGEM_RECEBIDA", rs.getString(1));
            }
        }

        // ---------------------------------------------------------------- sessão revogada
        ClienteHttp adm = cliente("adm.plantao");
        JsonNode lista = json.readTree(exigir(200, adm.enviar("GET", "/api/admin/usuarios", null)).body());
        JsonNode alvo = null;
        for (JsonNode u : lista.get("itens")) {
            if ("med.plantao".equals(texto(u.get("login")))) {
                alvo = u;
            }
        }
        exigir(200, adm.enviar("POST", "/api/admin/usuarios/" + texto(alvo.get("id")) + "/senha-provisoria",
                "{\"versao\":" + alvo.get("versao").asInt() + "}"));
        exigir(401, med.enviar("GET", "/api/plantao/previa", null));
    }

    /**
     * Início do período sem passagem RECEBIDA (revisão do PR #10, ponto 1): a primeira prévia de
     * uma unidade respondia 500 porque o adaptador lia um agregado nulo com {@code single()}. Cobre
     * unidade vazia, unidade com casos e sem recebida, unidade só com canceladas e unidade com
     * recebida anterior — conferindo que a prévia e o gatilho do banco usam o mesmo início.
     */
    @Test
    void periodoDaPrimeiraPassagemSemRecebidaComCanceladasEComRecebida() throws Exception {
        // ---------------------------------------------------------------- unidade vazia
        ClienteHttp enfV = cliente("enf.v.plantao");
        JsonNode vazia = json.readTree(exigir(200, enfV.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(0, vazia.get("totais").get("casos").asInt());
        assertEquals(0, vazia.get("casos").size());
        assertTrue(vazia.get("periodoInicio").isNull(), "unidade vazia: sem passagem recebida");
        assertTrue(vazia.get("pendente").isNull());
        // Passagem de unidade vazia também é registrável (nada a continuar também é informação).
        String vaziaId = texto(json.readTree(exigir(201, enfV.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(vazia.get("assinatura")) + "\"}")).body()).get("id"));
        JsonNode vaziaDetalhe = json.readTree(exigir(200, enfV.enviar("GET", "/api/plantao/passagens/" + vaziaId, null)).body());
        assertTrue(vaziaDetalhe.get("passagem").get("periodoInicio").isNull());

        // ---------------------------------------------------------------- casos, nenhuma passagem
        ClienteHttp enf = cliente("enf.c.plantao");
        ClienteHttp med = cliente("med.c.plantao");
        abrir(enf, "Paciente Ficticio Periodo", setorC);
        JsonNode previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(1, previa.get("totais").get("casos").asInt());
        assertTrue(previa.get("periodoInicio").isNull(), "casos sem passagem recebida");

        // ---------------------------------------------------------------- só canceladas
        String cancelada = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}")).body()).get("id"));
        exigir(204, enf.enviar("POST", "/api/plantao/passagens/" + cancelada + "/cancelamento",
                "{\"versao\":0,\"justificativa\":\"Entregue por engano\"}"));
        previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        assertTrue(previa.get("periodoInicio").isNull(), "passagem cancelada não inicia período");
        assertTrue(previa.get("pendente").isNull());

        // ---------------------------------------------------------------- recebida anterior
        String recebida = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}")).body()).get("id"));
        JsonNode aberta = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + recebida, null)).body());
        assertTrue(aberta.get("passagem").get("periodoInicio").isNull(), "gatilho: só canceladas antes");
        exigir(204, med.enviar("POST", "/api/plantao/passagens/" + recebida + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + texto(aberta.get("assinaturaRecebimento")) + "\"}"));
        String entregueEm = texto(json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + recebida, null))
                .body()).get("passagem").get("entregueEm"));
        previa = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(entregueEm, texto(previa.get("periodoInicio")), "período desde a entrega da última recebida");

        // Uma cancelada MAIS NOVA que a recebida não muda o início; o gatilho do banco concorda.
        String nova = texto(json.readTree(exigir(201, med.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}")).body()).get("id"));
        JsonNode novaDetalhe = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + nova, null)).body());
        assertEquals(entregueEm, texto(novaDetalhe.get("passagem").get("periodoInicio")), "prévia e gatilho: mesmo início");
        exigir(204, med.enviar("POST", "/api/plantao/passagens/" + nova + "/cancelamento",
                "{\"versao\":0,\"justificativa\":\"Refazer depois\"}"));
        previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(entregueEm, texto(previa.get("periodoInicio")));
    }

    /**
     * Revisão do PR #10, ponto 2: o recebimento assina a situação atual, então ela tem de vir na
     * resposta (e na tela) com os valores NOVOS. Entrega → muda responsável/prazo de uma pendência e
     * etapa/motivo de um caso → o detalhe traz entregue × atual → outra mudança depois da leitura →
     * 409 sem gravar nada → nova leitura com os novos valores → recebimento confirmado.
     */
    @Test
    void recebimentoMostraSituacaoAtualComAntesEDepois() throws Exception {
        ClienteHttp enf = cliente("enf.r.plantao");
        ClienteHttp med = cliente("med.r.plantao");
        String ep = abrir(enf, "Paciente Ficticio Recebimento", setorR1);
        String prazo1 = java.time.Instant.now().plusSeconds(4 * 3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString();
        String pend = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/episodios/" + ep + "/pendencias",
                "{\"categoria\":\"LOGISTICA\",\"descricao\":\"Confirmar vaga de retaguarda\",\"responsavel\":{\"setorId\":\""
                + setorR1 + "\"},\"prazo\":\"" + prazo1 + "\",\"criticidade\":\"MEDIA\"}")).body()).get("id"));

        // ---------------------------------------------------------------- 1. entrega
        JsonNode previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        String id = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}")).body()).get("id"));

        // ---------------------------------------------------------------- 2. responsável/prazo e etapa/motivo
        String prazo2 = java.time.Instant.now().plusSeconds(8 * 3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString();
        exigir(200, med.enviar("PATCH", "/api/pendencias/" + pend,
                "{\"versao\":0,\"responsavel\":{\"papel\":\"MEDICO\"},\"prazo\":\"" + prazo2 + "\"}"));
        int versao = versaoEpisodio(med, ep);
        exigir(200, med.enviar("PUT", "/api/episodios/" + ep + "/etapa", "{\"versao\":" + versao + ",\"etapaId\":\""
                + idCatalogo("fluxo.etapa", unidadeR, "AGUARDANDO_RECURSO_LEITO") + "\",\"motivoId\":\""
                + idCatalogo("fluxo.motivo_bloqueio", unidadeR, "SEM_LEITO_ESPECIALIDADE") + "\"}"));

        // ---------------------------------------------------------------- 3. leitura: entregue × atual
        JsonNode d = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertEquals("Em atendimento", texto(d.get("casos").get(0).get("etapaNome")), "conteúdo ENTREGUE inalterado");
        JsonNode caso = d.get("diferencas").get("casosAlterados").get(0);
        assertEquals(ep, texto(caso.get("episodioId")));
        assertEquals("Paciente Ficticio Recebimento", texto(caso.get("pacienteNome")));
        assertTrue(caso.get("campos").toString().contains("ETAPA"));
        assertTrue(caso.get("campos").toString().contains("MOTIVO_BLOQUEIO"));
        assertEquals("Em atendimento", texto(caso.get("entregue").get("etapaNome")));
        assertTrue(caso.get("entregue").get("motivoId").isNull());
        assertEquals("Aguardando recurso/leito", texto(caso.get("atual").get("etapaNome")));
        assertEquals("Sem leito na especialidade", texto(caso.get("atual").get("motivoDescricao")));
        assertEquals("Observacao R", texto(caso.get("atual").get("setorNome")));
        JsonNode p = d.get("diferencas").get("pendenciasAlteradas").get(0);
        assertEquals(pend, texto(p.get("id")));
        assertEquals(ep, texto(p.get("episodioId")), "pendência vinculada ao caso");
        assertEquals("Paciente Ficticio Recebimento", texto(p.get("pacienteNome")));
        assertEquals("Confirmar vaga de retaguarda", texto(p.get("descricao")));
        assertEquals("[\"RESPONSAVEL\",\"PRAZO\"]", p.get("campos").toString());
        assertEquals(setorR1.toString(), texto(p.get("entregue").get("responsavelSetorId")));
        assertEquals("Observacao R", texto(p.get("entregue").get("responsavelNome")));
        assertEquals(java.time.Instant.parse(prazo1), java.time.Instant.parse(texto(p.get("entregue").get("prazo"))));
        assertEquals("MEDICO", texto(p.get("atual").get("responsavelPapel")));
        assertTrue(p.get("atual").get("responsavelSetorId").isNull());
        assertEquals(java.time.Instant.parse(prazo2), java.time.Instant.parse(texto(p.get("atual").get("prazo"))));
        assertEquals(1, d.get("totaisAtuais").get("casos").asInt());
        String assinaturaLida = texto(d.get("assinaturaRecebimento"));

        // ---------------------------------------------------------------- 4. outra mudança depois da leitura
        versao = versaoEpisodio(med, ep);
        exigir(200, enf.enviar("PUT", "/api/episodios/" + ep + "/motivo", "{\"versao\":" + versao + ",\"motivoId\":\""
                + idCatalogo("fluxo.motivo_bloqueio", unidadeR, "DESTINO_SEM_CAPACIDADE") + "\"}"));

        // ---------------------------------------------------------------- 5. 409 sem gravar nada
        HttpResponse<String> velha = med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + assinaturaLida + "\"}");
        assertEquals(409, velha.statusCode());
        assertTrue(velha.body().contains("RECEBIMENTO_DESATUALIZADO"));
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT status::text, recebida_por, assinatura_recebimento, versao, "
                     + "(SELECT count(*) FROM auditoria.registro r WHERE r.recurso_id = p.id::text AND r.acao = 'PASSAGEM_RECEBIDA') "
                     + "FROM fluxo.passagem_plantao p WHERE id = ?")) {
            ps.setObject(1, UUID.fromString(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("ENTREGUE", rs.getString(1));
                assertEquals(null, rs.getObject(2));
                assertEquals(null, rs.getString(3));
                assertEquals(0, rs.getInt(4));
                assertEquals(0, rs.getInt(5), "nenhum registro de recebimento");
            }
        }

        // ---------------------------------------------------------------- 6. nova leitura explícita + recebimento
        d = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        caso = d.get("diferencas").get("casosAlterados").get(0);
        assertEquals("Unidade de destino sem capacidade", texto(caso.get("atual").get("motivoDescricao")));
        assertEquals("MEDICO", texto(d.get("diferencas").get("pendenciasAlteradas").get(0).get("atual").get("responsavelPapel")));
        String nova = texto(d.get("assinaturaRecebimento"));
        assertFalse(nova.equals(assinaturaLida));
        exigir(204, med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + nova + "\"}"));
        JsonNode recebida = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertEquals("RECEBIDA", texto(recebida.get("passagem").get("status")));
        assertEquals(1, recebida.get("passagem").get("diferencasRecebimento").get("casosAlterados").asInt());
        assertEquals(1, recebida.get("passagem").get("diferencasRecebimento").get("pendenciasAlteradas").asInt());
        // A passagem não mexeu na pendência nem no caso: valores são os que os usuários gravaram.
        JsonNode atual = json.readTree(exigir(200, med.enviar("GET", "/api/episodios/" + ep, null)).body());
        assertEquals("ABERTA", texto(atual.get("pendencias").get(0).get("status")));
        assertTrue(atual.get("encerradoEm").isNull());
    }

    /**
     * Revisão do PR #10, ponto 3: prévia e detalhe registram a leitura nominal (ator, unidade,
     * referências) sem copiar nomes ou descrições; leitura recusada ou de outra unidade não gera
     * registro na unidade alheia.
     */
    @Test
    void leiturasNominaisDaPassagemSaoRegistradasSemNomes() throws Exception {
        ClienteHttp enf = cliente("enf.a.plantao");
        ClienteHttp med = cliente("med.a.plantao");
        String ep1 = abrir(enf, "Paciente Ficticio Auditoria Um", setorA);
        String ep2 = abrir(enf, "Paciente Ficticio Auditoria Dois", setorA);
        exigir(201, enf.enviar("POST", "/api/episodios/" + ep1 + "/pendencias",
                "{\"categoria\":\"LOGISTICA\",\"descricao\":\"Descricao sigilosa da pendencia\",\"responsavel\":{\"setorId\":\""
                + setorA + "\"},\"prazo\":\"" + java.time.Instant.now().plusSeconds(3 * 3600) + "\",\"criticidade\":\"BAIXA\"}"));
        UUID idEnf = idUsuario("enf.a.plantao");
        UUID idMed = idUsuario("med.a.plantao");

        // ---------------------------------------------------------------- prévia
        long marco = ultimoRegistro();
        JsonNode previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        List<Registro> regs = registros(marco, "CONSULTA_PREVIA_PASSAGEM");
        assertEquals(1, regs.size());
        Registro r = regs.get(0);
        assertEquals(idEnf, r.usuario());
        assertEquals(unidadeA, r.unidade());
        assertEquals("fluxo.passagem_plantao", r.recurso());
        assertEquals(null, r.recursoId());
        JsonNode dados = json.readTree(r.dados());
        assertEquals(2, dados.get("episodios").asInt());
        assertEquals(2, dados.get("casos").asInt());
        assertEquals(texto(previa.get("assinatura")), texto(dados.get("assinatura")));
        assertEquals(sorted(ep1, ep2), conjunto(unidadeA, texto(dados.get("conjunto"))), "referências = casos exibidos");
        semNominal(r.dados(), ep1, ep2);

        // ---------------------------------------------------------------- detalhe (com caso novo além do entregue)
        String id = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}")).body()).get("id"));
        String ep3 = abrir(enf, "Paciente Ficticio Auditoria Tres", setorA);
        marco = ultimoRegistro();
        exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null));
        regs = registros(marco, "CONSULTA_PASSAGEM");
        assertEquals(1, regs.size());
        r = regs.get(0);
        assertEquals(idMed, r.usuario());
        assertEquals(unidadeA, r.unidade());
        assertEquals(id, r.recursoId(), "o conteúdo entregue é referenciado pela própria passagem");
        dados = json.readTree(r.dados());
        assertEquals("ENTREGUE", texto(dados.get("status")));
        assertEquals(List.of(ep3), conjunto(unidadeA, texto(dados.get("conjunto"))), "só os exibidos além do entregue");
        semNominal(r.dados(), ep1, ep2, ep3);

        // ---------------------------------------------------------------- isolamento e recusas
        marco = ultimoRegistro();
        ClienteHttp outra = cliente("coord.q.plantao");
        exigir(404, outra.enviar("GET", "/api/plantao/passagens/" + id, null));
        exigir(403, cliente("dir.a.plantao").enviar("GET", "/api/plantao/previa", null));
        assertEquals(0, registros(marco, "CONSULTA_PASSAGEM").size(), "leitura recusada não é registrada como leitura");
        assertEquals(0, registros(marco, "CONSULTA_PREVIA_PASSAGEM").size());
        exigir(200, outra.enviar("GET", "/api/plantao/previa", null));
        regs = registros(marco, "CONSULTA_PREVIA_PASSAGEM");
        assertEquals(1, regs.size());
        assertEquals(unidadeQ, regs.get(0).unidade(), "registro na unidade de quem leu");
        List<String> daOutra = conjunto(unidadeQ, texto(json.readTree(regs.get(0).dados()).get("conjunto")));
        assertFalse(daOutra.contains(ep1) || daOutra.contains(ep2) || daOutra.contains(ep3), "nada da unidade A");
    }

    /**
     * Revisão do PR #10 (alertas no recebimento): a assinatura cobre os alertas do conteúdo atual,
     * então o detalhe identifica cada alerta — qual saiu, qual entrou, qual mudou de versão —, com os
     * dados DA VERSÃO de cada regra (nunca os da configuração atual no lugar dos entregues) e a
     * situação atual da regra à parte. Mudança depois da leitura → 409 sem gravar nada.
     */
    @Test
    void recebimentoIdentificaCadaAlertaComARegraNaVersaoDoAlerta() throws Exception {
        ClienteHttp adm = cliente("adm.l.plantao");
        ClienteHttp enf = cliente("enf.l.plantao");
        ClienteHttp med = cliente("med.l.plantao");
        java.time.Instant agora = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        java.time.Instant entrada = agora.minusSeconds(3 * 3600);
        String ep = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/episodios",
                "{\"novoPaciente\":{\"nome\":\"Paciente Ficticio Alertas\"},\"setorId\":\"" + setorL
                + "\",\"momento\":{\"ocorridoEm\":\"" + entrada + "\",\"justificativaAjuste\":\"Registro tardio do teste\"}}"))
                .body()).get("id"));
        String a = regra(adm, "{\"nome\":\"Permanencia longa (A)\",\"tipo\":\"TEMPO_TOTAL\",\"limiteMinutos\":60,"
                + "\"acaoEsperada\":\"Avisar coordenacao\"}");
        String c = regra(adm, "{\"nome\":\"Permanencia em atraso (C)\",\"tipo\":\"TEMPO_TOTAL\",\"limiteMinutos\":30,"
                + "\"acaoEsperada\":\"Rever conduta\"}");

        // ---------------------------------------------------------------- entrega com A e C
        JsonNode previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(2, previa.get("casos").get(0).get("alertas").size());
        String id = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}")).body()).get("id"));

        // ---------------------------------------------------------------- depois: A renomeada e desativada, B criada, C alterada
        exigir(200, adm.enviar("PUT", "/api/config/regras-alerta/" + a, "{\"versao\":0,\"nome\":\"A renomeada\","
                + "\"limiteMinutos\":60,\"acaoEsperada\":\"Outra acao\",\"ativa\":false}"));
        String b = regra(adm, "{\"nome\":\"Permanencia muito longa (B)\",\"tipo\":\"TEMPO_TOTAL\",\"limiteMinutos\":90,"
                + "\"acaoEsperada\":\"Acionar NIR\"}");
        exigir(200, adm.enviar("PUT", "/api/config/regras-alerta/" + c, "{\"versao\":0,\"nome\":\"Permanencia em atraso (C v1)\","
                + "\"limiteMinutos\":45,\"acaoEsperada\":\"Rever conduta e avisar NIR\",\"ativa\":true}"));

        JsonNode d = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        // Conteúdo ENTREGUE: regra A na versão 0, como era — não o nome/ação atuais; situação atual à parte.
        JsonNode entregueA = alertaDaRegra(d.get("casos").get(0).get("alertas"), a);
        assertEquals(0, entregueA.get("regraVersao").asInt());
        assertEquals("Permanencia longa (A)", texto(entregueA.get("regra").get("nome")));
        assertEquals("Avisar coordenacao", texto(entregueA.get("regra").get("acaoEsperada")));
        assertEquals(60, entregueA.get("regra").get("limiteMinutos").asInt());
        assertEquals(1, entregueA.get("regraAtual").get("versao").asInt());
        assertFalse(entregueA.get("regraAtual").get("ativa").asBoolean(), "desativada depois");
        assertFalse(d.toString().contains("A renomeada"), "dados da versão atual de A não aparecem como os entregues");

        JsonNode caso = d.get("diferencas").get("casosAlterados").get(0);
        assertTrue(caso.get("campos").toString().contains("ALERTAS"));
        JsonNode mud = caso.get("alertas");
        assertEquals(3, mud.size(), "A, B e C, um a um (2 → 2 alertas)");
        JsonNode mA = mudancaDaRegra(mud, a);
        assertEquals("REMOVIDO", texto(mA.get("tipo")));
        assertTrue(mA.get("atual").isNull());
        assertEquals("Permanencia longa (A)", texto(mA.get("entregue").get("regra").get("nome")));
        JsonNode mB = mudancaDaRegra(mud, b);
        assertEquals("ADICIONADO", texto(mB.get("tipo")));
        assertTrue(mB.get("entregue").isNull());
        assertEquals("Permanencia muito longa (B)", texto(mB.get("atual").get("regra").get("nome")));
        assertEquals("Acionar NIR", texto(mB.get("atual").get("regra").get("acaoEsperada")));
        assertEquals(entrada, java.time.Instant.parse(texto(mB.get("atual").get("referenciaEm"))));
        assertEquals(entrada.plusSeconds(90 * 60), java.time.Instant.parse(texto(mB.get("atual").get("atingidoEm"))));
        JsonNode mC = mudancaDaRegra(mud, c);
        assertEquals("ALTERADO", texto(mC.get("tipo")));
        assertEquals("[\"VERSAO_REGRA\",\"ATINGIDO\"]", mC.get("campos").toString());
        assertEquals("Permanencia em atraso (C)", texto(mC.get("entregue").get("regra").get("nome")));
        assertEquals(30, mC.get("entregue").get("regra").get("limiteMinutos").asInt());
        assertEquals("Permanencia em atraso (C v1)", texto(mC.get("atual").get("regra").get("nome")));
        assertEquals(45, mC.get("atual").get("regra").get("limiteMinutos").asInt());
        String lida = texto(d.get("assinaturaRecebimento"));

        // ---------------------------------------------------------------- nova versão de B depois da leitura → 409
        exigir(200, adm.enviar("PUT", "/api/config/regras-alerta/" + b, "{\"versao\":0,\"nome\":\"Permanencia muito longa (B)\","
                + "\"limiteMinutos\":90,\"acaoEsperada\":\"Acionar NIR e direcao\",\"ativa\":true}"));
        HttpResponse<String> velha = med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + lida + "\"}");
        assertEquals(409, velha.statusCode());
        assertTrue(velha.body().contains("RECEBIMENTO_DESATUALIZADO"));
        try (Connection cx = conexaoDono();
             PreparedStatement ps = cx.prepareStatement("SELECT status::text, recebida_por, versao FROM fluxo.passagem_plantao WHERE id = ?")) {
            ps.setObject(1, UUID.fromString(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("ENTREGUE", rs.getString(1));
                assertEquals(null, rs.getObject(2));
                assertEquals(0, rs.getInt(3));
            }
        }

        // ---------------------------------------------------------------- recarga explícita → B na versão 1 → recebimento
        d = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        mB = mudancaDaRegra(d.get("diferencas").get("casosAlterados").get(0).get("alertas"), b);
        assertEquals(1, mB.get("atual").get("regraVersao").asInt());
        assertEquals("Acionar NIR e direcao", texto(mB.get("atual").get("regra").get("acaoEsperada")));
        exigir(204, med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + texto(d.get("assinaturaRecebimento")) + "\"}"));
        // Depois de recebida, o conteúdo entregue continua com A na versão 0 (histórico, não configuração atual).
        JsonNode recebida = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertEquals("Permanencia longa (A)",
                texto(alertaDaRegra(recebida.get("casos").get(0).get("alertas"), a).get("regra").get("nome")));
        assertEquals(ep, texto(recebida.get("casos").get(0).get("episodioId")));
    }

    private String regra(ClienteHttp adm, String corpo) throws Exception {
        return texto(json.readTree(exigir(201, adm.enviar("POST", "/api/config/regras-alerta", corpo)).body()).get("id"));
    }

    private static JsonNode alertaDaRegra(JsonNode alertas, String regra) {
        for (JsonNode x : alertas) {
            if (regra.equals(texto(x.get("regraId")))) {
                return x;
            }
        }
        throw new AssertionError("alerta da regra " + regra + " ausente: " + alertas);
    }

    private static JsonNode mudancaDaRegra(JsonNode mudancas, String regra) {
        for (JsonNode m : mudancas) {
            JsonNode lado = m.get("entregue").isNull() ? m.get("atual") : m.get("entregue");
            if (regra.equals(texto(lado.get("regraId")))) {
                return m;
            }
        }
        throw new AssertionError("mudança da regra " + regra + " ausente: " + mudancas);
    }

    // ----------------------------------------------------------------------------

    record Registro(UUID usuario, UUID unidade, String recurso, String recursoId, String dados) {
    }

    private static long ultimoRegistro() throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT coalesce(max(id), 0) FROM auditoria.registro");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<Registro> registros(long depoisDe, String acao) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT usuario_id, unidade_id, recurso, recurso_id, dados::text "
                     + "FROM auditoria.registro WHERE id > ? AND acao = ? ORDER BY id")) {
            ps.setLong(1, depoisDe);
            ps.setString(2, acao);
            List<Registro> r = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    r.add(new Registro(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getString(4), rs.getString(5)));
                }
            }
            return r;
        }
    }

    /** Episódios do conjunto referenciado (já conferindo que o hash gravado corresponde à lista). */
    private static List<String> conjunto(UUID unidade, String hashHex) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT array_to_string(episodios, ','), "
                     + "hash = public.digest(convert_to(array_to_string(episodios, ','), 'UTF8'), 'sha256') "
                     + "FROM auditoria.conjunto_consultado WHERE unidade_id = ? AND hash = decode(?, 'hex')")) {
            ps.setObject(1, unidade);
            ps.setString(2, hashHex);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "conjunto referenciado existe na unidade");
                assertTrue(rs.getBoolean(2), "hash confere com a lista");
                String s = rs.getString(1);
                return s.isEmpty() ? List.of() : List.of(s.split(","));
            }
        }
    }

    private static void semNominal(String dados, String... episodios) {
        for (String proibido : List.of("Paciente Ficticio", "Auditoria", "sigilosa", "Observacao A")) {
            assertFalse(dados.contains(proibido), "dados da auditoria sem nomes/descrições: " + proibido);
        }
        for (String ep : episodios) {
            assertFalse(dados.contains(ep), "a lista de episódios fica no conjunto, não no evento");
        }
    }

    private static List<String> sorted(String... ids) {
        return java.util.Arrays.stream(ids).sorted().toList();
    }

    private int versaoEpisodio(ClienteHttp c, String ep) throws Exception {
        return json.readTree(exigir(200, c.enviar("GET", "/api/episodios/" + ep, null)).body())
                .get("resumo").get("versao").asInt();
    }

    private static UUID idCatalogo(String tabela, UUID unidade, String codigo) throws Exception {
        if (!tabela.equals("fluxo.etapa") && !tabela.equals("fluxo.motivo_bloqueio")) {
            throw new IllegalArgumentException(tabela);
        }
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM " + tabela + " WHERE unidade_id = ? AND codigo = ?")) {
            ps.setObject(1, unidade);
            ps.setString(2, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), codigo);
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static UUID idUsuario(String login) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM fluxo.usuario WHERE login = ?")) {
            ps.setString(1, login);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), login);
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private String abrir(ClienteHttp c, String nome, UUID setor) throws Exception {
        return texto(json.readTree(exigir(201, c.enviar("POST", "/api/episodios",
                "{\"novoPaciente\":{\"nome\":\"" + nome + "\"},\"setorId\":\"" + setor + "\"}")).body()).get("id"));
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

    private static UUID unidade(Connection c, String codigo, String nome) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.unidade (id, codigo, nome, tipo) VALUES (?, ?, ?, 'UPA')")) {
            ps.setObject(1, id);
            ps.setString(2, codigo);
            ps.setString(3, nome);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT fluxo.provisionar_unidade(?)")) {
            ps.setObject(1, id);
            ps.execute();
        }
        return id;
    }

    private static UUID setor(Connection c, UUID unidade, String codigo, String nome) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, unidade);
            ps.setString(3, codigo);
            ps.setString(4, nome);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID usuario(Connection c, String login, String nome, String hash, UUID unidade, String papel)
            throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fluxo.usuario (id, login, nome, senha_hash, "
                + "deve_trocar_senha, unidade_gestora_id) VALUES (?, ?, ?, ?, false, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, login);
            ps.setString(3, nome);
            ps.setString(4, hash);
            ps.setObject(5, unidade);
            ps.executeUpdate();
        }
        lotar(c, id, unidade, papel);
        return id;
    }

    private static void lotar(Connection c, UUID usuario, UUID unidade, String papel) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, CAST(? AS fluxo.papel))")) {
            ps.setObject(1, usuario);
            ps.setObject(2, unidade);
            ps.setString(3, papel);
            ps.executeUpdate();
        }
    }
}
