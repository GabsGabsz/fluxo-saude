package br.fluxosaude.configuracao;

import br.fluxosaude.alerta.aplicacao.ServicoAlertas;
import br.fluxosaude.alerta.infra.TransacaoAlertasJdbc;
import br.fluxosaude.compartilhado.UuidV7;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Montagem do módulo de alertas (núcleo puro + adaptador JDBC; relógio do servidor). */
@Configuration(proxyBeanMethods = false)
class AlertasConfig {

    @Bean
    ServicoAlertas servicoAlertas(ExecutorTransacional executor, Clock relogio) {
        return new ServicoAlertas(new TransacaoAlertasJdbc(executor), relogio, UuidV7::gerar);
    }
}
