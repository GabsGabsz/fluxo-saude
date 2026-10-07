package br.fluxosaude.plantao.dominio;

import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Pendência ABERTA no estado atual (responsável: exatamente um entre usuário, setor ou perfil). */
public record PendenciaAtual(UUID id, int versao, CategoriaBloqueio categoria, CriticidadeOperacional criticidade,
                             Instant prazo, UUID responsavelUsuarioId, UUID responsavelSetorId,
                             String responsavelPapel) {

    public PendenciaAtual {
        Objects.requireNonNull(id);
        Objects.requireNonNull(categoria);
        Objects.requireNonNull(criticidade);
        Objects.requireNonNull(prazo);
    }
}
