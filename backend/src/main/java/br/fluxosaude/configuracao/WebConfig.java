package br.fluxosaude.configuracao;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class WebConfig implements WebMvcConfigurer {

    private final HandlerInterceptor interceptadorSessao;

    WebConfig(@org.springframework.beans.factory.annotation.Qualifier("interceptadorSessao")
              HandlerInterceptor interceptadorSessao) {
        this.interceptadorSessao = interceptadorSessao;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptadorSessao).addPathPatterns("/api/**");
    }
}
