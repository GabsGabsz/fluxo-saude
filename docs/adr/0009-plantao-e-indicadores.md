# ADR-0009 — Passagem de plantão e indicadores

- **Status:** proposto (PR da etapa 7), implementado em `V16`, `V17`, `V18` e nos módulos `plantao` e `indicador`; revisado no PR #10
- **Data:** 2026-10-07
- **Requisitos:** M06 (RF-016, RF-017, CA-07, §10.4), M07 (RF-019, RF-020, CA-09, §10.5), RF-039 (parcial),
  RNF-002, RNF-003, RNF-014, RNF-015, RNF-017; pendências institucionais V-01, V-06, V-09

## Contexto (texto da ERS v1.1)

**Passagem de plantão**

- RF-016: "gerar passagem de plantão contendo casos críticos, pendências e ações esperadas".
- RF-017: "registrar quem entregou e quem recebeu".
- §10.4: "lista estruturada dos casos que exigem continuidade, separando casos críticos, transferências, pendências vencidas e ações prometidas para o turno seguinte. O recebimento deve ser confirmado pelo usuário do novo plantão".
- CA-07: "consolida automaticamente casos e pendências configurados".

**Indicadores**

- RF-019: "permanência média/mediana e quantidade de casos acima dos limites".
- RF-020: "distribuição dos motivos de atraso/gargalo".
- §10.5: permanência, percentual acima dos limites, transferências, solicitação→aceite, aceite→saída, motivos e tendência.
- RF-039 (S): "dicionário de indicadores com fórmula, marco inicial, marco final, inclusões/exclusões e unidade de medida".
- V-09: as fórmulas oficiais ainda dependem da instituição.

A ERS não define:

- horários de turno — RF-040 é só "Should" e não há calendário configurado;
- quem cancela uma passagem;
- regras de composição além das quatro seções.

## Decisão — passagem de plantão

1. **Fluxo.**
   - Um profissional com `PLANTAO_GERENCIAR` (coordenação, enfermagem ou médico) **prepara** a passagem: o servidor gera o conteúdo a partir do estado atual.
   - Esse profissional **entrega** a passagem.
   - **Outro** profissional com a mesma permissão **recebe**. O banco recusa um recebedor igual a quem entregou.
   - Há uma passagem pendente por unidade (índice único parcial).
   - O autor pode **cancelar** com justificativa. Quem cancela é uma proposta, a validar em V-01/V-06.
2. **Composição (CA-07).**
   - Entram **todos** os episódios abertos da unidade, para que nada se perca.
   - Cada caso recebe marcações, todas propostas:
     - *crítico*: alerta operacional ativo pelo mesmo motor da Torre **ou** pendência de criticidade operacional CRÍTICA;
     - *transferência*: protocolo ou destino registrados, ou etapa de natureza Aceito/Transporte.
   - As pendências abertas trazem responsável e prazo (as "ações esperadas"). Uma pendência é *vencida* quando o relógio do servidor passou do prazo.
   - A tela mostra as quatro seções da ERS, mais "demais casos".
   - Não há paginação. Acima de 2000 casos a passagem **não é gerada**: nunca existe passagem parcial.
3. **Período.**
   - Vai da entrega da última passagem **recebida** até esta entrega. O banco calcula o início no gatilho de inserção.
   - Não há horário fixo de turno.
