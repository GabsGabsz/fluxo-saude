package br.fluxosaude.configuracao;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parâmetros de segurança (ADR-0002). Valores padrão são técnicos e devem ser
 * confirmados na implantação (V-10).
 *
 * @param maxFalhasPorOrigem         falhas de login por IP na janela (força bruta distribuída por contas;
 *                                   folgado porque, atrás de NAT, toda a unidade pode compartilhar o IP)
 * @param maxFalhasPorOrigemELogin   falhas por IP+login na janela
 * @param janelaTentativas           janela dos limites de login
 * @param maxFalhasTrocaSenha        senhas atuais erradas por usuário (sessão roubada) na janela de troca
 * @param janelaTrocaSenha           janela do limite de troca de senha
 * @param maxFalhasConta          falhas consecutivas que bloqueiam a conta (bloqueio progressivo no banco)
 * @param bloqueioConta           duração base do bloqueio de conta
 * @param maxSessoesPorUsuario    sessões simultâneas (ex.: desktop + tablet)
 * @param duracaoAbsolutaSessao   validade máxima da sessão, mesmo com uso contínuo (um plantão)
 * @param maxHashesSimultaneos    verificações Argon2 em paralelo (memória ~19 MiB cada)
 */
@ConfigurationProperties("fluxo.seguranca")
public record PropriedadesSeguranca(
        @DefaultValue("30") int maxFalhasPorOrigem,
        @DefaultValue("5") int maxFalhasPorOrigemELogin,
        @DefaultValue("5m") Duration janelaTentativas,
        @DefaultValue("5") int maxFalhasTrocaSenha,
        @DefaultValue("15m") Duration janelaTrocaSenha,
        @DefaultValue("5") int maxFalhasConta,
        @DefaultValue("5m") Duration bloqueioConta,
        @DefaultValue("2") int maxSessoesPorUsuario,
        @DefaultValue("12h") Duration duracaoAbsolutaSessao,
        @DefaultValue("4") int maxHashesSimultaneos) {
}
