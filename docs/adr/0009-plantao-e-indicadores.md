# ADR-0009 — Passagem de plantão e indicadores

- **Status:** proposto (PR da etapa 7), implementado em `V16` e nos módulos `plantao` e `indicador`
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
   - No **recebimento**, a tela mostra o conteúdo entregue e as diferenças desde a entrega: casos encerrados, novos e alterados; pendências encerradas, novas e alteradas.
   - A assinatura do recebimento cobre o conteúdo entregue, as diferenças e o conteúdo atual exibido. Se algo muda antes do clique, a resposta é 409 `RECEBIMENTO_DESATUALIZADO`, sem efeito.
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

## Consequências

- Uma passagem pode ser recusada várias vezes numa unidade muito movimentada: cada mudança relevante exige nova leitura. É o comportamento pedido (nenhuma confirmação silenciosa). O custo é uma recarga.
- O limite de "acima do limite" no histórico é o **vigente** na consulta; alterações posteriores da regra mudam o histórico. Essa limitação está registrada no dicionário.
- Os percentuais de motivos somam o tempo bloqueado sobreposto ao período. A divisão por setor usa o setor **atual ou final** do episódio, não o setor no momento do bloqueio. Essa limitação está registrada para V-09.
