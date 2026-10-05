# ADR-0002 — Autenticação e sessão

- **Status:** proposto (implementação na próxima etapa)
- **Data:** 2026-10-05
- **Requisitos:** RF-001, RNF-001, RNF-002, RNF-013, ERS §16

## Contexto

Uso em plantão (desktop/tablet compartilhados no setor), dados de saúde, risco explícito de
credencial compartilhada (ERS §16). SSO institucional pode não existir no início.

## Decisão

1. **Sessão no servidor** (cookie `HttpOnly`, `Secure`, `SameSite=Strict`) persistida em
   PostgreSQL via Spring Session JDBC — e **não** JWT no navegador. Motivos: revogação
   imediata (logout, bloqueio de usuário, troca de perfil), sem token roubável via XSS,
   expiração controlada no servidor.
2. **CSRF** ativo (token sincronizado) para todas as requisições que alteram estado.
3. **Senhas:** Argon2id (`DelegatingPasswordEncoder`, prefixo `{argon2@SpringSecurity_v5_8}`),
   mínimo de 12 caracteres, troca obrigatória no primeiro acesso (`deve_trocar_senha`),
   verificação contra lista de senhas comuns.
4. **Força bruta:** bloqueio progressivo por conta (`usuario_acesso.falhas_consecutivas`,
   `bloqueado_ate`) + limite por IP; respostas de erro idênticas para usuário inexistente
   e senha errada (sem enumeração); comparação em tempo constante.
5. **Expiração:** inatividade 30 min, absoluta 12 h (um plantão). Rotação do ID de sessão
   no login (fixação de sessão).
6. **Sessões concorrentes:** no máximo 2 por usuário (desktop + tablet), configurável.
7. **Contexto por transação:** após autenticar, cada transação executa
   `set_config('fluxo.usuario_id' | 'fluxo.unidade_ids' | 'fluxo.origem_ip' | 'fluxo.correlacao_id', …, true)`
   — base do RLS (ADR-0004) e da auditoria (ADR-0003).
8. **Auditoria de autenticação** (já implementada no banco, V6): `fluxo.credencial_para_login`
   (busca por login, sem RLS, só leitura) e `fluxo.registrar_tentativa_login` (bloqueio
   progressivo + eventos `LOGIN_SUCESSO`, `LOGIN_FALHA`, `CONTA_BLOQUEADA`). O login digitado
   em tentativa de usuário inexistente não é gravado (pode ser uma senha no campo errado).
9. **MFA (RNF-013):** TOTP obrigatório para perfis privilegiados (ADMINISTRADOR, AUDITORIA)
   quando a infraestrutura institucional permitir; opcional para os demais.
10. **SSO (OIDC)** quando a instituição tiver provedor: entra como alternativa de login, sem
   mudar o modelo de sessão/contexto.

## Consequências

- (+) Revogação real; nenhuma credencial de longa duração no cliente.
- (−) Sessão em banco adiciona uma escrita por requisição autenticada (aceitável no porte).
- (−) Integrações máquina-a-máquina (M09) precisarão de credencial própria (client credentials
  ou mTLS), nunca usuário humano compartilhado.
