# Fluxo Saúde — Torre de Controle Assistencial

Plataforma de gestão operacional do fluxo assistencial: identifica pacientes parados, mede há
quanto tempo aguardam, registra o gargalo atual, define a próxima ação e o responsável.
**Não é prontuário e não substitui a regulação oficial.** Especificação: **ERS v1.1** (revisão técnica).

## Estado atual — etapa 7 (passagem de plantão e indicadores) — em revisão no PR

As etapas 4 a 6 (gestão de usuários, alertas e "Pacientes travados", interface web — PRs #6, #7 e #8) já estão incorporadas à `main`. Esta etapa acrescenta a **V16** (passagem de plantão e funções de indicadores).

| Camada | Conteúdo | Verificação |
|---|---|---|
| Banco (PostgreSQL 16) | Esquema do núcleo, regras críticas em `CHECK`/triggers, RLS por unidade (inclusive usuários e auditoria), auditoria imutável com cadeia SHA-256, login por funções controladas, observações (V9), margem de relógio (V10), gestão de usuários só por funções com alcance conferido (V11), versão de credencial conferida em toda transação (V12), regras de alerta e ciência (V13), ciência só na versão da regra vista (V14), unidades do próprio usuário para a interface (V15) | `backend/src/test/sql` — 14 suítes + 5 testes de concorrência |
| Domínio (Java 21, sem framework) | `Episodio`, `Pendencia`, `FluxoConfigurado`, ajuste manual de horário, pseudônimo, UUIDv7 | 45 testes JUnit, incl. o cenário completo da ERS §11 |
| Identidade (núcleo puro) | Política de senha, limitadores, matriz de permissões, serviço de autenticação | `ServicoAutenticacaoTest`, `MatrizPermissoesTest`, ... |
| Casos de uso (núcleo puro) | `ServicoEpisodios`, `ServicoPendencias`, `ServicoConsultas`: permissão na unidade ativa, versão lida (409), ajuste manual de horário, painel pseudonimizado | `ServicosDeAplicacaoTest` (portas em memória) |
| Gestão de usuários (núcleo puro) | `ServicoGestaoUsuarios`: alcance de lotação × alcance de conta, sem autoalteração, senha provisória, encerramento de sessões | `ServicoGestaoUsuariosTest` |
| Alertas (núcleo puro) | `MotorDeAlertas` (regras × estado do episódio × relógio do servidor), `ServicoAlertas` (travados, destaque, ciência, configuração) | `MotorDeAlertasTest` (fronteiras com relógio controlado), `ServicoAlertasTest` |
| Aplicação (Spring Boot 4.1) | Login/sessão no servidor, CSRF SPA, revalidação no banco por transação, API REST de episódios/pendências/Torre e administração de usuários, erros padronizados; migração em job separado | `SessaoIT`, `EpisodiosIT` (cenário ERS §11 via HTTP), `GestaoUsuariosIT`, `SessaoSobreviventeIT`, `AlertasIT`, `CatalogoIT`, `InterfaceEstaticaIT`, `BancoDeDadosIT` (Testcontainers) |
| Passagem de plantão e indicadores ([ADR-0009](docs/adr/0009-plantao-e-indicadores.md)) | Passagem entregue por um profissional e recebida por outro, confirmação condicionada ao conteúdo visto (assinatura SHA-256), sem passagem parcial; indicadores agregados no banco (retrato atual × histórico do período, fuso da unidade), dicionário com fórmulas propostas ([`docs/indicadores.md`](docs/indicadores.md)) | `t12`, `t13` (SQL), `ComposicaoPassagemTest`, `ServicoPlantaoTest`, `ServicoIndicadoresTest`, `PlantaoIT`, `IndicadoresIT`, E2E `08-plantao`, `09-indicadores` |
| Interface web (ES modules, sem build — [ADR-0008](docs/adr/0008-interface-web.md)) | Login, troca de senha, unidade ativa, Torre de Controle, abrir episódio, detalhe do caso (etapa/desfecho, motivo, protocolo, destino, setor, observação, pendências, linha do tempo, ciência), Pacientes travados, painel pseudonimizado, usuários/lotações, regras de alerta | `backend/src/test/js` (`node --test`) e `e2e/` (Playwright contra o jar e o PostgreSQL reais) |

Mapa requisito → código → teste: [`docs/rastreabilidade.md`](docs/rastreabilidade.md).
Escolhas que precisam de validação com a equipe: [`docs/decisoes-a-validar.md`](docs/decisoes-a-validar.md).

## Decisões de arquitetura

- [ADR-0001](docs/adr/0001-arquitetura.md) — monólito modular, domínio independente, defesa em profundidade no banco
- [ADR-0002](docs/adr/0002-autenticacao-e-sessao.md) — sessão no servidor, Argon2id, CSRF, bloqueio progressivo, autorização em duas camadas
- [ADR-0003](docs/adr/0003-auditoria-imutavel.md) — auditoria por trigger, imutável, encadeada e redigida
- [ADR-0004](docs/adr/0004-isolamento-por-unidade.md) — RLS por unidade e mínimo privilégio
- [ADR-0005](docs/adr/0005-tempo-concorrencia-duplicidade.md) — horário do servidor e ajuste manual, controle otimista, duplicidade sem bloqueio (ERS v1.1)
- [ADR-0006](docs/adr/0006-gestao-de-usuarios.md) — gestão de usuários: alcance por lotação × conta, autoalteração, último administrador, contas órfãs
- [ADR-0007](docs/adr/0007-alertas-e-travados.md) — alertas operacionais calculados no servidor, "Pacientes travados", ciência
- [ADR-0008](docs/adr/0008-interface-web.md) — interface web em ES modules sem build, mesma origem, estado só em memória, unidade esperada conferida no servidor
- [ADR-0009](docs/adr/0009-plantao-e-indicadores.md) — passagem de plantão (entrega × recebimento, assinatura do conteúdo visto) e indicadores (funções SQL agregadas, dicionário proposto)

## Rodando localmente

Pré-requisitos: JDK 21, Maven 3.9+, Docker.

```bash
cp .env.example .env            # e troque TODAS as senhas
docker compose up -d postgres   # cria papéis fluxo_owner / fluxo_app e o banco "fluxo"

cd backend
export $(grep -v '^#' ../.env | xargs)
mvn verify                      # testes de unidade + integração (Testcontainers)

# 1) job de migração (único processo que recebe a senha do papel dono)
mvn spring-boot:run -Dspring-boot.run.profiles=migracao
# 2) aplicação (só a credencial do papel restrito); só /actuator/health é público
unset FLUXO_DB_OWNER_PASSWORD && mvn spring-boot:run
```

Primeira unidade e primeiro administrador (uma vez, após as migrações):

```bash
cd backend && mvn -q spring-boot:run -Dspring-boot.run.main-class=br.fluxosaude.identidade.infra.GerarHashSenha
# copie o hash gerado e rode, como fluxo_owner, o script com as variáveis indicadas no cabeçalho:
psql -U fluxo_owner -d fluxo -v ON_ERROR_STOP=1 -v unidade_codigo=UPA_BJ ... -f infra/db/admin-inicial.sql
```

### Interface web (sistema completo)

Com a aplicação no ar, a interface é servida pela própria aplicação, na mesma origem da API:
abra `https://<servidor>/` (em produção, atrás de TLS). Em desenvolvimento por **HTTP** em
`localhost`, o cookie de sessão padrão (`__Host-FLUXO`, `Secure`) precisa ser trocado só para
esse uso local:

```bash
cd backend && unset FLUXO_DB_OWNER_PASSWORD && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.servlet.session.cookie.secure=false --server.servlet.session.cookie.name=FLUXO"
# navegador: http://localhost:8080/  (entre com o administrador inicial; ele troca a senha no 1º acesso,
# cadastra os profissionais em "Usuários" e as regras em "Regras de alerta")
```

Para subir tudo de uma vez com **dados fictícios** (o mesmo ambiente do E2E; use um PostgreSQL
descartável): `cd backend && mvn -DskipTests package && cd .. && PGHOST=localhost PGUSER=postgres
PGPASSWORD=... e2e/preparar-ambiente.sh` — usuários e senhas de teste em `e2e/dados/fixtures.json`.

A interface não guarda senha, credencial de sessão nem dado nominal no navegador
(`localStorage`/`sessionStorage`/cache offline); horários aparecem no fuso da unidade e os tempos
partem do relógio do servidor ([ADR-0008](docs/adr/0008-interface-web.md)).

API de sessão (JSON; CSRF via cookie `XSRF-TOKEN` → cabeçalho `X-XSRF-TOKEN`):

| Método | Caminho | Uso |
|---|---|---|
| GET | `/api/sessao/csrf` | obtém o cookie CSRF (público) |
| POST | `/api/sessao` | login `{login, senha}` (público) |
| GET | `/api/sessao` | sessão atual, lotações e permissões |
| GET | `/api/sessao/unidades` | unidades do próprio usuário (nome e fuso horário) |
| PUT | `/api/sessao/unidade` | troca a unidade ativa |
| PUT | `/api/sessao/senha` | troca a senha (obrigatória no 1º acesso) |
| DELETE | `/api/sessao` | logout |

Alertas e "Pacientes travados" (alerta operacional ≠ risco clínico; regras são parâmetros da
unidade — **nenhuma vem cadastrada**; ver [ADR-0007](docs/adr/0007-alertas-e-travados.md)):

| Método | Caminho | Uso |
|---|---|---|
| GET | `/api/travados` | episódios abertos que violam regra ativa: tempos, motivo, pendências (responsável, prazo), alertas, ação esperada |
| POST | `/api/episodios/{id}/alertas/ciencia` | "ciente" de uma ocorrência em alerta, na versão da regra **vista**: `regraId`, `regraVersao` (obrigatória, ≥ 0), `referenciaEm`, `pendenciaId`. Regra alterada desde a leitura → **409** (reler); sem alerta ativo → 422; regra/episódio fora do alcance → 404. Não encerra pendência |
| GET | `/api/config/regras-alerta` | regras da unidade ativa |
| POST | `/api/config/regras-alerta` | cria regra (Administrador): `nome`, `tipo`, `etapaId`, `categoria`, `limiteMinutos`, `acaoEsperada` |
| PUT | `/api/config/regras-alerta/{id}` | altera/desativa (`versao` obrigatória) |

A Torre (`GET /api/episodios`) traz `alertas` por episódio listado (com `regraVersao`); o painel coletivo, só `emAlerta`.

API de administração de usuários (perfil Administrador, sempre na **unidade ativa**; toda
alteração envia a `versao` lida → 409 se mudou; ver [ADR-0006](docs/adr/0006-gestao-de-usuarios.md)):

| Método | Caminho | Uso |
|---|---|---|
| GET | `/api/admin/usuarios` | usuários lotados na unidade ativa (papéis aqui, `possuiOutrasUnidades`, `contaGerenciavel`) |
| GET | `/api/admin/usuarios/{id}` | um usuário (404 se não estiver lotado na unidade) |
| GET | `/api/admin/usuarios/busca?login=` | conta existente para vincular (só id, versão, `vinculavel`) |
| POST | `/api/admin/usuarios` | cria conta + papéis; devolve a **senha provisória** uma única vez |
| PUT | `/api/admin/usuarios/{id}/papeis` | papéis nesta unidade; `[]` revoga o acesso nela (vincula conta existente) |
| PUT | `/api/admin/usuarios/{id}/conta` | nome, e-mail, registro — só a unidade gestora da conta, administrando todas as unidades do usuário |
| PUT | `/api/admin/usuarios/{id}/situacao` | ativa/desativa a conta — idem |
| POST | `/api/admin/usuarios/{id}/senha-provisoria` | nova senha provisória (troca obrigatória) — idem |

API da Torre (exige sessão; toda alteração envia a `versao` lida e recebe a nova — versão
desatualizada responde **409**; registro de outra unidade responde **404**). Horário opcional
`"momento": {"ocorridoEm": "...Z", "justificativaAjuste": "..."}` — omitido = relógio do servidor;
retroativo além do limiar da unidade exige justificativa e a permissão `HORARIO_AJUSTAR`.

| Método | Caminho | Uso |
|---|---|---|
| GET | `/api/episodios` | Torre: `setor`, `etapa`, `motivo`, `categoria`, `especialidade`, `responsavel`, `minutosNaEtapa`, `somenteVencidas`, `ordem` (`TEMPO_TOTAL`, `TEMPO_NA_ETAPA`, `TEMPO_BLOQUEADO`, `CRITICIDADE`, `SETOR`, `ETAPA`, `MOTIVO`, `PRAZO`), `decrescente`, `limite` (≤ 500) |
| POST | `/api/episodios` | abre episódio (`pacienteId` **ou** `novoPaciente`, `setorId`, `justificativaDuplicidade`) |
| GET | `/api/episodios/{id}` | caso + pendências + linha do tempo + observações (consulta auditada) |
| PUT | `/api/episodios/{id}/etapa` | muda etapa (`etapaId`, `motivoId`, `protocoloSistema/Numero`, `justificativa`); desfecho encerra |
| PUT | `/api/episodios/{id}/motivo` · `/protocolo` · `/destino` · `/setor` | motivo do bloqueio, protocolo externo, destino, transferência interna |
| POST | `/api/episodios/{id}/observacoes` | observação operacional (não substitui o prontuário) |
| POST | `/api/episodios/{id}/pendencias` | cria pendência (responsável: `usuarioId` **ou** `setorId` **ou** `papel`) |
| PATCH | `/api/pendencias/{id}` | reatribui e/ou altera o prazo |
| POST | `/api/pendencias/{id}/resolucao` · `/cancelamento` | encerra com texto obrigatório |
| GET | `/api/painel` | painel coletivo pseudonimizado (sem nome/CNS) |

Passagem de plantão (`PLANTAO_GERENCIAR` + acesso nominal; ver [ADR-0009](docs/adr/0009-plantao-e-indicadores.md)):

| Método | Caminho | Uso |
|---|---|---|
| GET | `/api/plantao/previa` | conteúdo atual (todos os abertos, sem paginação) + `assinatura`, período e passagem pendente |
| POST | `/api/plantao/passagens` | entrega `{assinatura, observacao}`; conteúdo mudou → **409** `PASSAGEM_DESATUALIZADA`; já há pendente → 409 `PASSAGEM_PENDENTE` |
| GET | `/api/plantao/passagens` · `/api/plantao/passagens/{id}` | histórico; detalhe com o conteúdo entregue, diferenças desde a entrega e `assinaturaRecebimento` |
| POST | `/api/plantao/passagens/{id}/recebimento` | `{versao, assinatura}` por OUTRO profissional; situação mudou → **409** `RECEBIMENTO_DESATUALIZADO` |
| POST | `/api/plantao/passagens/{id}/cancelamento` | `{versao, justificativa}`, só quem entregou |

Indicadores (`INDICADORES_VER`; resposta só agregada, sem nomes; fórmulas propostas — V-09):

| Método | Caminho | Uso |
|---|---|---|
| GET | `/api/indicadores?inicio=AAAA-MM-DD&fim=AAAA-MM-DD&setor=` | retrato atual + histórico do período (datas locais da unidade, até 366 dias) |
| GET | `/api/indicadores/dicionario` | dicionário de cálculo (RF-039) |
| GET | `/api/catalogo` | configuração da unidade ativa para a interface: setores, etapas, transições, motivos, especialidades, profissionais (só para quem vê episódios ou gere usuários) |
| GET | `/api/pacientes?cns=` · `?identificador=` | busca **exata** de paciente na unidade ativa (abrir episódio; auditada) |

Cabeçalho opcional `X-Fluxo-Unidade: <id>` (a interface sempre envia): a unidade que o cliente
exibe. Se a unidade ativa da sessão for outra (ex.: trocada em outra aba), a requisição é recusada
com **409** `UNIDADE_ATIVA_ALTERADA`, sem leitura nem gravação (rotas `/api/sessao/**` não conferem).

## Executando os testes

Os testes Java não precisam de `.env`, de credenciais de produção ou do banco do
`docker compose`: os testes de integração criam seu próprio PostgreSQL 16 descartável.

```bash
cd backend
mvn -B -ntp test                 # somente os testes unitários; JDK 21 + Maven 3.9+
mvn -B -ntp verify               # unitários + integração; também exige Docker ativo
```

`mvn test` sozinho **não valida a integração**. A validação completa exige `mvn verify`
e os testes SQL abaixo. O Surefire executa `*Test`; o Failsafe executa `*IT` e faz o
build falhar se houver erro. Os relatórios ficam em `backend/target/surefire-reports/`
e `backend/target/failsafe-reports/`.

No GitHub, o workflow **CI** executa os quatro jobs em cada push na `main` e em cada
pull request. Também é possível iniciar em **Actions → CI → Run workflow** e escolher a
branch. O resultado só é aprovado quando **Build + testes (Java 21)**, **Migrações + testes
SQL (PostgreSQL 16)**, **Interface — testes de unidade (Node)** e **Interface — E2E
(Playwright + aplicação + PostgreSQL 16 reais)** estiverem verdes. Os artefatos `testes-java`,
`testes-sql` e `e2e-interface` guardam os relatórios por 14 dias, inclusive em caso de falha
se os arquivos tiverem sido produzidos.

Interface — núcleo (sem dependências, Node 22):

```bash
cd backend && node --test 'src/test/js/*.test.mjs'
```

Interface — **E2E** com a aplicação e o PostgreSQL **reais** (dados fictícios; banco descartável):

```bash
cd backend && mvn -B -ntp -DskipTests package && cd ..
pip install argon2-cffi==25.1.0
PGHOST=localhost PGUSER=postgres PGPASSWORD=... e2e/preparar-ambiente.sh   # bootstrap, migração, seed, app
cd e2e && npm ci && npx playwright install chromium && npx playwright test
kill "$(cat aplicacao/aplicacao.pid)"
```

Os cenários (`e2e/testes`) assumem um banco recém-preparado. No CI, os jobs **Interface — testes
de unidade** e **Interface — E2E** rodam junto com os de Java e SQL; o artefato `e2e-interface`
traz o relatório do Playwright, as capturas de tela e o log da aplicação.

Testes do banco sem Java (PostgreSQL 16 é a versão validada pelo CI, com cliente
`psql` e superusuário em uma instância exclusiva para testes):

```bash
PGHOST=localhost PGUSER=postgres backend/src/test/sql/run-db-tests.sh
```

Execute esse último comando na raiz do repositório. O script cria e remove um banco
descartável e cria os papéis `fluxo_owner` e `fluxo_app` se ainda não existirem;
por isso, use uma instância de testes, com autenticação configurada para esses papéis.

## Estrutura

```
backend/
  src/main/java/br/fluxosaude/
    compartilhado/        utilitários puros (UuidV7, validação de texto, erro de regra)
    episodio/dominio/     agregados e regras — SEM dependência de framework
    episodio/aplicacao/   casos de uso e portas (puros)
    episodio/infra/       adaptadores JDBC (escrita e consultas da Torre)
    episodio/web/         API REST
    identidade/           login, sessão, permissões, gestão de usuários (mesma divisão)
    alerta/               regras de alerta, "Pacientes travados", ciência (mesma divisão)
    configuracao/         Spring (relógio, segurança, montagem dos módulos)
  src/main/resources/db/migration/   V1..V16 (Flyway)
  src/main/resources/static/         interface web (index.html, app/css, app/js/nucleo, app/js/telas)
  src/test/java/          testes de domínio, arquitetura e integração
  src/test/sql/           testes das garantias do banco
  src/test/js/            testes do núcleo da interface (node --test)
e2e/                      testes de ponta a ponta (Playwright) + preparo do ambiente real com dados fictícios
infra/db/init/            bootstrap de papéis/banco (dev, testes e referência p/ DBA)
docs/                     ADRs, rastreabilidade, decisões a validar
```

## Próximas etapas (proposta)

1. ~~Autenticação, sessão e autorização~~ (etapa 2, concluída).
2. ~~Casos de uso e API REST: episódio, etapas, pendências, linha do tempo~~ (etapa 3, concluída).
3. ~~Gestão de usuários e lotações pelo administrador~~ (etapa 4, concluída).
4. ~~Alertas/SLA e "Pacientes travados" (M04, RF-018, RN-006)~~ (etapa 5, concluída; escalonamento aguarda V-05/V-06).
5. ~~Torre de Controle e telas operacionais~~ (etapa 6, concluída).
6. ~~Passagem de plantão (M06) e indicadores (M07)~~ (etapa 7, em revisão; fórmulas oficiais aguardam V-09).
7. A definir com a instituição: transporte (RF-024), exportação (RF-025), importação (RF-026), escalonamento (RF-023).
