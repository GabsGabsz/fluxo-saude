# Fluxo Saúde — Torre de Controle Assistencial

Plataforma de gestão operacional do fluxo assistencial: identifica pacientes parados, mede há
quanto tempo aguardam, registra o gargalo atual, define a próxima ação e o responsável.
**Não é prontuário e não substitui a regulação oficial.** Especificação: **ERS v1.1** (revisão técnica).

## Estado atual — etapa 2 (identidade e sessão) sobre a fundação

| Camada | Conteúdo | Verificação |
|---|---|---|
| Banco (PostgreSQL 16) | Esquema do núcleo, regras críticas em `CHECK`/triggers, RLS por unidade (inclusive usuários e auditoria), auditoria imutável com cadeia SHA-256, login por funções controladas | `backend/src/test/sql` — 6 suítes + 2 testes de concorrência |
| Domínio (Java 21, sem framework) | `Episodio`, `Pendencia`, `FluxoConfigurado`, ajuste manual de horário, pseudônimo, UUIDv7 | 45 testes JUnit, incl. o cenário completo da ERS §11 |
| Identidade (núcleo puro) | Política de senha, limitadores, matriz de permissões, serviço de autenticação | `ServicoAutenticacaoTest`, `MatrizPermissoesTest`, ... |
| Aplicação (Spring Boot 4.1) | Login/sessão no servidor, CSRF SPA, revalidação no banco por transação, erros padronizados; migração em job separado | `SessaoIT`, `BancoDeDadosIT` (Testcontainers) |

Mapa requisito → código → teste: [`docs/rastreabilidade.md`](docs/rastreabilidade.md).
Escolhas que precisam de validação com a equipe: [`docs/decisoes-a-validar.md`](docs/decisoes-a-validar.md).

## Decisões de arquitetura

- [ADR-0001](docs/adr/0001-arquitetura.md) — monólito modular, domínio independente, defesa em profundidade no banco
- [ADR-0002](docs/adr/0002-autenticacao-e-sessao.md) — sessão no servidor, Argon2id, CSRF, bloqueio progressivo, autorização em duas camadas
- [ADR-0003](docs/adr/0003-auditoria-imutavel.md) — auditoria por trigger, imutável, encadeada e redigida
- [ADR-0004](docs/adr/0004-isolamento-por-unidade.md) — RLS por unidade e mínimo privilégio
- [ADR-0005](docs/adr/0005-tempo-concorrencia-duplicidade.md) — horário do servidor e ajuste manual, controle otimista, duplicidade sem bloqueio (ERS v1.1)

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

Testes do banco sem Java (qualquer PostgreSQL ≥ 14, com superusuário):

```bash
PGHOST=localhost PGUSER=postgres backend/src/test/sql/run-db-tests.sh
```

## Estrutura

```
backend/
  src/main/java/br/fluxosaude/
    compartilhado/        utilitários puros (UuidV7, validação de texto, erro de regra)
    episodio/dominio/     agregados e regras — SEM dependência de framework
    configuracao/         Spring (relógio, segurança)
  src/main/resources/db/migration/   V1..V6 (Flyway)
  src/test/java/          testes de domínio, arquitetura e integração
  src/test/sql/           testes das garantias do banco
infra/db/init/            bootstrap de papéis/banco (dev, testes e referência p/ DBA)
docs/                     ADRs, rastreabilidade, decisões a validar
```

## Próximas etapas (proposta)

1. ~~Autenticação, sessão e autorização~~ (etapa 2, concluída).
2. Casos de uso e API REST: episódio, etapas, pendências, linha do tempo (com as permissões).
3. Gestão de usuários e lotações pelo administrador (encerrando sessões ao revogar).
4. Alertas/SLA e "Pacientes travados" (M04, RF-018, RN-006).
5. Torre de Controle e telas (front-end).
6. Passagem de plantão (M06) e indicadores (M07).
