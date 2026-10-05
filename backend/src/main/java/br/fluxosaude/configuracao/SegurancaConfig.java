package br.fluxosaude.configuracao;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Postura inicial NEGAR TUDO: até o módulo de autenticação (próxima etapa, ADR-0002)
 * existir, nenhum endpoint de negócio é acessível. Apenas o health check é público.
 * Também impede o Spring Boot de criar o usuário padrão com senha gerada.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class SegurancaConfig {

    @Bean
    SecurityFilterChain filtroPadrao(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().denyAll())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    @Bean
    UserDetailsService semUsuariosAteOModuloDeAutenticacao() {
        return login -> {
            throw new UsernameNotFoundException("autenticação ainda não habilitada");
        };
    }
}
