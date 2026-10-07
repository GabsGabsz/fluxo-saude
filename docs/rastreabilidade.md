# Rastreabilidade ERS v1.1 → implementação

Baseline: **ERS v1.1 (revisão técnica)**. Legenda: ✅ implementado e testado · 🟡 parcial (base pronta) · ⬜ próxima etapa.
"Ambos" = domínio Java + banco (dupla garantia, ADR-0001).

## Requisitos funcionais

| ID | Pri. | Status | Onde | Teste |
|---|---|---|---|---|
| RF-001 Autenticação e perfil | M | ✅ | módulo `identidade` (login, sessão, troca de senha, unidade ativa), `MatrizPermissoes`, V6–V8, ADR-0002 | `ServicoAutenticacaoTest`, `MatrizPermissoesTest`, `t05`, `SessaoIT` |
| M08 Gestão de usuários e perfis (ERS §3, §5) | M | ✅ | `ServicoGestaoUsuarios`, `/api/admin/usuarios`, funções `admin_*` (V11), ADR-0006: criação com senha provisória e troca obrigatória, papéis por unidade, revogação, vínculo de conta existente, desativação/senha só com alcance sobre a conta, sem autoalteração, último administrador garantido, sessões encerradas; tela `telas/usuarios.js` (senha provisória exibida uma vez, sem autoalteração) | `ServicoGestaoUsuariosTest`, `t08`, `concorrencia-administradores`, `GestaoUsuariosIT` |
| RF-002 Abrir episódio | M | ✅ | `Episodio.abrir`, `ServicoEpisodios.abrir`, `POST /api/episodios` | `EpisodioTest`, `ServicosDeAplicacaoTest`, `t03`, `EpisodiosIT` |
| RF-003 Detectar duplicidade **sem bloquear** (v1.1) | M | ✅ | justificativa obrigatória + evento `DUPLICIDADE_JUSTIFICADA`; banco serializa aberturas do mesmo paciente | `RegrasV11Test`, `t03` |
| RF-004 Alterar etapa com autor e hora | M | ✅ | `mudarEtapa`, `PUT /api/episodios/{id}/etapa`, `transicao_etapa`, trigger `episodio_regras` | ambos, `EpisodiosIT` |
| RF-005 / 006 Cronômetros | M | ✅ | `tempoTotal`, `tempoNaEtapa`, `tempoBloqueado` | `cenarioCompletoDaErs` |
| RF-007 Criar pendência | M | ✅ | `Pendencia.criar` | ambos |
| RF-008 Motivo obrigatório em espera | M | ✅ | `exige_motivo_bloqueio` | ambos |
| RF-009 Protocolo externo | M | ✅ | `ProtocoloExterno`, `exige_protocolo_externo` | ambos |
| RF-010 / 012 Torre de Controle e filtros | M | ✅ | `GET /api/episodios` (setor, etapa, motivo, categoria, especialidade, responsável, tempo mínimo na etapa, pendência vencida; ordenação por lista fechada); `ConsultasJdbc`, índices V9; tela `telas/torre.js` (filtros, ordenação, tempos pelo relógio do servidor, aviso de lista truncada) | `ServicosDeAplicacaoTest`, `EpisodiosIT`, E2E `02-unidades` |
| RF-011 Destaque por limite, separado de risco clínico | M | ✅ | `MotorDeAlertas`; `GET /api/episodios` traz `alertas` por episódio; painel coletivo só `emAlerta` (V13, ADR-0007); tela `torre.js` (etiqueta com ícone e texto, aviso "operacional ≠ risco clínico") | `MotorDeAlertasTest`, `ServicoAlertasTest`, `AlertasIT` |
| RF-013 Atualizar/resolver pendência | M | ✅ | `reatribuir`, `alterarPrazo` (máx. 30 dias), `resolver`, `cancelar`; `PATCH /api/pendencias/{id}`, `POST …/resolucao`, `…/cancelamento` | ambos, `EpisodiosIT` |
| RF-014 Linha do tempo append-only, correção = novo evento | M | ✅ | `evento_episodio` imutável; `corrige_evento_id` só no mesmo episódio; `GET /api/episodios/{id}` (caso + linha do tempo, consulta auditada, resposta limitada com `historicoTruncado`) | `t03`, `EpisodiosIT` |
| RF-015 Desfecho configurável por unidade (v1.1) | M | ✅ | etapas de desfecho por unidade; `provisionar_unidade(…, p_internacao_encerra)` | `o02`, ambos |
| RF-016 / 017 Passagem de plantão | M | ✅ | `ServicoPlantao` (prévia, entrega por assinatura do conteúdo visto, recebimento por outro profissional vendo a situação atual entregue × agora, `Comparacao`), V16 (`passagem_plantao`, `passagem_conteudo`, papel restritivo), V17 (leituras nominais registradas), V18 (regra de cada alerta na versão gravada), tela `telas/plantao.js`, ADR-0009 | `ComposicaoPassagemTest`, `ComparacaoTest`, `ServicoPlantaoTest`, `t12`, `t14`, `t15`, `o04`, `o05`, `PlantaoIT`, `plantao.test.mjs`, E2E `08-plantao` |
| RF-018 Pacientes travados (regras configuráveis) | M | ✅ | `GET /api/travados`: só episódios abertos que violam regra ativa (tempo na etapa, tempo total, tempo bloqueado, sem atualização, pendência vencida), com tempos, motivo, pendências (próxima ação, responsável, prazo) e ação esperada; tela `telas/travados.js` (distingue "nenhuma regra" de "nenhum alerta") | `MotorDeAlertasTest`, `ServicoAlertasTest`, `t10`, `AlertasIT` |
| RF-021 Alertas direcionados a usuário/setor/perfil | S | ⬜ | depende de V-06 (destinatários) | — |
| RF-022 Ciência do alerta sem encerrar pendência | S | ✅ | `POST /api/episodios/{id}/alertas/ciencia`; `ciencia_alerta` imutável, auditada; por ocorrência e **versão da regra vista** (409 se mudou, V14) | `ServicoAlertasTest`, `t10`, `concorrencia-ciencia`, `AlertasIT` |
| RF-023 Escalonamento após tempo configurado | S | ⬜ | depende de V-05/V-06 (níveis, destinatários, tempos) | — |
| RF-019 / 020 Indicadores | M | ✅ (fórmulas propostas, V-09) | funções `fluxo.ind_*` (V16): permanência média/mediana, acima dos limites configurados, motivos por intervalos de bloqueio; retrato atual × histórico num único instantâneo (REPEATABLE READ somente leitura); limitações exibidas junto dos resultados; tela `telas/indicadores.js` | `t13` (valores à mão, fronteiras, fuso, vazio, base zero), `concorrencia-indicadores`, `ServicoIndicadoresTest`, `IndicadoresIT`, `IndicadoresConsistenciaIT`, `indicadores.test.mjs`, E2E `09-indicadores` |
| RF-027 Parametrizar etapas, motivos, encerramento | S | 🟡 | tabelas + provisionamento; **limites de alerta por unidade/etapa** (`/api/config/regras-alerta`, só Administrador, V13) ✅; tela de regras (`regras.js`) ✅; faltam telas de etapas/motivos e escalonamento | `t03`, `o02`, `t10`, `AlertasIT` |
| RF-028 Observação operacional | S | ✅ | tabela `observacao` (V9, imutável, RLS, auditada com texto redigido) + evento só com a referência; `POST /api/episodios/{id}/observacoes` | `ServicosDeAplicacaoTest`, `t06`, `EpisodiosIT` |
| RF-029 Busca | S | 🟡 | índice trigram do nome normalizado; busca **exata** por CNS/identificador para abrir episódio (`GET /api/pacientes`, auditada); busca por nome ⬜ | — |
| **RF-035** Motivo "causa em investigação" (v1.1) | M | ✅ | categoria `NAO_DEFINIDA` + motivo `CAUSA_EM_INVESTIGACAO` com justificativa obrigatória | `RegrasV11Test`, `t04` |
| **RF-036** Concorrência sem sobrescrita silenciosa (v1.1) | M | ✅ | `versao` em todos os agregados; trigger `tg_versao` exige versão+1; API exige a `versao` lida → 409; `lock_timeout` 5 s → 409 | `t04`, `ServicosDeAplicacaoTest`, `EpisodiosIT` |
| **RF-037** Reconciliação de cadastros duplicados (v1.1) | M | ✅ (banco) | `paciente.reconciliado_com_id`; duplicado preservado e imutável, não aceita novos episódios | `t04` |
| **RF-038** Modo privacidade para TV (v1.1) | M | ✅ | `Pseudonimo`; `GET /api/painel` só devolve pseudônimo (sem nome, CNS ou ID do episódio); tela `painel.js` (E2E `06-perfis`: nenhum nome exibido) | `RegrasV11Test`, `EpisodiosIT` |
| **RF-039** Dicionário de indicadores | S | 🟡 | dicionário explícito e versionado no código (`DicionarioIndicadores`, `GET /api/indicadores/dicionario`, [`docs/indicadores.md`](indicadores.md)); **configuração** pelo usuário ⬜ | `ServicoIndicadoresTest` |
| **RF-040** Horário do servidor e fuso institucional | S | 🟡 | `registrado_em`/autoria pelo banco, `unidade.fuso_horario`; interface exibe no fuso da unidade e conta tempos pelo relógio do servidor (ADR-0008); turnos ⬜ | `t03`, `t04` |

