# Rastreabilidade ERS v1.1 → implementação

Baseline: **ERS v1.1 (revisão técnica)**. Legenda: ✅ implementado e testado · 🟡 parcial (base pronta) · ⬜ próxima etapa.
"Ambos" = domínio Java + banco (dupla garantia, ADR-0001).

## Requisitos funcionais

| ID | Pri. | Status | Onde | Teste |
|---|---|---|---|---|
| RF-001 Autenticação e perfil | M | ✅ | módulo `identidade` (login, sessão, troca de senha, unidade ativa), `MatrizPermissoes`, V6–V8, ADR-0002 | `ServicoAutenticacaoTest`, `MatrizPermissoesTest`, `t05`, `SessaoIT` |
| RF-002 Abrir episódio | M | ✅ | `Episodio.abrir`, `fluxo.episodio` | `EpisodioTest`, `t03` |
| RF-003 Detectar duplicidade **sem bloquear** (v1.1) | M | ✅ | justificativa obrigatória + evento `DUPLICIDADE_JUSTIFICADA`; banco serializa aberturas do mesmo paciente | `RegrasV11Test`, `t03` |
| RF-004 Alterar etapa com autor e hora | M | ✅ | `mudarEtapa`, `transicao_etapa`, trigger `episodio_regras` | ambos |
| RF-005 / 006 Cronômetros | M | ✅ | `tempoTotal`, `tempoNaEtapa`, `tempoBloqueado` | `cenarioCompletoDaErs` |
| RF-007 Criar pendência | M | ✅ | `Pendencia.criar` | ambos |
| RF-008 Motivo obrigatório em espera | M | ✅ | `exige_motivo_bloqueio` | ambos |
| RF-009 Protocolo externo | M | ✅ | `ProtocoloExterno`, `exige_protocolo_externo` | ambos |
| RF-010 / 012 Torre de Controle e filtros | M | ⬜ | índices prontos | — |
| RF-011 Destaque por limite, separado de risco clínico | M | ⬜ | depende de M04 (limites parametrizados) | — |
| RF-013 Atualizar/resolver pendência | M | ✅ | `reatribuir`, `alterarPrazo`, `resolver`, `cancelar` | ambos |
| RF-014 Linha do tempo append-only, correção = novo evento | M | ✅ | `evento_episodio` imutável; `corrige_evento_id` só no mesmo episódio | `t03` |
| RF-015 Desfecho configurável por unidade (v1.1) | M | ✅ | etapas de desfecho por unidade; `provisionar_unidade(…, p_internacao_encerra)` | `o02`, ambos |
| RF-016 / 017 Passagem de plantão | M | ⬜ | — | — |
| RF-018 Pacientes travados (regras configuráveis) | M | ⬜ | — | — |
| RF-019 / 020 Indicadores | M | ⬜ | eventos com horário do servidor como base | — |
| RF-027 Parametrizar etapas, motivos, encerramento | S | 🟡 | tabelas + provisionamento; faltam telas, limites e escalonamento | `t03`, `o02` |
| RF-028 Observação operacional | S | 🟡 | evento `OBSERVACAO_REGISTRADA` | — |
| RF-029 Busca | S | 🟡 | índice trigram do nome normalizado | — |
| **RF-035** Motivo "causa em investigação" (v1.1) | M | ✅ | categoria `NAO_DEFINIDA` + motivo `CAUSA_EM_INVESTIGACAO` com justificativa obrigatória | `RegrasV11Test`, `t04` |
| **RF-036** Concorrência sem sobrescrita silenciosa (v1.1) | M | ✅ | `versao` em todos os agregados; trigger `tg_versao` exige versão+1 | `t04` |
| **RF-037** Reconciliação de cadastros duplicados (v1.1) | M | ✅ (banco) | `paciente.reconciliado_com_id`; duplicado preservado e imutável, não aceita novos episódios | `t04` |
| **RF-038** Modo privacidade para TV (v1.1) | M | 🟡 | `Pseudonimo` (iniciais + sufixo); falta a tela | `RegrasV11Test` |
| **RF-039** Dicionário de indicadores | S | ⬜ | — | — |
| **RF-040** Horário do servidor e fuso institucional | S | 🟡 | `registrado_em`/autoria pelo banco, `unidade.fuso_horario`; turnos ⬜ | `t03`, `t04` |

