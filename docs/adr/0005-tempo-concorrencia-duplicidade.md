# ADR-0005 — Horário do servidor, controle otimista e duplicidade sem bloqueio (ERS v1.1)

- **Status:** aceito e implementado (`V1`, `V2`, `V5`, domínio)
- **Data:** 2026-10-05
- **Requisitos:** RF-003, RF-036, RF-037, RF-040, RNF-014, RNF-017, RNF-018, RN-014

## 1. Tempo (RNF-017, RF-040, RNF-018)

- O **instante de registro** (`registrado_em`, `criado_em`, `criada_em`, autoria) é sempre
  definido pelo banco, sobrescrevendo o que a aplicação enviar.
- O **instante do fato** (`ocorrido_em`, `entrada_em`, `etapa_desde`) é, por padrão, o relógio do
  servidor (`MomentoInformado.agora`). Informar um horário anterior é **ajuste manual**:
  - acima de `unidade.limiar_ajuste_manual` (padrão técnico 5 min) exige justificativa curta
    (≤ 120 caracteres), gravada com `ajuste_manual: true` em **cada** evento do comando;
  - limitado por `unidade.retroatividade_maxima` (padrão técnico 24 h, máx. 7 dias), que pode
    ser ampliada temporariamente em contingência (RNF-018) — a alteração é auditada;
  - o banco recusa evento retroativo sem marcação/justificativa e fatos além da janela.
- Nenhum desses valores é regra institucional (RN-014); são parâmetros da unidade (V-10).
- Pendente: restringir por perfil quem pode registrar ajuste manual (etapa de autorização).

## 2. Concorrência (RF-036, RNF-014)

- Todos os agregados têm `versao`. A aplicação escreve com `WHERE id = :id AND versao = :lida`
  e `versao = :lida + 1`; o trigger `tg_versao` recusa qualquer UPDATE que não incremente a
  versão em exatamente 1. Resultado: duas edições simultâneas nunca se sobrescrevem em
  silêncio — a segunda recebe conflito e o usuário recarrega o caso.

## 3. Duplicidade (RF-003, RF-037)

- A v1.1 proíbe bloquear automaticamente: segundo episódio ativo do mesmo paciente é
  **permitido com justificativa** (`justificativa_duplicidade` + evento `DUPLICIDADE_JUSTIFICADA`).
  O banco trava o cadastro do paciente (`FOR UPDATE`) durante a abertura, então a detecção é
  confiável mesmo com duas aberturas simultâneas.
- **Reconciliação de cadastros:** o duplicado aponta para o principal (`reconciliado_com_id`),
  registra autor/horário/justificativa e torna-se imutável; não aceita novos episódios. Nada é
  apagado. Exige que o duplicado não tenha episódio ativo (encerrar administrativamente antes).

## Consequências

- (+) Atende RNF-017 sem impedir o registro tardio, que é a realidade do MVP com digitação manual.
- (−) Diferença de relógio entre aplicação e banco > limiar faria eventos "agora" parecerem
  ajuste. Mitigação: NTP obrigatório nos servidores; limiar mínimo de 1 min.
