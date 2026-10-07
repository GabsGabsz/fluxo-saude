package br.fluxosaude.configuracao;

import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import br.fluxosaude.relatorio.aplicacao.ServicoRelatorios;
import br.fluxosaude.relatorio.aplicacao.TokenRelatorio;
import br.fluxosaude.relatorio.infra.RegistroRelatoriosJdbc;
import br.fluxosaude.relatorio.infra.TransacaoRelatoriosJdbc;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Montagem dos relatórios gerenciais (issue #9). A chave HMAC dos comprovantes é gerada a cada
 * inicialização e nunca sai do processo (comprovantes antigos deixam de valer: recalcula-se).
 * Uma única instância da aplicação; com várias, a chave precisaria ser compartilhada (ADR-0010).
 */
@Configuration(proxyBeanMethods = false)
class RelatoriosConfig {

    @Bean
    ServicoRelatorios servicoRelatorios(ExecutorTransacional executor, Clock relogio) {
        byte[] chave = new byte[32];
        new SecureRandom().nextBytes(chave);
        return new ServicoRelatorios(new TransacaoRelatoriosJdbc(executor), new RegistroRelatoriosJdbc(executor),
                new TokenRelatorio(chave), relogio);
    }
}
