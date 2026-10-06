package br.fluxosaude.alerta.dominio;

import static br.fluxosaude.compartilhado.RegraVioladaException.exigir;

import br.fluxosaude.compartilhado.Textos;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Regra de alerta da unidade (RN-006, RF-027). É PARÂMETRO institucional: nenhum valor é
 * pré-definido pelo sistema (RN-014). Os mesmos limites são conferidos pelo banco (V13).
 *
 * @param etapaId      só para tipos de tempo; nulo = qualquer etapa
 * @param categoria    só para TEMPO_BLOQUEADO e PENDENCIA_VENCIDA; nula = qualquer categoria
 * @param limite       obrigatório, exceto em PENDENCIA_VENCIDA (usa o prazo da pendência)
 * @param acaoEsperada texto operacional opcional exibido no painel (CA-06), nunca conduta clínica
 */
public record RegraAlerta(UUID id, String nome, TipoRegraAlerta tipo, UUID etapaId, CategoriaBloqueio categoria,
                          Duration limite, String acaoEsperada, boolean ativa, int versao) {

    public static final Duration LIMITE_MINIMO = Duration.ofMinutes(1);
    public static final Duration LIMITE_MAXIMO = Duration.ofDays(30);

    public RegraAlerta {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tipo, "tipo");
        nome = Textos.obrigatorio(nome, "Nome da regra", 3, 120);
        acaoEsperada = Textos.opcional(acaoEsperada, "Ação esperada", 3, 200);
        if (tipo.exigeLimite()) {
            exigir(limite != null, "LIMITE_OBRIGATORIO", "Informe o limite de tempo da regra");
            exigir(limite.compareTo(LIMITE_MINIMO) >= 0 && limite.compareTo(LIMITE_MAXIMO) <= 0, "LIMITE_INVALIDO",
                    "O limite deve estar entre 1 minuto e 30 dias");
        } else {
            exigir(limite == null, "LIMITE_INDEVIDO", "Pendência vencida usa o prazo da própria pendência");
        }
        exigir(etapaId == null || tipo.aceitaEtapa(), "ETAPA_INDEVIDA", "Este tipo de regra não se restringe a etapa");
        exigir(categoria == null || tipo.aceitaCategoria(), "CATEGORIA_INDEVIDA",
                "Este tipo de regra não se restringe a categoria");
    }
}
