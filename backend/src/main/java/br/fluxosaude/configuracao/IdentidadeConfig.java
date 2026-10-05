package br.fluxosaude.configuracao;

import br.fluxosaude.identidade.aplicacao.HashDeSenha;
import br.fluxosaude.identidade.aplicacao.ServicoAutenticacao;
import br.fluxosaude.identidade.dominio.LimitadorDeTentativas;
import br.fluxosaude.identidade.infra.CredenciaisJdbc;
import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Montagem do módulo de identidade (núcleo puro + adaptadores). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropriedadesSeguranca.class)
class IdentidadeConfig {

    @Bean
    HashDeSenha hashDeSenha(PropriedadesSeguranca p) {
        return new HashDeSenhaArgon2(p.maxHashesSimultaneos());
    }

    @Bean
    CredenciaisJdbc credenciais(ExecutorTransacional executor, PropriedadesSeguranca p) {
        return new CredenciaisJdbc(executor, p.maxFalhasConta(), p.bloqueioConta());
    }

    @Bean
    ServicoAutenticacao servicoAutenticacao(CredenciaisJdbc credenciais, HashDeSenha hash,
                                            PropriedadesSeguranca p, Clock relogio) {
        return new ServicoAutenticacao(credenciais, hash, new ServicoAutenticacao.Limites(
                new LimitadorDeTentativas(p.maxFalhasPorOrigem(), p.janelaTentativas(), 50_000, relogio),
                new LimitadorDeTentativas(p.maxFalhasPorOrigemELogin(), p.janelaTentativas(), 50_000, relogio),
                new LimitadorDeTentativas(p.maxFalhasTrocaSenha(), p.janelaTrocaSenha(), 50_000, relogio),
                p.janelaTentativas()), relogio);
    }
}