4. **Confirmação = conteúdo visto.**
   - O conteúdo tem forma canônica: JSON determinístico, só com ids, códigos, instantes e versões.
   - Sua assinatura SHA-256 vai para a tela.
   - Na **entrega**, o servidor recompõe o conteúdo na mesma transação. Se a assinatura difere, responde 409 `PASSAGEM_DESATUALIZADA` e nada é gravado. Isso cobre episódio alterado ou encerrado, pendência criada ou resolvida, prazo vencido e regra alterada.
   - No **recebimento**, a tela separa duas coisas:
     - **Situação atual**: cada caso ou pendência que mudou desde a entrega, com o valor **na entrega** e o valor **agora**, campo a campo (etapa, setor, bloqueio/motivo, marcações, alertas; responsável, prazo, vencimento, criticidade). Cada pendência vem ligada ao seu caso. Casos e pendências novos ou encerrados também aparecem.
     - **Conteúdo entregue**: como estava na entrega, com marca nos itens que mudaram depois.
   - O domínio (`Comparacao`) considera alterado qualquer campo diferente. Assim, conteúdo entregue + mudanças exibidas reconstroem exatamente o conteúdo atual (`ComparacaoTest`).
   - A assinatura do recebimento cobre o conteúdo entregue, as diferenças e esse conteúdo atual, isto é, exatamente o que a tela mostra. Se algo muda antes do clique, a resposta é 409 `RECEBIMENTO_DESATUALIZADO`, sem efeito.
   - Os rótulos (nomes de etapa, setor, motivo e responsável) são lidos do cadastro atual na mesma consulta e não entram na assinatura; os identificadores e códigos que eles nomeiam entram.
   - **Alertas (revisão do PR #10).** Quando os alertas de um caso mudam, a tela lista **cada** alerta, pareado por regra (e pendência, em "pendência vencida"): encerrado, novo, alterado (versão da regra, tipo, referência ou instante atingido) ou sem mudança — inclusive quando a quantidade é a mesma (A trocado por B). Cada lado mostra regra, versão, tipo, limite, ação esperada, referência, instante atingido e a pendência vinculada.
   - **Regra na versão do alerta (V18).** O conteúdo guarda regra e versão; nome, limite e ação esperada vêm de `fluxo.regra_alerta_versao`, cópia imutável de cada versão gravada por gatilho a cada INSERT/UPDATE da regra. Nunca se usa a configuração atual no lugar da versão histórica: versão anterior ao histórico (só a vigente foi copiada na implantação da V18) aparece como "detalhes desta versão indisponíveis". A situação atual da regra (alterada ou desativada depois) aparece à parte, como aviso. Como versão é imutável e está na assinatura, os dados exibidos de cada alerta são cobertos por ela; uma nova versão depois da leitura muda o conteúdo atual e dá 409.
   - A tela de plantão não lê mais `/api/config/regras-alerta`: os rótulos vêm na resposta da passagem, por versão, também no conteúdo entregue de passagens já recebidas ou canceladas.
   - A versão da passagem é conferida, o que impede duplo recebimento ou cancelamento.
   - A interface nunca reenvia sozinha: o envio fica bloqueado até "Recarregar dados".
5. **O que fica registrado e o que é consultado.**
   - **Registrado na entrega**, em `passagem_conteudo`, imutável:
     - por episódio: id, versão, etapa, setor, motivo, categoria e instantes;
     - marcações e alertas (regra, versão, referência);
     - pendências: id, versão, categoria, criticidade, prazo, vencida e responsável (id ou papel).
   - **Na linha da passagem:** totais e assinatura. No recebimento, quem recebeu, quando, a assinatura e **só as contagens** das diferenças.
   - **Consultado do estado atual,** só para quem tem acesso nominal:
     - nome do paciente e descrição da pendência;
     - nomes de setor, etapa, motivo e profissional.

     Assim nada nominal é duplicado na passagem, em log ou na auditoria. A auditoria por linha redige a observação e a justificativa.
   - **Leituras registradas (V17, RNF-002).** Prévia e detalhe exibem nomes de muitos pacientes de uma vez, então registram a leitura como `CONSULTA_PREVIA_PASSAGEM` e `CONSULTA_PASSAGEM`, no padrão das consultas de caso e paciente:
     - ator, unidade, IP, correlação e instante vêm do contexto do banco (`auditoria.registrar_consulta`);
     - as referências são o **conjunto de episódios exibidos**, gravado uma única vez por unidade em `auditoria.conjunto_consultado`, endereçado pelo SHA-256 da lista ordenada de ids. O evento da cadeia leva só o hash e a contagem: cabe no limite de 4 KB, não tem nomes e leituras repetidas do mesmo conjunto não duplicam a lista;
     - o conjunto é imutável, isolado por unidade (RLS), só aceita episódios da própria unidade e o banco recusa lista que não corresponda ao hash. Como o hash está no registro encadeado, trocar o conjunto seria detectável;
     - no detalhe, o conteúdo entregue é referenciado pela própria passagem (registro imutável); só os casos exibidos além dele entram no conjunto. A prévia também registra a assinatura do conteúdo exibido;
     - leitura recusada (permissão, outra unidade, inexistente) não gera registro de leitura.
6. **Sem efeitos colaterais.** A passagem não resolve pendências, não registra ciência, não muda responsáveis e não encerra episódios. Os testes de serviço, IT e E2E conferem isso.
7. **Banco (V16).**
   - RLS por unidade, mais política **restritiva** de papel para ler e escrever (`ctx_gerencia_plantao`).
   - Autoria e instantes vêm do contexto do banco.
   - Transições permitidas: ENTREGUE→RECEBIDA e ENTREGUE→CANCELADA. O conteúdo entregue é imutável.
   - Não há DELETE.
   - Auditoria semântica: `PASSAGEM_ENTREGUE`, `PASSAGEM_RECEBIDA` e `PASSAGEM_CANCELADA`, só com contagens.

## Decisão — indicadores

1. **Retrato atual × histórico.** São duas seções e dois cálculos separados.
   - **Retrato:** agora, pelo relógio do servidor, sobre **todos** os abertos. Mostra casos ativos, contagem por etapa e por motivo, pendências vencidas e casos acima de cada limite configurado (mesmo motor dos alertas).
   - **Histórico:** período em datas locais. Mostra permanência média e mediana, encerrados acima de cada limite "tempo total", saídas por desfecho e transferências, solicitação→aceite, aceite→saída, motivos e volume diário.
2. **Fonte.**
   - Funções SQL `fluxo.ind_*`, `SECURITY INVOKER` (o RLS por unidade vale), agregadas **no banco** sobre todos os registros. Nada é calculado a partir da lista paginada da Torre.
   - Os percursos (solicitação, aceite, bloqueios) são reconstruídos da **linha do tempo** (`evento_episodio`, instante do fato), não do estado atual.
3. **Período e fuso.**
   - `[início 00:00, (fim + 1) 00:00)` no fuso da unidade. O PostgreSQL trata o horário de verão; o teste cobre dias de 23 h e de 25 h.
   - O instante exato do início entra; o instante exato do fim não entra.
   - Limite de 366 dias; datas futuras são recusadas.
4. **Ausência de dados.**
   - Média e mediana nulas aparecem como "sem dados", nunca como 0.
   - Percentual com base zero fica ausente; não há divisão por zero.
   - Sem regra configurada, o indicador aparece como "indisponível", não como zero.
5. **Dicionário (RF-039).**
   - Nove verbetes explícitos em `DicionarioIndicadores`, servidos por `GET /api/indicadores/dicionario` e documentados em [`docs/indicadores.md`](../indicadores.md).
   - **Todas as fórmulas são proposta (V-09).**
   - O dicionário **configurável** pelo usuário não foi implementado; RF-039 fica parcial.
6. **Privacidade.**
   - `INDICADORES_VER` (coordenação e direção).
   - A resposta não tem nome, CNS, prontuário, id de paciente ou episódio, nem link ou filtro que leve a detalhe nominal. O único filtro é o setor, que não é nominal.
   - Não há rankings de profissionais, metas nem limites clínicos.
7. **Desempenho.**
   - Índices de entradas por unidade e instante, e de eventos de etapa e bloqueio por unidade, tipo e instante.
   - Script `backend/src/test/sql/desempenho-indicadores.sh`, com 50 mil episódios, 250 mil eventos e período de 30 dias, no PostgreSQL 14 local:

     | Indicador | Tempo |
     |---|---|
     | Permanência | ~10 ms |
     | Solicitação→aceite | ~170 ms |
     | Aceite→saída | ~3 ms |
     | Motivos | ~25 ms |
     | Volume | ~4 ms |

   - Os planos usam `episodio_encerrados_idx` e `evento_episodio_indicadores_idx`.
   - Se o tempo de consulta se esgotar, a resposta é 503 `CONSULTA_DEMORADA`, não erro genérico.

## Decisão — visão única dos indicadores (revisão do PR #10)

Uma resposta de indicadores reúne várias consultas (retrato, situações abertas, desfechos, permanência, limites, tempos, motivos, volume). Em READ COMMITTED cada uma vê o que estava confirmado no seu instante: um encerramento confirmado entre duas consultas podia aparecer em umas e não em outras.

Opções avaliadas:

| Opção | Avaliação |
|---|---|
| Uma única consulta SQL para tudo | Coerente, mas junta nove cálculos num comando difícil de manter e testar; perde a separação por indicador. |
| Exportar o instantâneo (`pg_export_snapshot`) | Exige coordenar conexões; sem ganho para leituras numa só conexão. |
| REPEATABLE READ para todas as transações | Rejeitada: a cadeia de auditoria (ADR-0003) exige READ COMMITTED para encadear sem bifurcar. |
| **REPEATABLE READ somente leitura só nos indicadores** | **Escolhida.** |

Como funciona (`ExecutorTransacional.executarLeituraConsistente`):

- transação própria, REPEATABLE READ e READ ONLY; o primeiro comando fixa o modo (`SET TRANSACTION ...`) e ele é conferido, com falha fechada se não estiver ativo;
- depois, o mesmo `fluxo.aplicar_contexto` de sempre (revalidação da sessão, versão de credencial e papéis) e o mesmo RLS, tudo no mesmo instantâneo;
- não grava nada: READ ONLY recusa INSERT e o gatilho da cadeia recusa isolamento diferente de READ COMMITTED. Por isso os indicadores não podem registrar auditoria nessa transação; se um dia precisarem, será numa transação READ COMMITTED separada;
- leitura pura em REPEATABLE READ não sofre erro de serialização nem bloqueia gravações;
- o isolamento das demais transações não muda.

Efeitos:

- o instantâneo é tirado no primeiro comando; o "agora" da resposta é lido logo depois. Gravações confirmadas durante o cálculo entram inteiras na próxima consulta;
- a transação segura o horizonte de limpeza (VACUUM) enquanto dura; o cálculo leva centenas de milissegundos e o `statement_timeout` limita cada consulta.

Testes: `concorrencia-indicadores.sh` (sessões reais; encerramento confirmado entre desfechos e as demais consultas não aparece em nenhuma; controle em READ COMMITTED mostra a mistura) e `IndicadoresConsistenciaIT` (serviço com a transação real; outra requisição HTTP encerra um episódio no meio da resposta).

A passagem de plantão continua em READ COMMITTED porque grava e audita: lê o estado atual numa única consulta (`casosAbertos`) e a confirmação recompõe tudo na transação que grava, recusando com 409 qualquer diferença do que foi exibido.

## Decisão — limitações visíveis junto dos resultados

As limitações não ficam só no dicionário:

- "Encerrados acima dos limites": aviso de que o limite usado é o **vigente** hoje, aplicado a todo o período;
- com filtro de setor: aviso de que cada episódio conta no setor **atual** (abertos) ou **final** (encerrados), também junto dos motivos;
- cada bloco do histórico mantém a marca "Proposta (V-09)".

RF-039 continua parcial (dicionário fixo) e as fórmulas continuam propostas até a validação institucional (V-09).

## Consequências

- Uma passagem pode ser recusada várias vezes numa unidade muito movimentada: cada mudança relevante exige nova leitura. É o comportamento pedido (nenhuma confirmação silenciosa). O custo é uma recarga.
- O limite de "acima do limite" no histórico é o **vigente** na consulta; alterações posteriores da regra mudam o histórico. Essa limitação está registrada no dicionário.
- Os percentuais de motivos somam o tempo bloqueado sobreposto ao período. A divisão por setor usa o setor **atual ou final** do episódio, não o setor no momento do bloqueio. Essa limitação está registrada para V-09.
