package br.fluxosaude.configuracao;

import br.fluxosaude.compartilhado.UuidV7;
import br.fluxosaude.identidade.aplicacao.GeradorSenhaProvisoria;
import br.fluxosaude.identidade.aplicacao.HashDeSenha;
import br.fluxosaude.identidade.aplicacao.ServicoGestaoUsuarios;
import br.fluxosaude.identidade.aplicacao.SessoesPort;
import br.fluxosaude.identidade.infra.TransacaoUsuariosJdbc;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Montagem da gestão de usuários (só na aplicação web: depende das sessões HTTP). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class GestaoUsuariosConfig {

    @Bean
    ServicoGestaoUsuarios servicoGestaoUsuarios(ExecutorTransacional executor, HashDeSenha hash, SessoesPort sessoes) {
        return new ServicoGestaoUsuarios(new TransacaoUsuariosJdbc(executor), hash, sessoes,
                new GeradorSenhaProvisoria(), UuidV7::gerar);
    }
}
