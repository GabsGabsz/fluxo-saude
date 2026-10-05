package br.fluxosaude.identidade.aplicacao;

import br.fluxosaude.compartilhado.LimiteExcedidoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.dominio.LimitadorDeTentativas;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.PoliticaSenha;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Casos de uso de autenticação (RF-001, RNF-001, RNF-013). Sem dependência de framework.
 *
 * <p>Proteções: limites de FALHAS por origem e por origem+login (memória); bloqueio
 * progressivo por conta (banco); tempo de resposta equalizado (sempre há verificação de
 * hash); resposta idêntica para qualquer falha; auditoria de toda tentativa.
 */
public final class ServicoAutenticacao {

    /** Limites usados pelo serviço; valores vêm da configuração. */
    public record Limites(LimitadorDeTentativas porOrigem, LimitadorDeTentativas porOrigemELogin,
                          LimitadorDeTentativas trocaDeSenhaPorUsuario, Duration janela) {
        public Limites {
            Objects.requireNonNull(porOrigem);
            Objects.requireNonNull(porOrigemELogin);
            Objects.requireNonNull(trocaDeSenhaPorUsuario);
            Objects.requireNonNull(janela);
        }
    }

    private static final Pattern FORMATO_LOGIN = Pattern.compile("^[a-z0-9._-]{3,64}$");

    private final CredenciaisPort credenciais;
    private final HashDeSenha hash;
    private final Limites limites;
    private final Clock clock;

    public ServicoAutenticacao(CredenciaisPort credenciais, HashDeSenha hash, Limites limites, Clock clock) {
        this.credenciais = Objects.requireNonNull(credenciais);
        this.hash = Objects.requireNonNull(hash);
        this.limites = Objects.requireNonNull(limites);
        this.clock = Objects.requireNonNull(clock);
    }

    public ResultadoAutenticacao autenticar(String loginBruto, String senhaBruta, ContextoOrigem origem) {
        String login = loginBruto == null ? "" : loginBruto.strip().toLowerCase(Locale.ROOT);
        String chaveOrigem = origem.ip();
        String chaveOrigemLogin = origem.ip() + "|" + (login.length() > 64 ? login.substring(0, 64) : login);
        if (!limites.porOrigem().permitido(chaveOrigem) || !limites.porOrigemELogin().permitido(chaveOrigemLogin)) {
            return new ResultadoAutenticacao.LimiteExcedido();
        }

        ResultadoAutenticacao resultado = verificar(login, senhaBruta, origem);
        if (resultado instanceof ResultadoAutenticacao.Falha) {
            limites.porOrigem().registrarFalha(chaveOrigem);
            limites.porOrigemELogin().registrarFalha(chaveOrigemLogin);
        }
        return resultado;
    }

    private ResultadoAutenticacao verificar(String login, String senhaBruta, ContextoOrigem origem) {
        String senha = PoliticaSenha.normalizar(senhaBruta == null ? "" : senhaBruta);
        // Senha absurdamente longa não chega ao Argon2 (custo); o tempo continua equalizado.
        boolean entradaValida = FORMATO_LOGIN.matcher(login).matches() && senha.length() <= PoliticaSenha.MAXIMO * 4;

        Optional<CredenciaisPort.Credencial> credencial =
                entradaValida ? credenciais.buscarPorLogin(login, origem) : Optional.empty();

        if (credencial.isEmpty()) {
            hash.confere(entradaValida ? senha : "", hash.hashFicticio()); // equaliza tempo
            credenciais.registrarTentativa(null, false, origem);
            return new ResultadoAutenticacao.Falha(ResultadoAutenticacao.Motivo.CREDENCIAIS_INVALIDAS);
        }

        CredenciaisPort.Credencial c = credencial.get();
        boolean senhaConfere = hash.confere(senha, c.senhaHash());
        boolean bloqueada = c.bloqueadoAte() != null && c.bloqueadoAte().isAfter(clock.instant());

        if (bloqueada) {
            // Não incrementa falhas: tentativas durante o bloqueio não o prolongam (evita que um
            // atacante mantenha a vítima bloqueada). A tentativa é auditada à parte.
            credenciais.registrarTentativaDuranteBloqueio(c.usuarioId(), origem);
            return new ResultadoAutenticacao.Falha(ResultadoAutenticacao.Motivo.CONTA_BLOQUEADA);
        }
        if (!senhaConfere) {
            credenciais.registrarTentativa(c.usuarioId(), false, origem);
            return new ResultadoAutenticacao.Falha(ResultadoAutenticacao.Motivo.CREDENCIAIS_INVALIDAS);
        }
        if (!c.ativo()) {
            credenciais.registrarTentativa(c.usuarioId(), false, origem);
            return new ResultadoAutenticacao.Falha(ResultadoAutenticacao.Motivo.CONTA_INATIVA);
        }
        Map<UUID, Set<Papel>> lotacoes = credenciais.lotacoes(c.usuarioId(), origem);
        if (lotacoes.isEmpty()) {
            credenciais.registrarTentativa(c.usuarioId(), false, origem);
            return new ResultadoAutenticacao.Falha(ResultadoAutenticacao.Motivo.SEM_LOTACAO);
        }

        credenciais.registrarTentativa(c.usuarioId(), true, origem);
        CredenciaisPort.Perfil perfil = credenciais.perfil(c.usuarioId(), origem);
        UUID unidadeInicial = lotacoes.keySet().stream().sorted().findFirst().orElseThrow();
        return new ResultadoAutenticacao.Sucesso(new UsuarioAutenticado(
                c.usuarioId(), perfil.login(), perfil.nome(), lotacoes, unidadeInicial, c.deveTrocarSenha()));
    }

    /** Troca de senha pelo próprio usuário (obrigatória no primeiro acesso). */
    public UsuarioAutenticado trocarSenha(UsuarioAutenticado usuario, String senhaAtual, String novaSenha,
                                         ContextoOrigem origem) {
        Objects.requireNonNull(usuario);
        String chave = usuario.usuarioId().toString();
        if (!limites.trocaDeSenhaPorUsuario().permitido(chave)) {
            throw new LimiteExcedidoException(limites.janela());
        }
        String atual = PoliticaSenha.normalizar(senhaAtual == null ? "" : senhaAtual);
        boolean confere = atual.length() <= PoliticaSenha.MAXIMO * 4
                && hash.confere(atual, credenciais.hashAtual(usuario.usuarioId(), origem));
        if (!confere) {
            limites.trocaDeSenhaPorUsuario().registrarFalha(chave);
            credenciais.registrarFalhaNaTrocaDeSenha(usuario.usuarioId(), origem);
            throw new RegraVioladaException("SENHA_ATUAL_INCORRETA", "A senha atual não confere");
        }
        PoliticaSenha.validar(novaSenha, usuario.login(), usuario.nome());
        String nova = PoliticaSenha.normalizar(novaSenha);
        RegraVioladaException.exigir(!nova.equals(atual), "SENHA_REPETIDA", "A nova senha deve ser diferente da atual");
        credenciais.gravarNovaSenha(usuario.usuarioId(), hash.gerar(nova), origem);
        return usuario.senhaTrocada();
    }
}
