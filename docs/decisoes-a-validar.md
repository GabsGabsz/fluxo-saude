# Decisões a validar com a instituição (ERS v1.1, §19 e Anexo B.1)

Escolhas feitas para não bloquear o desenvolvimento. Todas são **parâmetros ou dados por
unidade**, não regras fixas (RN-014, Anexo B.3). Cada item indica a validação de campo
(V-xx) que o confirma.

| # | Decisão provisória | Onde mudar | Validação |
|---|---|---|---|
| 1 | Etapas e transições padrão, incluindo "Aguardando solicitação de transferência" (§11) e os desfechos Internação, Óbito e Evasão | `fluxo.provisionar_unidade` / cadastro de etapas | V-07 |
| 2 | **Internação encerra o episódio** na UPA (padrão). Em unidade cujo destino também é monitorado: `provisionar_unidade(id, p_internacao_encerra => false)` | parâmetro da unidade | V-07 |
| 3 | Motivo obrigatório em todas as etapas de espera e em "Aguardando transporte"; não obrigatório em "Aceito" | `etapa.exige_motivo_bloqueio` | V-04 |
| 4 | "Transferência solicitada" exige o nº do protocolo oficial | `etapa.exige_protocolo_externo` | V-03 |
| 5 | Dicionário inicial de motivos (ERS §4.3) + "Causa em investigação" (RF-035) | `motivo_bloqueio` | V-04 |
| 6 | Desfecho encerra as pendências abertas na mesma transação, com justificativa automática | caso de uso | V-07 |
| 7 | Retroatividade máxima de **24 h** e limiar de ajuste manual de **5 min** (valores técnicos, não institucionais) | `unidade.retroatividade_maxima`, `unidade.limiar_ajuste_manual` | V-10 |
| 8 | Quem pode registrar horário ajustado manualmente (restrição por perfil) | autorização (próxima etapa) | V-10 |
| 9 | Perfil `COORDENACAO_FLUXO` cobre coordenação, regulação interna ou NIR; os setores reais são cadastrados por unidade | `setor`, `lotacao` | V-01, V-06 |
| 10 | Dados do paciente: nome, nascimento (opcional), CNS (opcional, validado) e identificador institucional (opcional) | `paciente` | V-08 |
| 11 | Pseudônimo de TV = até 3 iniciais + 4 caracteres do episódio | `Pseudonimo` | V-08 |
| 12 | Sessão: inatividade 30 min, absoluta 12 h, até 2 sessões simultâneas | ADR-0002 | V-10 |

**Não decidido, e não deve ser decidido pelo desenvolvedor:** limites de alerta e SLA por etapa
(V-05), níveis e destinatários de escalonamento (V-06), fórmulas oficiais dos indicadores (V-09),
disponibilidade de interface com o Regula Piauí (V-03), retenção, RPO/RTO e MFA em produção (V-10).
