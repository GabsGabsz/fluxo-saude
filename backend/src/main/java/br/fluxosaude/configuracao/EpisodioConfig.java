package br.fluxosaude.configuracao;

import br.fluxosaude.compartilhado.UuidV7;
import br.fluxosaude.episodio.aplicacao.ServicoConsultas;
import br.fluxosaude.episodio.aplicacao.ServicoEpisodios;
import br.fluxosaude.episodio.aplicacao.ServicoPendencias;
import br.fluxosaude.episodio.aplicacao.Transacao;
import br.fluxosaude.episodio.infra.TransacaoJdbc;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Montagem do módulo de episódios (núcleo puro + adaptador JDBC). IDs em UUIDv7. */
@Configuration(proxyBeanMethods = false)
class EpisodioConfig {

    @Bean
    Transacao transacaoEpisodios(ExecutorTransacional executor) {
        return new TransacaoJdbc(executor);
    }

    @Bean
    ServicoEpisodios servicoEpisodios(Transacao transacao, Clock relogio) {
        return new ServicoEpisodios(transacao, relogio, UuidV7::gerar);
    }

    @Bean
    ServicoPendencias servicoPendencias(Transacao transacao, Clock relogio) {
        return new ServicoPendencias(transacao, relogio, UuidV7::gerar);
    }

    @Bean
    ServicoConsultas servicoConsultas(Transacao transacao) {
        return new ServicoConsultas(transacao);
    }
}
