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
| 33 | A ciência vale para a versão da regra que o profissional viu: se o Administrador alterar limite ou ação esperada, a ciência anterior não vale para a nova versão e a tela precisa ser relida (409) | V14 | V-06 |
| 34 | Interface: atualização automática a cada **30 s**, pausada enquanto há edição; falha de conexão mantém a lista e mostra "dados podem estar desatualizados" | `nucleo/atualizador.js`, telas | V-10 |
| 35 | A Torre exibe até **300** casos pela ordenação escolhida (aviso de lista truncada; refinar filtros) | `telas/torre.js` | V-10 |
| 36 | A unidade ativa é da **sessão** (compartilhada entre abas do mesmo navegador): trocar numa aba recarrega as outras; requisição de aba desatualizada é recusada (409) | `InterceptadorSessao`, `main.js` | V-10 |
| 37 | "Prazo expirado" no detalhe da pendência é só informação (relógio do servidor); alerta formal depende de regra `PENDENCIA_VENCIDA` configurada | `telas/episodio.js` | V-05 |
| 38 | Navegadores suportados: versões atuais de Chrome, Edge, Firefox e Safari (ES modules); sem modo offline por segurança | ADR-0008 | V-10 |
| 39 | Passagem de plantão: entrega por quem gerencia plantão (coordenação, enfermagem, médico); recebimento por **outro** profissional com a mesma permissão; uma pendente por unidade; **só o autor cancela**, com justificativa | `ServicoPlantao`, V16 | V-01, V-06 |
| 40 | Composição: **todos** os episódios abertos; "crítico" = alerta operacional ativo ou pendência de criticidade operacional CRÍTICA; "transferência" = protocolo/destino registrados ou etapa Aceito/Transporte (não é risco clínico) | `ComposicaoPassagem` | V-01, V-07 |
| 41 | Período da passagem = desde a entrega da última passagem RECEBIDA até esta entrega (sem horários de turno; RF-040 não implementado) | V16 (gatilho) | V-01 |
| 42 | Limite técnico de 2000 casos na passagem: acima disso ela não é gerada (nunca parcial) | `ServicoPlantao.LIMITE_CASOS` | V-10 |
| 43 | Indicadores: **todas as fórmulas são propostas** (dicionário em `docs/indicadores.md`); encerramento administrativo excluído da permanência; solicitação = etapa que exige protocolo externo; aceite = natureza ACEITO; 1ª solicitação → 1º aceite; último aceite → saída | `fluxo.ind_*` (V16) | V-09 |
| 44 | "Acima do limite" histórico usa o limite VIGENTE das regras "tempo total" sem etapa (não o vigente na época) e o setor atual/final do episódio; as duas limitações aparecem na tela junto dos resultados correspondentes | `fluxo.ind_acima_dos_limites`, `fluxo.ind_motivos`, `telas/indicadores.js` | V-05, V-09 |
| 45 | Período dos indicadores: datas locais da unidade, até 366 dias, sem datas futuras; sem supressão de contagens pequenas para a Direção (risco de reidentificação em unidades pequenas a avaliar) | `ServicoIndicadores` | V-08, V-09 |
| 46 | Dicionário de indicadores **não configurável** pelo usuário nesta versão (RF-039 parcial): mudanças de fórmula exigem nova versão do sistema | `DicionarioIndicadores` | V-09 |
| 47 | Leituras nominais da passagem (prévia e detalhe) registradas na auditoria com ator, unidade, origem e o **conjunto de episódios exibidos** (só ids, gravado uma vez por conteúdo, endereçado por SHA-256); retenção desse registro e quem pode consultá-lo (perfil Auditoria, relatórios) a definir | V17, `ServicoPlantao` | V-08, V-10 |
| 48 | Recebimento: a tela mostra a situação atual (entregue × agora, por campo) e a confirmação vale para o conteúdo entregue **e** essa situação; qualquer mudança antes do clique exige nova leitura (409). Rótulos (nomes de etapa, setor, motivo, responsável) vêm do cadastro atual e não entram na assinatura | `Comparacao`, `ServicoPlantao` | V-01 |
| 49 | Indicadores calculados num único instantâneo do banco (REPEATABLE READ somente leitura): o "agora" da resposta é o do início da leitura; gravações confirmadas durante o cálculo entram só na próxima consulta | `TransacaoIndicadoresJdbc` | V-09 |
| 50 | Histórico das versões das regras de alerta mantido a partir da V18; versões anteriores à implantação aparecem como "detalhes indisponíveis" (não reconstruídas da auditoria). Retenção desse histórico a definir | V18 | V-05, V-10 |
| 51 | Relatórios gerenciais são **extensão aprovada** (issue #9), não requisito da ERS original; **todas as fórmulas são propostas** (`relatorios-v2`, `docs/relatorios.md`); as definições usadas vão dentro de cada relatório (assinadas) e no CSV/impressão | `DicionarioRelatorios`, V19, V20 | V-09 |
| 52 | Tempos de gargalo reconstruídos da linha do tempo pelo instante do fato: com filtro de setor, etapas e bloqueios concluídos contam pelo setor em que **começaram**; minutos bloqueados, só o trecho vivido no setor; estoque pelo setor atual; encerramentos pelo setor final | `fluxo.rel_*` | V-09 |
| 53 | Percentis contínuos (P50/P90 interpolados); idade de esperas em curso separada da duração das concluídas; redefinição do mesmo motivo continua o mesmo bloqueio | V19 | V-09 |
| 54 | "Pendência encerrada no prazo" usa o **último** prazo registrado; agregados por responsável usam o **tipo** do responsável **atual** (o modelo não guarda o anterior) | `fluxo.rel_pendencias` | V-09 |
| 55 | Lista operacional nominal de pendências para quem tem `INDICADORES_VER` **e** `EPISODIO_VER` (coordenação); Direção só agregados; limite técnico de **2000** pendências — acima disso a lista não é exibida (nunca parcial); leitura registrada (conjunto de episódios) | `ServicoRelatorios.LIMITE_LISTA` | V-06, V-08, V-10 |
| 56 | **Grupos pequenos:** nenhuma supressão de contagens pequenas nos relatórios (inclusive para a Direção, com filtros combinados); o risco de reidentificação é declarado em todo relatório. O limiar e a regra de supressão são decisão institucional | limitação `GRUPOS_PEQUENOS` | V-08 |
| 57 | Alertas só no instante de referência (regras ativas agora); a Evolução não compara alertas e informa as alterações de regras registradas desde a V18; acima de 10 000 abertos, "casos em alerta" fica indisponível | `ServicoRelatorios` | V-05, V-09 |
| 58 | Qualidade: sem prazo oficial de desatualização — contagem só para regras "sem atualização" configuradas; destino/protocolo fora das etapas que os exigem são **cobertura** (opcional), não falha | `fluxo.rel_qualidade` | V-05, V-09 |
| 59 | Exportação: CSV (UTF-8 com BOM, `;`, CRLF, decimal com vírgula, apóstrofo antes de texto semelhante a fórmula); PDF só pela impressão do navegador (A4 paisagem); sem XLSX e sem PDF no servidor. Exportação e "impressão solicitada" registradas na auditoria sem conteúdo nominal; a conclusão da impressão não é comprovável | `nucleo/relatorios.js`, `ServicoRelatorios` | V-08, V-10 |
| 60 | Comprovante do relatório (HMAC-SHA256) vale **2 h**, só para o mesmo usuário e unidade, com a permissão atual; a chave é aleatória **por processo** (reinício invalida comprovantes; várias instâncias exigirão chave compartilhada) | `TokenRelatorio`, `RelatoriosConfig` | V-10 |
| 61 | Relatórios até 366 dias (datas locais da unidade); Evolução compara com o período anterior de mesmo número de dias locais (com horário de verão, a duração em horas pode diferir em 1 h — informada no resultado) | `ServicoRelatorios` | V-09 |
| 62 | **Evolução só com períodos encerrados**: fim até ontem no fuso da unidade (o servidor recusa hoje). Alternativa não adotada: recortes parciais equivalentes (ex.: até a mesma hora) | `ServicoRelatorios`, `telas/relatorios.js` | V-09 |
| 63 | Filtro de setor em fatos históricos: setor em vigor no instante do fato pela linha do tempo (registros retroativos: período pelo registro, setor pelo fato; a transferência pertence ao destino); fato sem setor determinável fica fora do filtro e é sinalizado, nunca atribuído ao setor atual. Transferências no mesmo instante: vale a ordem `ocorrido_em → registrado_em → id`; no início de um bloqueio, a transferência no mesmo instante prevalece (como em Gargalos/Evolução) | V20, V21 | V-09 |
| 64 | Bloqueio = intervalo normalizado: mudar só o detalhe (mesmo motivo) não é novo bloqueio; categoria = a registrada no início do intervalo | V20 | V-04, V-09 |
| 65 | Evolução: variação absoluta na unidade da métrica (contagem, minutos, pontos percentuais); variação relativa em % do anterior (ausente com zero; não se aplica a métricas em %) | `nucleo/relatorios.js` | V-09 |
| 66 | Homologação por **Docker Compose** numa única máquina, uma instância da aplicação; Windows via Docker Desktop + WSL2 (mesmo script) | `deploy/homologacao`, ADR-0011 | V-10 |
| 67 | HTTPS de homologação com CA interna do Caddy publicada só em 127.0.0.1; em implantação, certificado e domínio da instituição | `Caddyfile` | V-10 |
| 68 | Aplicação aceita `X-Forwarded-*` **somente** do IP fixo do proxy (expressão exata); o proxy descarta os do cliente | `entrypoint.sh`, `Caddyfile` | V-10 |
| 69 | Segredos em arquivos locais (dir. 700, arquivos 644 por exigência do bind mount); proposta para implantação: cofre institucional | `fluxo.sh preparar` | V-10 |
| 70 | Backup lógico (`pg_dump -Fc`) do mesmo instantâneo do manifesto, **sem dados de sessão**; proposta: diário + antes de atualizar; retenção 7 diários/4 semanais/3 mensais; RPO até 24 h; RTO de algumas horas; restauração de teste mensal; cópias cifradas fora do servidor | `fluxo.sh backup`, `homologacao.md` §6 | V-08, V-10 |
| 71 | Restauração sempre em projeto/volume separado; promoção é decisão humana; sessões restauradas invalidadas; todos entram de novo | `fluxo.sh restaurar` | V-10 |
| 72 | Dados de demonstração só por comando explícito (contas com senhas públicas); nunca em ambiente acessível por terceiros | `fluxo.sh demo` | V-08 |
| 73 | Sem rollback automático de migração: reverter a aplicação só sem migração nova aplicada; caso contrário, restaurar o banco | `homologacao.md` §7 | V-10 |
| 25 | **Limitação conhecida:** o contexto da transação (GUCs) pode ser definido pelo próprio papel da aplicação; as regras do banco não resistem a SQL arbitrário como `fluxo_app`. Proposta: contexto não forjável (etapa própria, afeta todas as políticas) | V1/V8 | V-10 |

**Não decidido, e não deve ser decidido pelo desenvolvedor:** limites de alerta e SLA por etapa
(V-05), níveis e destinatários de escalonamento (V-06), fórmulas oficiais dos indicadores e relatórios (V-09), supressão de grupos pequenos (V-08),
disponibilidade de interface com o Regula Piauí (V-03), retenção, RPO/RTO e MFA em produção (V-10).
