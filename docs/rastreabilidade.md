# Rastreabilidade ERS → implementação

Legenda: ✅ implementado e testado · 🟡 parcial (base pronta) · ⬜ próxima etapa

## Requisitos funcionais (MVP = prioridade M)

| ID | Status | Onde | Teste |
|---|---|---|---|
| RF-001 Autenticação e perfil | 🟡 | `usuario`, `lotacao`, `usuario_acesso`, `SegurancaConfig` (nega tudo), ADR-0002 | `t01` (lotações p/ autenticação) |
| RF-002 Abrir episódio | ✅ | `Episodio.abrir`, `fluxo.episodio` | `EpisodioTest.Abertura`, `t03` |
| RF-003 Sem duplicidade de episódio ativo | ✅ (estrita) | índice `episodio_ativo_unico` | `t03` |
| RF-004 Definir/alterar etapa com autor e hora | ✅ | `Episodio.mudarEtapa`, `transicao_etapa`, trigger `episodio_regras` | `EpisodioTest`, `t03` |
| RF-005 / RF-006 Cronômetros total e da etapa | ✅ | `tempoTotal`, `tempoNaEtapa`, `tempoBloqueado` | `cenarioCompletoDaErs` |
| RF-007 Criar pendência | ✅ | `Pendencia.criar`, `fluxo.pendencia` | `PendenciaTest`, `t03` |
| RF-008 Motivo obrigatório em espera | ✅ | `exige_motivo_bloqueio` (domínio + trigger) | `RN-003` em ambos |
| RF-009 Protocolo externo | ✅ | `ProtocoloExterno`, `exige_protocolo_externo` | ambos |
| RF-010 / 011 / 012 Torre de Controle, destaque, filtros | ⬜ | índices prontos (`episodio_ativos_idx`) | — |
| RF-013 Atualizar/resolver pendência com justificativa | ✅ | `reatribuir`, `alterarPrazo`, `resolver`, `cancelar` | ambos |
| RF-014 Linha do tempo imutável | ✅ | `EventoEpisodio`, `fluxo.evento_episodio` (append-only) | `t03` |
| RF-015 Desfecho | ✅ | etapas `DESFECHO` + `tipo_desfecho` | ambos |
| RF-016 / 017 Passagem de plantão | ⬜ | — | — |
| RF-018 Pacientes travados | ⬜ | depende de regras de alerta (M04) | — |
| RF-019 / 020 Indicadores | ⬜ | dados de base prontos (eventos com timestamps) | — |
| RF-027 Parametrizar etapas/motivos (S) | 🟡 | tabelas + `provisionar_unidade()`; falta tela | `t03` |
| RF-028 Observação operacional (S) | 🟡 | tipo `OBSERVACAO_REGISTRADA` | — |
| RF-029 Busca (S) | 🟡 | índice trigram em nome normalizado | — |

## Regras de negócio

| Regra | Garantia |
|---|---|
| RN-001 Não altera prioridade clínica | Nenhum atributo clínico no modelo; criticidade é operacional (`Criticidade`) |
| RN-002 Regulação oficial é a fonte | Só o número do protocolo é guardado |
| RN-003 Espera exige motivo | Domínio + trigger |
| RN-004 Toda alteração audita | Trigger `auditoria.tg_capturar` em todas as tabelas de negócio |
| RN-005 Encerrar pendência exige resolução | Domínio + `CHECK pendencia_encerramento_coerente` |
| RN-006 Limites parametrizáveis | ⬜ (módulo de alertas) |
| RN-007 Alerta ≠ risco clínico | Por construção |
| RN-008 Encerramento para relógios e preserva histórico | Domínio + episódio encerrado imutável + pendências encerradas pelo desfecho |
| RN-009 Minimização | Paciente só com nome, nascimento, CNS, identificador; auditoria redigida |
| RN-010 Auditoria não excluível | Privilégios + triggers + cadeia de hash |
| RN-011 Fonte da verdade externa | `identificador_institucional`, `protocolo_*` |
| RN-012 Independente do sistema de filas | Nenhuma dependência |

## Critérios de aceite (ERS §18)

| CA | Status |
|---|---|
| CA-01 abrir episódio e ver relógios | ✅ domínio/banco · ⬜ tela |
| CA-02 mover entre etapas preservando histórico | ✅ |
| CA-03 espera exige motivo | ✅ |
| CA-04 pendência com responsável e prazo | ✅ |
| CA-08 encerrar para cronômetros e preserva histórico | ✅ |
| CA-10 auditoria identifica autor | ✅ |
| CA-11 perfis sem autorização não veem dados restritos | 🟡 RLS por unidade ✅; perfis ⬜ |
| CA-12 funciona sem integração | ✅ |
| CA-05, 06, 07, 09 | ⬜ |
