# Roteiro de homologação do piloto (dados fictícios)

> Executar no ambiente de [homologação](homologacao.md), com dados **fictícios** carregados por
> `fluxo.sh demo --confirmo-dados-ficticios`. Usuários e senhas de teste: `e2e/dados/fixtures.json`.
> Nenhum cenário autoriza uso com pacientes reais.
> Veja também [`impedimentos-uso-real.md`](impedimentos-uso-real.md).

**Situação de cada cenário**

| Situação | Significado |
|---|---|
| **AUTO** | Coberto por teste automatizado que roda no CI contra aplicação e PostgreSQL reais. A automação **não substitui** a avaliação humana indicada. |
| **PENDENTE (humano)** | Depende de avaliação de pessoas: equipe assistencial, gestão, TI ou encarregado de dados. Registrar data, avaliador e resultado. |

Coluna **Evidência**: o que coletar e anexar ao registro da homologação (capturas, relatórios do CI,
arquivos). Nunca anexe senhas, segredos nem backups.

**Pré-condição geral (P0):** `fluxo.sh preparar` + `subir` + `demo --confirmo-dados-ficticios`;
CA de homologação instalada no navegador de teste.

## A. Acesso, perfis e unidades

| ID | Pré-condições | Passos | Resultado esperado | Evidência | Situação |
|---|---|---|---|---|---|
| A1 Primeiro acesso | Ambiente recém-instalado, sem administrador | `primeiro-acesso ...`. Entrar com a senha provisória. Tentar usar o sistema. Trocar a senha. | Antes da troca: só a tela de troca (API 403 `TROCA_DE_SENHA_OBRIGATORIA`). Depois: menu de administrador. A senha provisória deixa de valer. | Captura antes/depois. Job "Homologação" (passo *Primeiro acesso*). | AUTO + PENDENTE (humano: clareza da tela) |
| A2 Login e logout | P0 | Entrar como `coord.e2e`, sair, entrar com senha errada 5×. | Login ok; logout encerra a sessão; bloqueio progressivo após as falhas. | E2E `01-sessao` (credencial inválida); bloqueio: `t05`, `SessaoIT` (falhas auditadas); captura do bloqueio. | AUTO + PENDENTE (humano: limites — V-10) |
| A3 Perfis | P0 | Entrar com cada perfil (`admin.norte`, `coord.e2e`, `enf.e2e`, `direcao.e2e`). | Cada perfil vê só o menu permitido; Direção só agregados e painel pseudonimizado. | E2E `06-perfis`; capturas por perfil. | AUTO + PENDENTE (humano: matriz de perfis — V-06) |
| A4 Unidades | P0, `coord.e2e` com 2 unidades | Trocar a unidade ativa; abrir outra aba. | O conteúdo muda sem mistura; aba desatualizada recebe 409 e recarrega. | E2E `02-unidades`, `07-resiliencia`. | AUTO |
| A5 Sessão revogada | P0 | Administrador redefine a senha de um usuário logado. | A sessão do usuário cai na próxima ação ("Sua sessão foi encerrada"). | E2E `01-sessao`, `08-plantao`, `10-relatorios`. | AUTO |

## B. Episódio, pendências, alertas e ciência

| ID | Pré-condições | Passos | Resultado esperado | Evidência | Situação |
|---|---|---|---|---|---|
| B1 Abrir episódio | P0, `coord.e2e` | Abrir episódio para paciente fictício novo e para um existente (CNS fictício). | Episódio na Torre; duplicidade exige justificativa. | E2E `03-episodio`; duplicidade: `RegrasV11Test`, `t03`. | AUTO |
| B2 Evoluir | B1 | Mudar etapa com motivo, protocolo, destino, setor; registrar horário retroativo justificado. | Linha do tempo com autor e horário; retroativo além do limiar exige justificativa. | E2E `03-episodio`; captura da linha do tempo. | AUTO + PENDENTE (humano: etapas/motivos reais — V-04, V-07) |
| B3 Encerrar | B2 | Registrar desfecho. | Episódio sai da Torre; pendências abertas encerradas com justificativa automática. | `EpisodiosIT` (cenário ERS §11 via HTTP), E2E `09-indicadores` (encerramento). | AUTO + PENDENTE (humano: desfechos — V-07) |
| B4 Pendências | B1 | Criar, reatribuir, mudar prazo, resolver e cancelar. | Responsável e prazo visíveis; vencida sinalizada; texto obrigatório no encerramento. | E2E `03-episodio` (criar/resolver); demais: `EpisodiosIT`, `t03`. | AUTO |
| B5 Alertas | Regra cadastrada pelo administrador | Criar regra; deixar um caso ultrapassar o limite. | Caso destacado na Torre e em "Pacientes travados", com ação esperada; alerta ≠ risco clínico. | E2E `05-alertas`. | AUTO + PENDENTE (humano: limites — V-05) |
| B6 Ciência | B5 | Registrar ciência; o administrador altera a regra; tentar ciência na versão antiga. | Ciência registrada; versão antiga → 409 (reler). | E2E `05-alertas`. | AUTO |
| B7 Conflito de versão | Dois navegadores no mesmo caso | Alterar em um e depois no outro. | O segundo recebe 409 sem sobrescrever. | E2E `04-conflito`. | AUTO |

## C. Plantão, indicadores e relatórios

