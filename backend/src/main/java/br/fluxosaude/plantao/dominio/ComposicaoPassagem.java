package br.fluxosaude.plantao.dominio;

import br.fluxosaude.alerta.dominio.Alerta;
import br.fluxosaude.alerta.dominio.MotorDeAlertas;
import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import br.fluxosaude.episodio.dominio.NaturezaEtapa;
import java.time.Instant;
import java.util.List;

/**
 * Composição automática da passagem (CA-07) a partir do estado atual, pelo relógio do servidor.
 * Nada é filtrado: todos os episódios abertos entram (continuidade); as marcações ajudam a
 * separar as seções da ERS §10.4 (casos críticos, transferências, pendências vencidas e ações
 * esperadas). Os alertas vêm do mesmo motor da Torre (regras ativas da unidade).
 */
public final class ComposicaoPassagem {

    private ComposicaoPassagem() {
    }

    public static ConteudoPassagem compor(List<CasoAtual> casos, List<RegraAlerta> regrasAtivas, Instant agora) {
        return new ConteudoPassagem(casos.stream().map(c -> caso(c, regrasAtivas, agora)).toList());
    }

    private static ConteudoPassagem.CasoPassagem caso(CasoAtual c, List<RegraAlerta> regras, Instant agora) {
        SituacaoEpisodio situacao = new SituacaoEpisodio(c.episodioId(), c.etapaId(), c.entradaEm(), c.etapaDesde(),
                c.bloqueioDesde(), c.categoria(), c.ultimoRegistroEm(),
                c.pendencias().stream().map(p -> new SituacaoEpisodio.PendenciaAberta(p.id(), p.categoria(), p.prazo()))
                        .toList());
        List<Alerta> alertas = regras.isEmpty() ? List.of() : MotorDeAlertas.avaliar(situacao, regras, agora);
        List<ConteudoPassagem.PendenciaPassagem> pendencias = c.pendencias().stream()
                .map(p -> new ConteudoPassagem.PendenciaPassagem(p.id(), p.versao(), p.categoria(), p.criticidade(),
                        p.prazo(), agora.isAfter(p.prazo()), p.responsavelUsuarioId(), p.responsavelSetorId(),
                        p.responsavelPapel()))
                .toList();
        boolean critico = !alertas.isEmpty()
                || c.pendencias().stream().anyMatch(p -> p.criticidade() == CriticidadeOperacional.CRITICA);
        boolean transferencia = c.protocoloRegistrado() || c.destinoDefinido()
                || c.natureza() == NaturezaEtapa.ACEITO || c.natureza() == NaturezaEtapa.TRANSPORTE;
        return new ConteudoPassagem.CasoPassagem(c.episodioId(), c.versao(), c.etapaId(), c.setorId(), c.motivoId(),
                c.categoria(), c.bloqueioDesde(), c.entradaEm(), c.etapaDesde(), critico, transferencia,
                alertas.stream().map(a -> new ConteudoPassagem.AlertaPassagem(a.regraId(), a.regraVersao(), a.tipo(),
                        a.referenciaEm(), a.atingidoEm(), a.pendenciaId())).toList(),
                pendencias);
    }
}
