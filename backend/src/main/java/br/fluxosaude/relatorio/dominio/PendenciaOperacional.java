package br.fluxosaude.relatorio.dominio;

import java.time.Instant;
import java.util.UUID;

/**
 * Item da lista OPERACIONAL de pendências abertas (nominal): só para perfis com acesso nominal
 * (EPISODIO_VER); nunca para a Direção. {@code descricao} é a ação registrada pela equipe —
 * nenhuma ação clínica é inferida.
 */
public record PendenciaOperacional(UUID pendenciaId, UUID episodioId, String pacienteNome, String setorNome,
                                   String etapaNome, String motivoDescricao, String descricao, String categoria,
                                   String criticidade, String responsavelTipo, String responsavelNome, Instant prazo,
                                   Instant criadaEm, boolean vencida) {
}
