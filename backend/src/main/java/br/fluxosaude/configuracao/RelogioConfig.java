package br.fluxosaude.configuracao;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Relógio único e injetável (UTC). O domínio nunca chama Instant.now() diretamente. */
@Configuration(proxyBeanMethods = false)
class RelogioConfig {

    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }
}
