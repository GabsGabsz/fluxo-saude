-- =============================================================================
-- V7 — Sessões HTTP no servidor (Spring Session JDBC) — ADR-0002
-- Esquema oficial do Spring Session 4.1 (schema-postgresql.sql), em esquema próprio.
-- A sessão guarda apenas o principal autenticado (IDs, login, nome do profissional e
-- lotações) — nenhum dado de paciente. PRINCIPAL_NAME é o UUID do usuário.
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS sessao;
REVOKE ALL ON SCHEMA sessao FROM PUBLIC;
GRANT USAGE ON SCHEMA sessao TO ${app_role};

CREATE TABLE sessao.SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON sessao.SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON sessao.SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON sessao.SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE sessao.SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES sessao.SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

REVOKE ALL ON ALL TABLES IN SCHEMA sessao FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON sessao.SPRING_SESSION, sessao.SPRING_SESSION_ATTRIBUTES TO ${app_role};

-- Revogação de sessões quando um usuário é desativado ou tem lotações removidas é feita
-- pela aplicação (FindByIndexNameSessionRepository); o principal é revalidado a cada login.
