package br.fluxosaude.identidade.web;

import br.fluxosaude.identidade.aplicacao.SessoesPort;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Remove do Spring Session (JDBC) todas as sessões do usuário. O nome indexado do principal
 * é o ID do usuário ({@code UsuarioAutenticado.getName()}), nunca o login.
 */
@ConditionalOnWebApplication
@Component
class EncerradorDeSessoes implements SessoesPort {

    private final FindByIndexNameSessionRepository<? extends Session> sessoes;

    EncerradorDeSessoes(FindByIndexNameSessionRepository<? extends Session> sessoes) {
        this.sessoes = sessoes;
    }

    @Override
    public void encerrarTodas(UUID usuarioId) {
        List<String> ids = List.copyOf(sessoes.findByPrincipalName(usuarioId.toString()).keySet());
        ids.forEach(sessoes::deleteById);
    }
}
