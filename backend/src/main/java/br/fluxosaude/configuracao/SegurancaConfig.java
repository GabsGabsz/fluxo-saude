package br.fluxosaude.configuracao;

import br.fluxosaude.infra.web.RespostaDeErro;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Segurança HTTP (ADR-0002):
 * <ul>
 *   <li>sessão no servidor (Spring Session JDBC), cookie HttpOnly/Secure/SameSite=Strict;</li>
 *   <li>CSRF ativo no modo SPA (cookie XSRF-TOKEN + cabeçalho X-XSRF-TOKEN), inclusive no login;</li>
 *   <li>tudo negado por padrão; /api exige autenticação; autorização fina por PERMISSÃO nos casos de uso;</li>
 *   <li>cabeçalhos de segurança restritivos (CSP, frame-ancestors, referrer, permissions-policy).</li>
 * </ul>
 * O login é feito pelo {@code SessaoController} (JSON), não por form login.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class SegurancaConfig {

    @Bean
    SecurityContextRepository repositorioDeContexto() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    SecurityFilterChain filtroPadrao(HttpSecurity http, SecurityContextRepository repositorio) throws Exception {
        http
            .securityContext(c -> c.securityContextRepository(repositorio))
            .csrf(c -> c.spa())
            .authorizeHttpRequests(a -> a
                // Despachos internos de erro não passam por "denyAll" (senão 403 viraria 401).
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Interface web (ADR-0008): só arquivos estáticos, só GET. Não contêm dado algum —
                // tudo vem de /api/**, que continua exigindo sessão.
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.svg", "/app/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/sessao/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/sessao").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                // Inclui falha de CSRF: 403 com corpo problem+json, sem sendError/redirect.
                .accessDeniedHandler((request, response, negado) -> RespostaDeErro.escrever(request, response, 403,
                        "ACESSO_NEGADO", "Acesso negado ou token CSRF ausente/inválido")))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            // Fixação de sessão: tratada no SessaoController (login cria sessão nova; troca de senha
            // muda o ID), pois o login é manual e não passa pelas estratégias do Spring Security.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'self'"))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")));
        return http.build();
    }

    /** Impede o Spring Boot de criar o usuário padrão com senha gerada. */
    @Bean
    UserDetailsService semUsuariosEmMemoria() {
        return login -> {
            throw new UsernameNotFoundException("autenticação é feita pelo SessaoController");
        };
    }
}
