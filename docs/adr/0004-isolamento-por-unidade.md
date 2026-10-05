# ADR-0004 — Isolamento por unidade com Row Level Security e mínimo privilégio

- **Status:** aceito e implementado (`V1`, `V6`, `infra/db/init`)
- **Data:** 2026-10-05
- **Requisitos:** RNF-001, RNF-003, RNF-011, RF-034, CA-11, ERS §3 (mínimo privilégio)

## Decisão

1. **Dois papéis de banco:** `fluxo_owner` (dono, só migrações) e `fluxo_app` (aplicação:
   sem DDL, sem DELETE em dados de negócio, sem superusuário, sem `BYPASSRLS`).
2. **RLS** em toda tabela com `unidade_id`: o papel da aplicação só vê/escreve linhas cujas
   unidades estão em `fluxo.unidade_ids` da transação. Sem contexto ⇒ zero linhas;
   contexto malformado ⇒ erro. `WITH CHECK` impede "mover" registro para outra unidade.
3. **FKs compostas** `(unidade_id, id)` garantem que episódio, etapa, motivo, setor e
   paciente referenciados são da mesma unidade.
4. **Autorização por perfil continua na aplicação** (ERS §3); RLS é a rede de segurança
   contra bug de filtro esquecido, não substitui o controle por perfil.
5. Contexto definido com `set_config(..., true)` (escopo de transação): seguro com pool de
   conexões.

## Limites conhecidos (honestidade técnica)

- RLS **não protege contra injeção de SQL**: quem injeta SQL pode chamar `set_config`. A
  proteção contra SQLi é usar exclusivamente consultas parametrizadas (regra de revisão).
- `usuario` tem RLS: visível/alterável só o próprio usuário e os lotados nas unidades do
  contexto. Um usuário lotado em duas unidades pode ser alterado por administradores de
  ambas (autorização fina por perfil fica na aplicação).
- `usuario_acesso` (tentativas/bloqueio) não é acessível pela aplicação: apenas pelas funções
  `credencial_para_login`, `registrar_tentativa_login` e `desbloquear_usuario`.
- A aplicação confia em si mesma para informar o resultado da verificação de senha
  (`registrar_tentativa_login`): quem controla o processo da aplicação pode registrar
  "sucesso". Isso é inerente a qualquer verificação de senha feita na aplicação.
- O paciente é cadastrado **por unidade**. Deduplicação regional (por CNS) é tema da fase de
  escala (RF-034).
