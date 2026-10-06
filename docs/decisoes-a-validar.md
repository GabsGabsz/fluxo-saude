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
| 8 | Quem pode registrar horário ajustado manualmente: permissão `HORARIO_AJUSTAR` (coordenação e enfermagem) | `MatrizPermissoes` | V-10 |
| 9 | Perfil `COORDENACAO_FLUXO` cobre coordenação, regulação interna ou NIR; os setores reais são cadastrados por unidade | `setor`, `lotacao` | V-01, V-06 |
| 10 | Dados do paciente: nome, nascimento (opcional), CNS (opcional, validado) e identificador institucional (opcional) | `paciente` | V-08 |
| 11 | Pseudônimo de TV = até 3 iniciais + 4 caracteres do episódio | `Pseudonimo` | V-08 |
| 12 | Sessão: inatividade 30 min, absoluta 12 h, até 2 sessões simultâneas | `application.yml` | V-10 |
| 13 | **Matriz perfil → permissões** (ERS §3): administração sem acesso nominal a casos; direção só agregado/pseudonimizado; transporte só a fila; ajuste manual de horário só coordenação e enfermagem; reconciliação só coordenação | `MatrizPermissoes` | V-06, V-08 |
| 14 | Limites de login: 30 falhas/5 min por IP (NAT), 5 por IP+login, bloqueio de conta após 5 falhas (5 min, progressivo) | `application.yml` | V-10 |
| 15 | **Justificativa de ajuste de horário fica no evento imutável** (até 120 caracteres), pois o RNF-017 exige que ela acompanhe o fato. É o único texto livre em `evento_episodio`; a tela deve orientar a não escrever dado clínico/pessoal. Alternativa (se o encarregado de dados exigir): tabela própria, referenciada pelo evento | `Episodio`, V5/V10 | V-08 |
| 16 | Prazo de pendência: no máximo **30 dias** à frente | `Pendencia.PRAZO_MAXIMO` | V-05 |
| 17 | Leitura do caso limitada a 1000 eventos, 200 pendências e 200 observações mais recentes (`historicoTruncado` sinaliza corte) | `Consultas` | V-10 |
| 18 | Banco: espera por lock até **5 s** e consulta até **30 s** (acima disso: 409 / erro) | `application.yml` | V-10 |
| 19 | **Alcance administrativo:** papéis por unidade = administrador da unidade; dados da conta, desativação e senha provisória = administrador da **unidade gestora** (a que criou a conta) que também administre **todas** as unidades do usuário (ADR-0006) | V11, `ServicoGestaoUsuarios` | V-06 |
| 20 | **Sem autoalteração:** administrador não altera os próprios papéis, situação ou conta (só outro administrador) | V11 | V-06 |
| 21 | **Último administrador:** toda unidade mantém ≥ 1 administrador ativo; a remoção que zeraria é recusada | V11 | V-06 |
| 22 | Administrador pode conceder qualquer papel da unidade, inclusive Administrador e papéis com acesso nominal (ex.: Coordenação). Segregação de funções (ex.: dupla aprovação) não implementada — depende de processo institucional | V11 | V-06, V-08 |
| 23 | Remover a última lotação **desativa** a conta; conta órfã só é reativada pelo DBA; vínculo entre unidades só de conta ativa, localizada pelo login exato; revogar o acesso na unidade gestora encerra a gestão da conta pela aplicação (DBA a partir daí) | V11 | V-06 |
| 24 | Senha provisória gerada pelo sistema e exibida uma vez ao administrador, que a entrega ao profissional por canal seguro (procedimento institucional a definir) | `GeradorSenhaProvisoria` | V-10 |
| 26 | Encerram todas as sessões do usuário: troca da própria senha (exceto a sessão que trocou), senha provisória, desativação/reativação e alteração de papéis em qualquer unidade; a implantação da V12 encerra todas as sessões existentes uma vez | V12 | V-10 |
| 27 | Tipos de regra de "travado": tempo na etapa, tempo total, tempo bloqueado (por categoria), sem atualização, pendência vencida (ADR-0007). Nenhuma regra vem cadastrada; cada unidade configura as suas | V13 | V-05 |
| 28 | Fronteira: o alerta vale quando o limite é **atingido** (`≥`); pendência vencida quando `agora > prazo` (igual à Torre) | `MotorDeAlertas` | V-05 |
| 29 | "Sem atualização" = nenhum registro na linha do tempo (qualquer evento, inclusive observação) há mais que o limite | `MotorDeAlertas` | V-05 |
| 30 | Quem configura regras: Administrador; quem vê travados: quem tem acesso nominal (`EPISODIO_VER`); quem registra ciência: `EPISODIO_ALTERAR` (coordenação, enfermagem, médico); painel coletivo só sinaliza "em alerta" | V13, `ServicoAlertas` | V-06 |
| 31 | Ordem do painel de travados: limite atingido há mais tempo primeiro (critério operacional, não prioridade clínica) | `ServicoAlertas` | V-05 |
| 32 | Escalonamento e notificações direcionadas **não implementados**: aguardam níveis, destinatários e tempos | — | V-05, V-06 |
| 25 | **Limitação conhecida:** o contexto da transação (GUCs) pode ser definido pelo próprio papel da aplicação; as regras do banco não resistem a SQL arbitrário como `fluxo_app`. Proposta: contexto não forjável (etapa própria, afeta todas as políticas) | V1/V8 | V-10 |

**Não decidido, e não deve ser decidido pelo desenvolvedor:** limites de alerta e SLA por etapa
(V-05), níveis e destinatários de escalonamento (V-06), fórmulas oficiais dos indicadores (V-09),
disponibilidade de interface com o Regula Piauí (V-03), retenção, RPO/RTO e MFA em produção (V-10).
