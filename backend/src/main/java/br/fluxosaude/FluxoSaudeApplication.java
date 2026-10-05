package br.fluxosaude;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class FluxoSaudeApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(FluxoSaudeApplication.class, args);
        // Perfil "migracao": o Flyway já executou durante a inicialização; encerra o processo.
        if (ctx.getEnvironment().matchesProfiles("migracao")) {
            System.exit(SpringApplication.exit(ctx));
        }
    }
}
