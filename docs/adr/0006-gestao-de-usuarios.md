# ADR-0006 — Gestão de usuários e lotações: alcance, autoalteração e último administrador

- **Status:** proposto (PR da etapa 4), implementado em `V11` e no módulo `identidade`
- **Data:** 2026-10-06
- **Requisitos:** M08, RF-001, RNF-001, RNF-013, CA-11, RN-015 (ERS v1.1 §3)

## Contexto

A ERS dá ao Administrador a gestão de "configurações, usuários e auditoria" (§3), mas não
define o alcance dessa gestão quando um profissional atua em mais de uma unidade, nem as
regras de autoalteração e de remoção do último administrador. Até a V10, a aplicação tinha
DML direto em `usuario` e `lotacao`: a autorização administrativa existia só no Java.

## Decisão

### 1. Dois alcances distintos

| Operação | Escopo | Quem pode |
|---|---|---|
| Conceder, alterar, revogar papéis; vincular conta existente | **lotação** na unidade ativa | ADMINISTRADOR ativo da unidade ativa |
| Alterar nome/e-mail/registro; desativar/reativar; senha provisória | **conta** (vale em todas as unidades) | administrador da **unidade gestora** da conta (a que a criou), com ela como unidade ativa, que também administre **todas** as unidades em que o usuário está lotado |

O administrador da unidade A revoga o acesso de um profissional **na A** (as lotações na B
permanecem e a conta segue ativa), mas não desativa a conta nem redefine a senha de quem
também atua na B — isso tiraria o acesso dele na B ou permitiria tomar a conta.

A **unidade gestora** impede a "tomada por vínculo": se a B vincula alguém criado pela A e a
A depois revoga o seu acesso, a B continua com a lotação, mas **não** passa a gerir a conta
(não redefine a senha nem altera dados). Revogar o acesso na unidade gestora encerra a gestão
da conta pela aplicação; a partir daí, operações de conta são do DBA. Contas anteriores à V11
recebem como gestora a única unidade em que estão lotadas (as multiunidade ficam sem gestora).

### 2. Autoalteração (proposta — a ERS não define)

Nenhum administrador altera os **próprios** papéis, a própria situação ou a própria conta pela
gestão de usuários; a própria senha continua em `/api/sessao/senha`. Motivo: impedir
autoconcessão de acesso nominal (o Administrador não tem `EPISODIO_VER`, ERS §3) e
autorremoção acidental. Mudanças no próprio acesso são feitas por outro administrador.

### 3. Último administrador (proposta)

Toda unidade mantém ao menos um ADMINISTRADOR **ativo**. Com a regra 2, o próprio executor é
sempre um administrador remanescente — o risco real é a **corrida** (A remove B enquanto B
remove A). O banco serializa por unidade: toda operação administrativa bloqueia a linha de
`fluxo.unidade` (em ordem de id) **antes** de reconferir que o executor ainda administra a
unidade; o gatilho de último administrador bloqueia e conta de novo. Resultado: a segunda
operação é recusada (403/422) e a unidade nunca fica sem administrador.

### 4. Vínculo de conta existente e contas órfãs

- Para dar acesso a quem já atua em outra unidade, o administrador localiza a conta pelo
  **login exato** (`/busca`), que devolve só id, versão e se é vinculável — sem nome, e-mail
  ou registro — e é auditada (sem gravar o login digitado).
- Só é vinculável conta **ativa e lotada em outra unidade**. Remover a última lotação de uma
  conta a **desativa**. Assim, contas órfãs não podem ser "adotadas" por outra unidade para
  ganhar a identidade (e a trilha de auditoria) de um ex-profissional. Reativar conta órfã é
  procedimento do DBA.

### 5. Provisionamento e senha provisória

- A conta nasce ativa e com **troca de senha obrigatória** (forçado por gatilho no banco).
- A senha provisória é gerada no servidor (CSPRNG, ~99 bits, validada pela `PoliticaSenha`),
  devolvida **uma única vez** na resposta (`Cache-Control: no-store`) e nunca registrada em log
  ou auditoria; só o hash Argon2id é persistido. Redefinir a senha zera o bloqueio por
  tentativas e encerra as sessões.

### 6. Defesa em profundidade no banco (V11)

- `REVOKE` de INSERT/UPDATE em `usuario` e INSERT/DELETE em `lotacao` para o papel da
  aplicação. Toda escrita passa por funções `SECURITY DEFINER` (`admin_*`) que bloqueiam as
  unidades envolvidas (em ordem de id) e depois o usuário-alvo (`FOR NO KEY UPDATE`),
  reconferem que o executor ainda administra a unidade, conferem alcance, autoalteração,
  versão (`40001` → 409; recusa administrativa com código próprio `FX403` → 403, distinto do
  `42501` de GRANT ausente) e registram a auditoria semântica
  (`USUARIO_CRIADO`, `ACESSO_CONCEDIDO`, `PAPEIS_ALTERADOS`, `ACESSO_REVOGADO`,
  `CONTA_ALTERADA`, `CONTA_DESATIVADA`, `CONTA_REATIVADA`, `SENHA_PROVISORIA_DEFINIDA`,
  `USUARIO_LOCALIZADO`), além da auditoria por linha já existente (hash e dados pessoais
  redigidos).
- Políticas RLS **restritivas** repetem as regras caso um GRANT seja reintroduzido por engano.
- O DBA (membro do papel dono) não é afetado — implantação e recuperação continuam possíveis.
  Qualquer outro login é tratado como aplicação (falha fechada).

### 7. Sessões

Após o COMMIT de mudança de papéis, desativação ou senha provisória, as sessões do usuário são
removidas do Spring Session (índice por ID do usuário). É reforço: mesmo sem isso,
`fluxo.aplicar_contexto` revalida usuário, lotação e papéis a cada transação (ADR-0002 §7) e
a sessão antiga recebe 401 na requisição seguinte.

## Consequências

- Usuários de várias unidades exigem coordenação entre administradores para operações de conta.
- Uma unidade pode vincular um profissional de outra (precisa do login exato) e, enquanto o
  vínculo existir, a unidade gestora não consegue desativar a conta inteira — mas continua
  podendo revogar o acesso na própria unidade. O vínculo fica auditado (`ACESSO_CONCEDIDO`).
- A busca por login revela a qualquer administrador se um login existe (não revela dados
  pessoais) e cada busca é auditada (`USUARIO_LOCALIZADO`).
- Toda operação administrativa serializa por unidade (operação rara; custo desprezível).
- **Limitação conhecida (anterior a esta etapa):** o contexto da transação (`fluxo.usuario_id`,
  `fluxo.unidade_ids`) são GUCs que o próprio papel da aplicação consegue definir. As regras do
  banco protegem contra defeitos da aplicação que usem o contexto correto, mas não contra quem
  já executa SQL arbitrário como `fluxo_app` (ex.: injeção). Mitigação atual: SQL sempre
  parametrizado e papel exclusivo da aplicação. Proposta de evolução: contexto não forjável
  (registro do contexto validado por `aplicar_contexto` em estrutura só do dono, conferido
  pelas funções `ctx_*`) — ver `docs/decisoes-a-validar.md`.
