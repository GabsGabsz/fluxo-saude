# ADR-0003 — Auditoria imutável com cadeia de hash, capturada no banco

- **Status:** aceito e implementado (`V3`, `V6`)
- **Data:** 2026-10-05
- **Requisitos:** RN-004, RN-010, RNF-002, RNF-010, CA-10, ERS §16

## Contexto

Toda alteração relevante precisa de rastro confiável de *quem, quando, de onde e o quê*.
Auditoria feita só pela aplicação depende de nenhum desenvolvedor esquecer uma chamada, e
pode ser apagada por quem tem acesso à tabela.

## Decisão

1. **Captura por trigger** (`auditoria.tg_capturar`) em todas as tabelas de negócio:
   INSERT/UPDATE/DELETE geram registro com o *diff* (só colunas alteradas). Nenhum caminho
   de código escapa — inclusive SQL manual feito pela aplicação.
2. **Falha fechada:** escrita pelo papel da aplicação sem `fluxo.usuario_id` no contexto é
   recusada (`42501`).
3. **Imutabilidade em três camadas:** (a) o papel da aplicação só tem `SELECT` e funções
   `SECURITY DEFINER` específicas; (b) triggers bloqueiam UPDATE/DELETE/TRUNCATE até para o
   dono; (c) **cadeia SHA-256**: cada registro inclui o hash do anterior.
   `auditoria.verificar_cadeia()` detecta alteração de conteúdo e remoção de elos — testado
   simulando um DBA mal-intencionado (`o01_auditoria_adulteracao.sql`).
4. **Encadeamento serializado:** a linha única `auditoria.cadeia_cabeca` é bloqueada
   (`FOR UPDATE`) por quem acrescenta um elo; o ID é atribuído sob o bloqueio. A aplicação não
   tem privilégio sobre essa tabela, logo não consegue segurar o bloqueio de propósito.
   A cabeça também permite detectar remoção dos últimos registros; o primeiro registro deve ter
   `hash_anterior` nulo (detecta remoção do início). Exige `READ COMMITTED`.
   Teste de concorrência: 8 sessões paralelas, cadeia íntegra.
   Coluna `origem` (`BANCO`/`APLICACAO`) entra no hash: a aplicação não consegue forjar um
   registro com aparência de captura automática.
5. **Minimização (LGPD):** colunas sensíveis (nome, CNS, textos livres, hash de senha) são
   gravadas como `"[redigido]"` — registra-se *que* mudou, não o valor. O log imutável não
   pode virar uma segunda cópia permanente de dados pessoais.
6. Eventos de aplicação (consulta sensível, exportação) via `auditoria.registrar`: exige
   usuário no contexto, autor é sempre o do contexto, unidade precisa estar no contexto, ações
   `CRIAR/ALTERAR/EXCLUIR/LOGIN_*` são reservadas. Eventos de login são gravados apenas pelas
   funções de autenticação (`fluxo.registrar_tentativa_login`).
7. A leitura da auditoria pela aplicação também está sob **RLS por unidade**.

## Consequências e riscos aceitos

- (−) **Gargalo de escrita:** o lock serializa transações que auditam, até o COMMIT. Para o
  porte do MVP (dezenas de usuários) é irrelevante; por isso o papel da aplicação tem
  `idle_in_transaction_session_timeout=30s` e `lock_timeout=5s`. Evolução possível em escala:
  selagem assíncrona em lote.
- (−) O **dono das tabelas** (`fluxo_owner`) ou um superusuário pode desabilitar triggers e
  reescrever *toda* a cadeia (inclusive a cabeça). Mitigações: (a) a credencial do dono só
  existe no job de migração, nunca no processo da aplicação; (b) planejado: exportar
  periodicamente o hash da cabeça para local externo (âncora) e monitorar
  `verificar_cadeia()`; (c) em produção, avaliar dono separado para o esquema `auditoria`
  (operação de DBA).
- (−) Retenção: não é possível apagar registros antigos sem quebrar a cadeia. Quando a
  política institucional de retenção for definida, implementar particionamento por período
  com "selo" de fechamento (hash final do período) antes do arquivamento.