## Interface web (etapa 6, ADR-0008)

| Requisito da etapa | Onde | Teste |
|---|---|---|
| Sessão no servidor, CSRF, nada sensível no navegador | `nucleo/api.js`, `nucleo/estado.js` | `nucleo.test.mjs`, E2E `01-sessao` (armazenamento vazio) |
| Troca de unidade sem mistura; operação de contexto antigo não enviada; resposta (cabeçalhos ou corpo) tardia descartada; outra aba | contexto capturado e reconferido em `api.js`, `X-Fluxo-Unidade` (`InterceptadorSessao`) | `contexto.test.mjs`, `nucleo.test.mjs`, `CatalogoIT`, E2E `02-unidades`, `07-resiliencia` |
| Versão lida em toda escrita; 409 sem sobrescrita nem reenvio | `nucleo/formulario.js`, `telas/episodio.js` | E2E `04-conflito` |
| Ciência na `regraVersao` exibida; regra alterada exige nova ação | `telas/travados.js`, `telas/episodio.js` | E2E `05-alertas` |
| Atualização periódica sem sobreposição, sem escrita, pausada na edição; dados desatualizados sinalizados | `nucleo/atualizador.js` | `nucleo.test.mjs`, E2E `07-resiliencia` |
| Menu por permissão; recusa no servidor; painel sem nomes | `main.js`, `estado.js` | E2E `06-perfis` |
| Estático público só para GET; `/api/**` protegida | `SegurancaConfig` | `InterfaceEstaticaIT` |

