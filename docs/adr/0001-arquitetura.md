# ADR-0001 — Arquitetura: monólito modular, domínio independente, PostgreSQL

- **Status:** aceito
- **Data:** 2026-10-05
- **Requisitos:** RNF-004, RNF-005, RNF-008, RNF-011, RN-011, RN-012, CA-12

## Contexto

O MVP atende uma unidade (Bom Jesus – PI), com dezenas de usuários simultâneos, dados de
saúde (LGPD) e equipe pequena. A ERS exige que o núcleo funcione **sem nenhuma integração**
(CA-12) e que conectores futuros sejam desacoplados (RNF-008), e que o produto escale para
multiunidade sem reescrever o núcleo (RNF-011).

## Decisão

1. **Monólito modular** em Java 21 + Spring Boot 4.1, um único deployable.
   Microserviços foram descartados: multiplicariam superfície de ataque, operação e
   pontos de falha sem nenhum ganho na escala do MVP.
2. **Domínio puro** (`br.fluxosaude.*.dominio`) sem dependência de Spring, JDBC ou JSON.
   Regras (RN-001…RN-012) vivem em agregados testáveis isoladamente (`Episodio`,
   `Pendencia`, `FluxoConfigurado`). Um teste de arquitetura (`DominioIndependenteTest`)
   quebra o build se o domínio importar framework.
3. **Defesa em profundidade no banco:** as regras críticas são repetidas como `CHECK`,
   índices únicos e triggers no PostgreSQL. Se a aplicação tiver um bug, o banco recusa o
   dado inválido. Testes SQL próprios (`backend/src/test/sql`) cobrem essa camada.
4. **PostgreSQL 16** como único armazenamento: transações ACID, RLS, triggers, JSONB e
   `pg_trgm` cobrem tudo o que o MVP precisa, sem Redis/filas/brokers.
5. **JDBC explícito** (Spring JDBC) em vez de JPA: SQL visível e revisável, sem carregamento
   preguiçoso acidental de dado sensível e sem conflito com triggers/RLS.
6. **Integrações (M09) como adaptadores** em módulo próprio, que chamam os mesmos casos de
   uso da interface web. Nunca scraping ou credencial compartilhada (ERS §15).
7. **Tempo:** todo instante é `timestamptz`/`Instant` em UTC; `Clock` injetado. O fuso da
   unidade (`America/Fortaleza`) só é usado na apresentação.
8. **IDs:** UUIDv7 gerados na aplicação (ordenáveis no tempo, sem expor contagem de registros).

## Consequências

- (+) Uma regra quebrada aparece em dois lugares de teste (domínio e banco).
- (+) Escalar para multiunidade é dado + RLS, não reescrita (ver ADR-0004).
- (−) Regras duplicadas Java/SQL exigem disciplina: toda mudança de regra altera os dois e
  seus testes. Mitigação: códigos de erro estáveis e testes espelhados.
- (−) Escala horizontal da aplicação é possível (sem estado em memória), mas a cadeia de
  auditoria serializa escritas (ADR-0003) — aceitável para o porte previsto.
