# Fluxo Saúde — Torre de Controle Assistencial

Plataforma de gestão operacional do fluxo assistencial: identifica pacientes parados, mede há
quanto tempo aguardam, registra o gargalo atual, define a próxima ação e o responsável.
**Não é prontuário e não substitui a regulação oficial.** Especificação: **ERS v1.1** (revisão técnica).

## Estado atual — etapa 4 (gestão de usuários e lotações) — em revisão no PR

| Camada | Conteúdo | Verificação |
|---|---|---|
| Banco (PostgreSQL 16) | Esquema do núcleo, regras críticas em `CHECK`/triggers, RLS por unidade (inclusive usuários e auditoria), auditoria imutável com cadeia SHA-256, login por funções controladas, observações (V9), margem de relógio (V10), gestão de usuários só por funções com alcance conferido (V11) | `backend/src/test/sql` — 10 suítes + 3 testes de concorrência |
| Domínio (Java 21, sem framework) | `Episodio`, `Pendencia`, `FluxoConfigurado`, ajuste manual de horário, pseudônimo, UUIDv7 | 45 testes JUnit, incl. o cenário completo da ERS §11 |
| Identidade (núcleo puro) | Política de senha, limitadores, matriz de permissões, serviço de autenticação | `ServicoAutenticacaoTest`, `MatrizPermissoesTest`, ... |
| Casos de uso (núcleo puro) | `ServicoEpisodios`, `ServicoPendencias`, `ServicoConsultas`: permissão na unidade ativa, versão lida (409), ajuste manual de horário, painel pseudonimizado | `ServicosDeAplicacaoTest` (portas em memória) |
| Gestão de usuários (núcleo puro) | `ServicoGestaoUsuarios`: alcance de lotação × alcance de conta, sem autoalteração, senha provisória, encerramento de sessões | `ServicoGestaoUsuariosTest` |
| Aplicação (Spring Boot 4.1) | Login/sessão no servidor, CSRF SPA, revalidação no banco por transação, API REST de episódios/pendências/Torre e administração de usuários, erros padronizados; migração em job separado | `SessaoIT`, `EpisodiosIT` (cenário ERS §11 via HTTP), `GestaoUsuariosIT`, `BancoDeDadosIT` (Testcontainers) |

Mapa requisito → código → teste: [`docs/rastreabilidade.md`](docs/rastreabilidade.md).
Escolhas que precisam de validação com a equipe: [`docs/decisoes-a-validar.md`](docs/decisoes-a-validar.md).

## Decisões de arquitetura

- [ADR-0001](docs/adr/0001-arquitetura.md) — monólito modular, domínio independente, defesa em profundidade no banco
- [ADR-0002](docs/adr/0002-autenticacao-e-sessao.md) — sessão no servidor, Argon2id, CSRF, bloqueio progressivo, autorização em duas camadas
- [ADR-0003](docs/adr/0003-auditoria-imutavel.md) — auditoria por trigger, imutável, encadeada e redigida
- [ADR-0004](docs/adr/0004-isolamento-por-unidade.md) — RLS por unidade e mínimo privilégio
- [ADR-0005](docs/adr/0005-tempo-concorrencia-duplicidade.md) — horário do servidor e ajuste manual, controle otimista, duplicidade sem bloqueio (ERS v1.1)
- [ADR-0006](docs/adr/0006-gestao-de-usuarios.md) — gestão de usuários: alcance por lotação × conta, autoalteração, último administrador, contas órfãs

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

API de sessão (JSON; CSRF via cookie `XSRF-TOKEN` → cabeçalho `X-XSRF-TOKEN`):

| Método | Caminho | Uso |
|---|---|---|
| GET | `/api/sessao/csrf` | obtém o cookie CSRF (público) |
| POST | `/api/sessao` | login `{login, senha}` (público) |
| GET | `/api/sessao` | sessão atual, lotações e permissões |
| PUT | `/api/sessao/unidade` | troca a unidade ativa |
| PUT | `/api/sessao/senha` | troca a senha (obrigatória no 1º acesso) |
| DELETE | `/api/sessao` | logout |

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

No GitHub, o workflow **CI** executa ambos os jobs em cada push na `main` e em cada
pull request. Depois que esta configuração estiver na `main`, também é possível
iniciar em **Actions → CI → Run workflow** e escolher a branch. O resultado só é
aprovado quando **Build + testes (Java 21)** e **Migrações + testes SQL (PostgreSQL 16)**
estiverem verdes. Os artefatos `testes-java` e `testes-sql` guardam os relatórios por
14 dias, inclusive em caso de falha se os arquivos tiverem sido produzidos.

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
    identidade/           login, sessão, permissões (mesma divisão)
    configuracao/         Spring (relógio, segurança, montagem dos módulos)
  src/main/resources/db/migration/   V1..V11 (Flyway)
  src/test/java/          testes de domínio, arquitetura e integração
  src/test/sql/           testes das garantias do banco
infra/db/init/            bootstrap de papéis/banco (dev, testes e referência p/ DBA)
docs/                     ADRs, rastreabilidade, decisões a validar
```

## Próximas etapas (proposta)

1. ~~Autenticação, sessão e autorização~~ (etapa 2, concluída).
2. ~~Casos de uso e API REST: episódio, etapas, pendências, linha do tempo~~ (etapa 3, concluída).
3. ~~Gestão de usuários e lotações pelo administrador~~ (etapa 4, em revisão).
4. Alertas/SLA e "Pacientes travados" (M04, RF-018, RN-006).
5. Torre de Controle e telas (front-end).
6. Passagem de plantão (M06) e indicadores (M07).
