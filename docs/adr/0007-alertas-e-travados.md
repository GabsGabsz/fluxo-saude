# ADR-0007 — Alertas operacionais e "Pacientes travados"

- **Status:** proposto (PR da etapa 5), implementado em `V13` e no módulo `alerta`
- **Data:** 2026-10-07
- **Requisitos:** M04, RF-011, RF-018, RF-022, RF-027, RN-006, RN-007, RN-008, RN-013, RN-014, CA-05, CA-06

## Contexto

A ERS pede destacar na Torre os casos que atingiram limites configurados (RF-011, CA-05) e um
painel de "Pacientes travados" definido por regras configuráveis, que mostre tempo, motivo,
responsável e ação esperada (RF-018, CA-06). Os limites são parâmetros por unidade e tipo de
fluxo (RN-006), nenhum tempo pode ser fixado no código (RN-014) e alerta operacional nunca
equivale a classificação de risco clínico (RN-007, RN-013). "Paciente travado" é categoria
operacional derivada de tempo, pendência ou falta de atualização (Anexo B).

## Decisão

1. **Regras de alerta por unidade** (`fluxo.regra_alerta`), com cinco tipos — os exemplos
   da ERS §10.2:

   | Tipo | Conta a partir de | Filtros opcionais |
   |---|---|---|
   | `TEMPO_NA_ETAPA` | início da etapa atual | etapa (ex.: "transporte atrasado" = Aguardando transporte) |
   | `TEMPO_TOTAL` | entrada do episódio | etapa |
   | `TEMPO_BLOQUEADO` | início do bloqueio atual | etapa, categoria do motivo |
   | `SEM_ATUALIZACAO` | último registro na linha do tempo (horário do servidor) | etapa |
   | `PENDENCIA_VENCIDA` | prazo da própria pendência (sem limite na regra) | categoria da pendência |

   Limite entre 1 minuto e 30 dias (faixa técnica, não institucional). **Nenhuma regra vem
   cadastrada**; os exemplos dos testes são marcados como ilustrativos. Só o Administrador
   ativo da unidade cria/altera (aplicação + política restritiva no banco); não há exclusão,
   desativa-se. Tipo e unidade não mudam. Toda alteração é versionada e auditada.
2. **Cálculo na leitura, no servidor** (`MotorDeAlertas`, puro): para cada episódio ABERTO e
   cada regra ativa. Fronteiras: limite de tempo **atingido** (`agora >= referência + limite`,
   ERS §2 "atingidos ou ultrapassados"); pendência vencida quando `agora > prazo` (mesma
   definição de "vencida" já usada pela Torre). Episódios encerrados não entram (RN-008).
   Não há alerta persistido nem tarefa agendada: o resultado é sempre coerente com o estado
   atual e com o relógio do servidor.
3. **API:** `GET /api/travados` (nominal, `EPISODIO_VER`): casos com ao menos um alerta, com
   tempos, motivo, pendências abertas (próxima ação, responsável, prazo), alertas e ação
   esperada; ordenados pelo limite atingido há mais tempo (critério operacional, não
   prioridade clínica). A Torre (`GET /api/episodios`) traz `alertas` por episódio listado. O
   painel coletivo traz só `emAlerta` (sem nome, regra ou detalhe — RNF-015).
4. **Ciência (RF-022):** `POST /api/episodios/{id}/alertas/ciencia` registra "ciente" de uma
   ocorrência **em alerta agora** (recalculada no servidor). A ocorrência é identificada por
   episódio, regra, **versão da regra**, instante de referência e pendência: mudou a situação
   ou a regra, é nova ocorrência. Não encerra pendência nem tira o caso do painel. Imutável,
   auditada, idempotente. Exige `EPISODIO_ALTERAR`.

## Fora desta etapa (dependem de definição institucional)

- **Escalonamento** (RF-023) e **notificações direcionadas** (RF-021): quem recebe cada nível e
  em quanto tempo (V-05, V-06). A ciência registrada já é a base para "sem ciência há X".
- Valores reais dos limites por etapa (V-05).

## Consequências

- Torre e painel fazem uma leitura extra (só dos episódios listados) quando há regras ativas;
  sem regras, nada é calculado.
- Uma ciência pode ficar registrada num episódio encerrado por uma transação concorrente
  (sem bloqueio, para evitar deadlock com o encerramento); é inofensivo e auditado.
- "Pacientes travados" lê até 2000 episódios abertos por unidade (com indicador `truncado`).