| ID | Pré-condições | Passos | Resultado esperado | Evidência | Situação |
|---|---|---|---|---|---|
| C1 Passagem de plantão | Casos abertos | Preparar e entregar; outro profissional recebe; mudar um caso antes do recebimento. | Conteúdo completo; recebimento por outro usuário; mudança antes do clique → 409. | E2E `08-plantao`; captura da passagem. | AUTO + PENDENTE (humano: composição e turnos — V-01) |
| C2 Indicadores | Casos encerrados | Consultar o período; comparar com cálculo manual. | Valores iguais ao cálculo manual; fórmulas marcadas como propostas. | E2E `09-indicadores`; planilha de conferência. | AUTO + PENDENTE (humano: fórmulas — V-09) |
| C3 Relatórios | Dados do período | Gerar os 5 relatórios; filtros de setor/etapa/categoria. | Cabeçalho completo, limitações e definições junto de cada seção; Direção sem dado nominal. | E2E `10-relatorios`. | AUTO + PENDENTE (humano: utilidade e linguagem) |
| C4 CSV e impressão | C3 | Baixar CSV; imprimir/salvar em PDF. | CSV com a mesma assinatura e definições; fórmulas neutralizadas; impressão legível em A4 paisagem. | Arquivos CSV/PDF fictícios; E2E `10-relatorios`. | AUTO + PENDENTE (humano: legibilidade do impresso) |
| C5 Isolamento entre unidades | Dados nas duas unidades | Consultar com usuário de uma unidade só. | Nada da outra unidade aparece (tela, API, relatórios). | `t01` (RLS), E2E `02-unidades`, `RelatoriosIT`. | AUTO |

## D. Ambiente, segurança e recuperação

| ID | Pré-condições | Passos | Resultado esperado | Evidência | Situação |
|---|---|---|---|---|---|
| D1 Instalação reproduzível | Máquina limpa (Linux e Windows/WSL2) | Seguir [homologacao.md](homologacao.md) §3. | Ambiente no ar só pelos comandos documentados. | Log do terminal (sem senhas); job "Homologação". | AUTO (Linux) + PENDENTE (humano: Windows/WSL2) |
| D2 HTTPS e cabeçalhos | P0 | Abrir pelo navegador; inspecionar cookies e cabeçalhos. | `__Host-FLUXO` Secure/HttpOnly/SameSite=Strict; `XSRF-TOKEN` Secure e Path=/ na emissão (navegador sem cookies) e continua Secure no reuso; HSTS, CSP, frame DENY. | Job "Homologação" (*cabecalhos*); captura das ferramentas do navegador. | AUTO |
| D3 Proxy confiável | P0 | Enviar `X-Forwarded-For` forjado (via proxy e direto na aplicação). | O IP registrado na auditoria é o real. | Job "Homologação". | AUTO |
| D4 Migração com falha | Projeto de teste | Corromper a senha do dono e rodar `subir`. | `subir` falha; a aplicação não inicia. | Job "Homologação". | AUTO |
| D5 Persistência | Dados criados | `reiniciar`; `parar` + `subir`. | Dados e sessões preservados. | Job "Homologação". | AUTO |
| D6 Indisponibilidade do banco | P0 | Parar o banco; `diagnostico`; religar. | `health` 503; diagnóstico aponta "BANCO INDISPONÍVEL"; aplicação volta sozinha. | Job "Homologação". | AUTO |
| D7 Backup | Dados criados | `backup`. | `.dump` + `.manifesto` + `.sha256` com permissão 600; sem dados de sessão. | Manifesto (sem dados pessoais). | AUTO |
| D8 Restauração | D7 | `restaurar ARQ` em projeto separado (imagem registrada no backup); conferir dados conhecidos pela API e pela tela; usar sessão antiga no recuperado; tentar com imagem incompatível. | Imagem compatível exigida antes de migrar (incompatível/ausente recusada sem criar nada); cadeia de auditoria íntegra; contagens = manifesto; estado final igual após subir; sessão antiga recusada (401); original intacto. | Relatório `restauracao-*.txt` (artefato `homologacao-evidencias`). | AUTO + PENDENTE (humano: ensaio com tempo medido — RTO) |
| D9 Atualização | Ambiente no ar | `atualizar`. | Backup prévio; aplicação parada durante a migração separada; nova imagem ativa; dados preservados. | Relatório `atualizacao-*.txt`; job "Homologação". | AUTO |
| D10 Atualização malsucedida | Imagens de teste com migrações artificiais (`teste-atualizacao/`) | (a) migração nova falha sem avanço; (b) V9001 confirma e V9002 falha; restaurar o backup prévio com a imagem anterior; (c) no original parcial: `atualizar` normal, imagem com V9001 alterada, correção que ainda falha e, por fim, `atualizar --imagem <corrigida> --continuar-parcial`. | (a) aplicação anterior restabelecida, esquema inalterado; (b) aplicação e proxy parados (código 3), imagem anterior recusada pelo `subir`, restauração isolada com a imagem do backup conferida pela API; (c) atualização normal e imagem alterada recusadas sem mudar nada; correção que falha → código 4, parado, A não volta; correção C → 9001+9002, C em execução, dados preservados, backups pré-B e do estado parcial preservados. | Relatórios `atualizacao-*.txt` e `restauracao-*.txt`; job "Homologação". | AUTO + PENDENTE (humano: ensaio com equipe e decisão de promoção) |
| D11 Acessibilidade | — | Navegação só por teclado e com leitor de tela (NVDA/Orca); checklist eMAG. | Sem bloqueios. | Relatório de avaliação. | PENDENTE (humano — RNF-016) |
| D12 Usabilidade em plantão | Equipe voluntária, dados fictícios | Tarefas cronometradas (achar casos travados em < 30 s — ERS §12). | Critérios de aceite da ERS atendidos. | Registro das sessões. | PENDENTE (humano) |

## Registro

Para cada execução, registre:
- data;
- versão (commit) e projeto;
- quem executou;
- cenários executados;
- resultados;
- desvios;
- decisões.

Cenários marcados **PENDENTE (humano)** não podem ser dados como aprovados apenas porque o CI passou.
