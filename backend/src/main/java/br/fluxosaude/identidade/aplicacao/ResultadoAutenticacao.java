package br.fluxosaude.identidade.aplicacao;

import br.fluxosaude.identidade.dominio.UsuarioAutenticado;

/**
 * Resultado do login. Toda falha é externamente IDÊNTICA (mesma mensagem, mesmo status),
 * para não revelar se o login existe, se a conta está bloqueada ou inativa. O motivo
 * interno serve apenas a métricas/testes e nunca vai para a resposta HTTP.
 */
public sealed interface ResultadoAutenticacao {

    record Sucesso(UsuarioAutenticado usuario) implements ResultadoAutenticacao {
    }

    record Falha(Motivo motivo) implements ResultadoAutenticacao {
    }

    /** Excesso de tentativas da mesma origem (HTTP 429). */
    record LimiteExcedido() implements ResultadoAutenticacao {
    }

    enum Motivo { CREDENCIAIS_INVALIDAS, CONTA_BLOQUEADA, CONTA_INATIVA, SEM_LOTACAO }
}