## Extensão aprovada — relatórios gerenciais (issue #9, etapa 8, ADR-0010)

**Não são requisitos da ERS v1.1 original.** Os itens abaixo vêm da issue #9 (extensão aprovada do
projeto); reaproveitam registros e definições de RF-019, RF-020 e RF-039, sem alterar seu status.
A exportação CSV desta extensão **não** implementa o RF-025 (exportação), que continua a definir.

| Item da issue #9 | Onde | Teste |
|---|---|---|
| Resumo gerencial (entradas, encerramentos por desfecho, estoque por etapa/setor, bloqueados, pendências abertas/vencidas, casos em alerta agora) | `fluxo.rel_resumo` (V19), `ServicoRelatorios` (alertas pelo `MotorDeAlertas`) | `t16`, `ServicoRelatoriosTest`, `RelatoriosIT` |
| Gargalos por etapa, setor e categoria — tempos da linha do tempo (sem refazer do estado atual; espera no setor em que ocorreu; concluído × em curso; intervalos repetidos) | `fluxo.rel_intervalos`, `fluxo.rel_gargalos` | `t16` (valores à mão), `RelatoriosIT` (filtros de setor/etapa), E2E `10-relatorios` |
| Acompanhamento de pendências (criadas, encerradas no prazo, abertas por categoria/criticidade/tipo de responsável, atraso) + lista operacional nominal só com acesso nominal, sem lista parcial (limite 2000), leitura auditada | `fluxo.rel_pendencias`, `fluxo.rel_pendencias_lista`, `registrar_consulta` | `t16`, `RelatoriosIT`, E2E `10-relatorios` |
| Evolução entre períodos comparáveis (só períodos encerrados — fim até ontem no fuso; mesmo nº de dias locais com durações informadas; base zero sem %; unidades das variações; alterações de regras informadas) | `fluxo.rel_metricas_periodo`, `fluxo.rel_mudancas_regras`, `ServicoRelatorios`, `nucleo/relatorios.js` | `t16`, `ServicoRelatoriosTest` (relógio controlado, meia-noite local, horário de verão), `RelatoriosIT`, `relatorios.test.mjs`, E2E `10-relatorios` |
| Qualidade/atualidade (sem prazo oficial inventado; opcionais como cobertura, não falha; retroativos pelo registro com setor do fato; bloqueios iniciados pela definição normalizada; fatos sem setor da época sinalizados; causa não definida; linha do tempo com escopo próprio) | `fluxo.rel_qualidade` (V20, desempate temporal V21) | `t16`, `t17` (regressão: falha na V19), `o06` (transferências no mesmo instante; falha na V20) |
| Fórmulas, população, denominador, marcos, exclusões, abertos, repetições, ausentes — propostas (V-09), levadas DENTRO de cada resultado (assinadas) para tela, impressão e CSV | `DicionarioRelatorios`, `Resultado.definicoes`, [`docs/relatorios.md`](relatorios.md) | `ServicoRelatoriosTest`, `relatorios.test.mjs` (impressão e CSV com definições mesmo com o dicionário fora do ar), E2E `10-relatorios` |
| Tela integrada com filtros; cabeçalho com unidade, período, fuso, filtros, referência, geração, versão, nº de linhas, assinatura; limitações junto das seções | `telas/relatorios.js`, `nucleo/relatorios.js` | `relatorios.test.mjs`, E2E `10-relatorios` |
| Tela, impressão e CSV = mesmo conjunto (um instantâneo; assinatura + comprovante HMAC; sem recálculo) | `ServicoRelatorios`, `TokenRelatorio`, `TransacaoRelatoriosJdbc` | `RelatoriosConsistenciaIT` (gravação concorrente durante a geração e a exportação), E2E `10-relatorios` |
| Exportação auditada (ator, unidade, filtros, referências, sem conteúdo nominal); impressão "solicitada", não comprovada | `POST /api/relatorios/exportacoes`, `RegistroRelatoriosJdbc` | `RelatoriosIT`, `relatorios.test.mjs` |
| CSV protegido contra injeção de fórmulas; PDF só pela impressão do navegador (A4 paisagem, cabeçalho repetido) | `nucleo/relatorios.js`, `app.css` (`@media print`) | `relatorios.test.mjs`, E2E `10-relatorios` (CSV e PDF de várias páginas) |
| Direção só agregados; RLS; troca de unidade; sessão revogada | `ServicoRelatorios`, RLS (ADR-0004) | `t16` (RLS), `RelatoriosIT`, E2E `10-relatorios` |
| Risco de reidentificação em grupos pequenos | limitação `GRUPOS_PEQUENOS` em todo relatório; política pendente | `decisoes-a-validar.md` (V-08) |

