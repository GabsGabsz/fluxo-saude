package br.fluxosaude.episodio.dominio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FluxoConfiguradoTest {

    private static Etapa etapa(String c, NaturezaEtapa n, TipoDesfecho d, boolean inicial) {
        return new Etapa(UUID.randomUUID(), c, c, n, d, inicial, false, false, false, true);
    }

    @Test
    void exigeExatamenteUmaEtapaInicialAtiva() {
        Etapa a = etapa("A", NaturezaEtapa.ATENDIMENTO, null, false);
        assertThrows(IllegalStateException.class,
                () -> new FluxoConfigurado(UUID.randomUUID(), List.of(a), List.of(), List.of(), PoliticaTempo.PADRAO));
        Etapa b = etapa("B", NaturezaEtapa.ATENDIMENTO, null, true);
        Etapa c = etapa("C", NaturezaEtapa.ATENDIMENTO, null, true);
        assertThrows(IllegalStateException.class,
                () -> new FluxoConfigurado(UUID.randomUUID(), List.of(b, c), List.of(), List.of(), PoliticaTempo.PADRAO));
    }

    @Test
    void desfechoETerminal() {
        Etapa ini = etapa("INI", NaturezaEtapa.ATENDIMENTO, null, true);
        Etapa alta = etapa("ALTA", NaturezaEtapa.DESFECHO, TipoDesfecho.ALTA, false);
        assertThrows(IllegalStateException.class, () -> new FluxoConfigurado(UUID.randomUUID(), List.of(ini, alta),
                List.of(new FluxoConfigurado.Transicao(alta.id(), ini.id())), List.of(), PoliticaTempo.PADRAO));
        FluxoConfigurado f = new FluxoConfigurado(UUID.randomUUID(), List.of(ini, alta),
                List.of(new FluxoConfigurado.Transicao(ini.id(), alta.id())), List.of(), PoliticaTempo.PADRAO);
        assertTrue(f.transicaoPermitida(ini.id(), alta.id()));
        assertFalse(f.transicaoPermitida(alta.id(), ini.id()));
    }

    @Test
    void etapaIncoerenteEhRejeitada() {
        assertThrows(IllegalArgumentException.class, () -> etapa("X", NaturezaEtapa.DESFECHO, null, false));
        assertThrows(IllegalArgumentException.class, () -> etapa("X", NaturezaEtapa.ESPERA, TipoDesfecho.ALTA, false));
        assertThrows(IllegalArgumentException.class,
                () -> new MotivoBloqueio(UUID.randomUUID(), CategoriaBloqueio.OUTROS, "O", "o", false, true));
    }

    @Test
    void transicaoParaEtapaDeOutraConfiguracaoEhRejeitada() {
        Etapa ini = etapa("INI", NaturezaEtapa.ATENDIMENTO, null, true);
        assertThrows(IllegalStateException.class, () -> new FluxoConfigurado(UUID.randomUUID(), List.of(ini),
                List.of(new FluxoConfigurado.Transicao(ini.id(), UUID.randomUUID())), List.of(), PoliticaTempo.PADRAO));
    }
}
