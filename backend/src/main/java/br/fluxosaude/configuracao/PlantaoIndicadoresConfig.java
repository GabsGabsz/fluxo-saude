package br.fluxosaude.configuracao;

import br.fluxosaude.compartilhado.UuidV7;
import br.fluxosaude.indicador.aplicacao.ServicoIndicadores;
import br.fluxosaude.indicador.infra.TransacaoIndicadoresJdbc;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import br.fluxosaude.plantao.aplicacao.ServicoPlantao;
import br.fluxosaude.plantao.infra.TransacaoPlantaoJdbc;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Montagem da passagem de plantão e dos indicadores (núcleo puro + adaptadores JDBC; relógio do servidor). */
@Configuration(proxyBeanMethods = false)
class PlantaoIndicadoresConfig {

    @Bean
    ServicoPlantao servicoPlantao(ExecutorTransacional executor, Clock relogio) {
        return new ServicoPlantao(new TransacaoPlantaoJdbc(executor), relogio, UuidV7::gerar);
    }

    @Bean
    ServicoIndicadores servicoIndicadores(ExecutorTransacional executor, Clock relogio) {
        return new ServicoIndicadores(new TransacaoIndicadoresJdbc(executor), relogio);
    }
}