## Regras de negócio

| Regra | Garantia |
|---|---|
| RN-001 / RN-007 / **RN-013** criticidade ≠ risco clínico | Sem atributo clínico; tipo `criticidade_operacional` / `CriticidadeOperacional` nomeado explicitamente |
| RN-002 / **RN-016** fonte oficial prevalece | Só o protocolo é guardado; eventos `DIVERGENCIA_REGISTRADA/RESOLVIDA` previstos (tela ⬜) |
| RN-003 | Ambos |
| RN-004 | Trigger de auditoria em todas as tabelas de negócio |
| RN-005 | Ambos |
| RN-006 / **RN-014** nenhum tempo de alerta hardcoded | Nenhum limite de alerta no código. Retroatividade e limiar de ajuste são parâmetros da unidade |
| RN-008 | Episódio encerrado imutável; pendências encerradas no mesmo COMMIT (constraint adiável) |
| RN-009 | Paciente mínimo; auditoria e eventos sem texto clínico |
| RN-010 | Privilégios + triggers + cadeia de hash |
| RN-011 | `identificador_institucional`, `protocolo_*` |
| RN-012 | Nenhuma dependência do sistema de filas |
| **RN-015** NIR não presumido | Perfil `COORDENACAO_FLUXO`; setores (NIR, regulação interna…) são cadastro da unidade |
| **RN-017** encerramento configurável | Etapas por unidade; internação pode ser transição; `episodio_origem_id` liga episódios entre unidades |

## Requisitos não funcionais novos (v1.1)

| RNF | Status |
|---|---|
| RNF-013 Credenciais (hash, MFA, sem compartilhamento) | 🟡 Argon2id + política NIST + bloqueio progressivo + limite de sessões ✅; MFA ⬜ |
| RNF-014 Concorrência | ✅ `tg_versao` |
| RNF-015 Privacidade visual | 🟡 `Pseudonimo`; tela ⬜ |
| RNF-016 Acessibilidade (eMAG/WCAG) | ⬜ front-end |
| RNF-017 Horário do servidor; ajuste manual restrito e auditado | ✅ banco define `registrado_em`; ajuste exige marcação + justificativa no evento, limitado pela unidade; permissão `HORARIO_AJUSTAR` (aplicação nos casos de uso ⬜) |
| RNF-018 Contingência | 🟡 ajuste manual justificado + janela ampliável por unidade; procedimento documentado ⬜ |

## Critérios de aceite (ERS §18)

| CA | Status |
|---|---|
| CA-01, 02, 03, 04, 08, 10, 12 | ✅ no domínio/banco (telas ⬜) |
| CA-11 | ✅ RLS por unidade + permissões por perfil na unidade ativa + revalidação no banco a cada transação (aplicação nas telas ⬜) |
| CA-05, 06, 07, 09 | ⬜ |

## Anexo B.3 — decisões que o desenvolvedor não deve tomar sozinho

| Diretriz | Situação |
|---|---|
| Não fixar SLA/alerta | ✅ nenhum valor fixo |
| Não fixar organograma (NIR) | ✅ `COORDENACAO_FLUXO` + setores configuráveis |
| Sem scraping/credencial compartilhada | ✅ nenhuma integração; ADR-0001 |
| Não armazenar diagnóstico/prescrição/anexos | ✅ não há campos para isso |
| Sem exclusão destrutiva de histórico | ✅ sem DELETE; triggers |
| Não exibir nome/CNS em dashboard coletivo por padrão | 🟡 `Pseudonimo`; regra da tela ⬜ |
| Alerta ≠ prioridade médica | ✅ por construção |