## Regras de negócio

| Regra | Garantia |
|---|---|
| RN-001 / RN-007 / **RN-013** criticidade ≠ risco clínico | Sem atributo clínico; tipo `criticidade_operacional` / `CriticidadeOperacional` nomeado explicitamente |
| RN-002 / **RN-016** fonte oficial prevalece | Só o protocolo é guardado; eventos `DIVERGENCIA_REGISTRADA/RESOLVIDA` previstos (tela ⬜) |
| RN-003 | Ambos |
| RN-004 | Trigger de auditoria em todas as tabelas de negócio |
| RN-005 | Ambos |
| RN-006 / **RN-014** nenhum tempo de alerta hardcoded | Nenhum limite de alerta no código e **nenhuma regra pré-cadastrada**: regras de alerta são parâmetros por unidade e etapa (V13); sem regra, nada alerta. Retroatividade e limiar de ajuste são parâmetros da unidade |
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
| RNF-013 Credenciais (hash, MFA, sem compartilhamento) | 🟡 Argon2id + política NIST + bloqueio progressivo + limite de sessões ✅; credencial individual provisionada pelo administrador com senha provisória aleatória e troca obrigatória ✅; versão de credencial conferida em toda transação — sessão antiga recusada após troca/redefinição de senha mesmo sem remoção física, login concorrente recusado (V12; `t09`, `o03`, `concorrencia-credencial`, `ServicoAutenticacaoTest`, `SessaoSobreviventeIT`) ✅; MFA ⬜ |
| RNF-014 Concorrência | ✅ `tg_versao` |
| RNF-015 Privacidade visual | ✅ `Pseudonimo` + painel coletivo só pseudonimizado; nada nominal no armazenamento do navegador (ADR-0008) |
| RNF-016 Acessibilidade (eMAG/WCAG) | 🟡 rótulos, foco visível e gerenciado, `role=alert/status`, estados com ícone + texto, tabelas responsivas; verificação automática axe-core (WCAG 2 A/AA) sem violações nas telas; auditoria eMAG completa e teste com leitor de tela ⬜ |
| RNF-017 Horário do servidor; ajuste manual restrito e auditado | ✅ banco define `registrado_em`; ajuste exige marcação + justificativa no evento, limitado pela unidade; permissão `HORARIO_AJUSTAR` exigida nos casos de uso, com um único "agora" por operação; banco com margem de 1 min sobre a aplicação (V10) |
| RNF-018 Contingência | 🟡 ajuste manual justificado + janela ampliável por unidade; procedimento documentado ⬜ |

