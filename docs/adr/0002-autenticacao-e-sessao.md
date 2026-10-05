# ADR-0002 — Autenticação, sessão e autorização

- **Status:** aceito e implementado (etapa 2: `V6`, `V7`, `V8`, módulo `identidade`)
- **Data:** 2026-10-05
- **Requisitos:** RF-001, RNF-001, RNF-002, RNF-013, RN-015, CA-10, CA-11, ERS §3 e §16

## Contexto

Uso em plantão (desktop/tablet compartilhados no setor), dados de saúde, risco explícito de
credencial compartilhada (ERS §16). SSO institucional pode não existir no início.

## Decisões

1. **Sessão no servidor** (Spring Session JDBC, esquema `sessao`), cookie `__Host-FLUXO`
   `HttpOnly`/`Secure`/`SameSite=Strict` — e **não** JWT no navegador: revogação imediata, sem
   token roubável via XSS, expiração controlada no servidor.
2. **Login JSON** (`POST /api/sessao`), sem form login. A cada login a sessão anterior é
   invalidada e uma nova é criada (fixação de sessão); a troca de senha muda o ID da sessão.
3. **CSRF** ativo em modo SPA (cookie `XSRF-TOKEN` + cabeçalho `X-XSRF-TOKEN`), inclusive no
   login. O token é **rotacionado** após login e troca de senha; o cliente sempre envia o valor
   atual do cookie.
4. **Senhas:** Argon2id (m=19 MiB, t=2, p=1 — mínimo OWASP), política NIST 800-63B
   (≥ 12 caracteres, sem regras de composição, bloqueio de senhas comuns e derivadas de login/nome,
   normalização NFKC), troca obrigatória no primeiro acesso. No máximo 4 verificações Argon2
   simultâneas (memória); excedente aguarda até 3 s e recebe 503.
5. **Força bruta:**
   - por conta, no banco: bloqueio progressivo (5 falhas → 5 min, 10 → 10 min… teto 16×);
     tentativas durante o bloqueio são auditadas mas **não** prolongam o bloqueio;
   - por origem, em memória: conta só **falhas** (sucesso não zera), por IP (30/5 min, folgado
     para NAT) e por IP+login (5/5 min). Estado por instância — com mais de uma instância,
     mover para armazenamento compartilhado;
   - respostas idênticas para login inexistente, senha errada, conta bloqueada/inativa; sempre
     há verificação de hash (tempo equalizado).
6. **Expiração:** inatividade 30 min; validade absoluta 12 h (falha fechada); no máximo 2
   sessões por usuário (as mais antigas são encerradas); troca de senha encerra as demais.
   Limite de sessões tem corrida benigna entre dois logins simultâneos (aceita).
7. **Autorização em duas camadas:**
   - **aplicação:** por *permissão*, derivada de `MatrizPermissoes` (perfil → permissões, ERS §3,
     mínimo privilégio) na **unidade ativa**; o usuário atua numa unidade por vez;
   - **banco, a cada transação:** `fluxo.aplicar_contexto` confere que o usuário segue ativo e
     lotado na unidade com os mesmos papéis da sessão; se não, a sessão é revogada (401
     `SESSAO_REVOGADA`) — desativar um usuário corta o acesso na requisição seguinte.
8. **Hash de senha protegido no banco:** a aplicação não lê nem altera `senha_hash` por SQL
   (privilégio por coluna); troca só pelo próprio usuário via `fluxo.alterar_senha_propria`.
9. **Auditoria de autenticação:** `LOGIN_SUCESSO`, `LOGIN_FALHA` (incl. login inexistente, sem
   gravar o texto digitado), `CONTA_BLOQUEADA`, `SENHA_ATUAL_INCORRETA`, `UNIDADE_ATIVA_ALTERADA`,
   `LOGOUT`, e alterações de cadastro por trigger.
10. **IP de origem:** `getRemoteAddr()`. Atrás de proxy, habilitar `forward-headers-strategy` com
    `internal-proxies` restrito ao IP do proxy (senão o IP seria forjável).
11. **Pendente:** MFA (TOTP) obrigatório para ADMINISTRADOR e AUDITORIA quando a infraestrutura
    permitir (RNF-013); SSO/OIDC como alternativa de login; gestão de usuários (etapa seguinte),
    que deverá encerrar sessões ao desativar/alterar lotação (o banco já revoga por requisição).

## Consequências

- (+) Revogação real e imediata; nenhuma credencial de longa duração no cliente.
- (−) Uma consulta extra por transação (`aplicar_contexto`) — barata (índices de PK).
- (−) Mudança incompatível na classe do principal (`UsuarioAutenticado`) invalida sessões
  persistidas: no deploy dessas versões, limpar `sessao.spring_session` (todos fazem login de novo).
- (−) Integrações máquina-a-máquina (M09) precisarão de credencial própria, nunca usuário humano.