## Critérios de aceite (ERS §18)

| CA | Status |
|---|---|
| CA-01, 02, 03, 04, 08, 10, 12 | ✅ no domínio/banco e nas telas (E2E `03-episodio`, `04-conflito`) |
| CA-11 | ✅ RLS por unidade + permissões por perfil na unidade ativa + revalidação no banco a cada transação; registro de outra unidade responde 404 (`EpisodiosIT`); administração restrita ao alcance da unidade/conta, revogação com sessão encerrada (`GestaoUsuariosIT`); telas: menu por permissão, troca de unidade sem mistura, unidade esperada conferida no servidor (E2E `02-unidades`, `06-perfis`, `07-resiliencia`) |
| CA-05, CA-06 | ✅ na API e nas telas (destaque na Torre; painel de travados com tempo, motivo, responsável e ação esperada) — telas `torre.js`/`travados.js` (E2E `05-alertas`) (`AlertasIT`) |
| CA-07 | ✅ composição automática de todos os episódios abertos com pendências e ações esperadas (`ComposicaoPassagem`, E2E `08-plantao`) |
| CA-09 | ✅ tempos (permanência, solicitação→aceite, aceite→saída) e distribuição dos motivos (`t13`, `IndicadoresIT`, E2E `09-indicadores`) — fórmulas propostas (V-09) |

## Anexo B.3 — decisões que o desenvolvedor não deve tomar sozinho

| Diretriz | Situação |
|---|---|
| Não fixar SLA/alerta | ✅ nenhum valor fixo |
| Não fixar organograma (NIR) | ✅ `COORDENACAO_FLUXO` + setores configuráveis |
| Sem scraping/credencial compartilhada | ✅ nenhuma integração; ADR-0001 |
| Não armazenar diagnóstico/prescrição/anexos | ✅ não há campos para isso |
| Sem exclusão destrutiva de histórico | ✅ sem DELETE; triggers |
| Não exibir nome/CNS em dashboard coletivo por padrão | ✅ na API (`/api/painel` pseudonimizado); tela ✅ |
| Alerta ≠ prioridade médica | ✅ por construção |
