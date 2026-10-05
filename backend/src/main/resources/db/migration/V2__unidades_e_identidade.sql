-- =============================================================================
-- V2 — Unidades, setores, especialidades, usuários e lotações (ERS §3, M08)
-- =============================================================================

CREATE TYPE fluxo.tipo_unidade AS ENUM ('UPA', 'HOSPITAL', 'OUTRO');

-- Perfis da ERS §3. Um usuário pode ter perfis diferentes em unidades diferentes.
CREATE TYPE fluxo.papel AS ENUM (
    'ADMINISTRADOR',
    'COORDENACAO_FLUXO',   -- coordenação de fluxo / regulação interna / NIR, conforme a unidade (RN-015)
    'ENFERMAGEM',
    'MEDICO',
    'TRANSPORTE',
    'DIRECAO',
    'AUDITORIA'
);

CREATE TABLE fluxo.unidade (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo        text NOT NULL UNIQUE CHECK (codigo ~ '^[A-Z0-9_]{2,32}$'),
    nome          text NOT NULL CHECK (length(btrim(nome)) BETWEEN 2 AND 200),
    tipo          fluxo.tipo_unidade NOT NULL,
    -- Piauí = America/Fortaleza (UTC-3, sem horário de verão). Usado só para
    -- apresentação/relatórios; todo instante é armazenado em timestamptz (UTC).
    fuso_horario  text NOT NULL DEFAULT 'America/Fortaleza',
    -- RNF-017 / RF-040: parâmetros de horário informado manualmente (por unidade).
    -- Fatos informados mais de "limiar_ajuste_manual" antes do relógio do servidor são
    -- AJUSTES MANUAIS: exigem justificativa e ficam marcados na linha do tempo.
    -- "retroatividade_maxima" limita o ajuste (ex.: ampliar temporariamente em
    -- contingência — RNF-018). Valores iniciais são técnicos e devem ser validados (V-10).
    retroatividade_maxima interval NOT NULL DEFAULT interval '24 hours'
        CHECK (retroatividade_maxima BETWEEN interval '0' AND interval '7 days'),
    limiar_ajuste_manual  interval NOT NULL DEFAULT interval '5 minutes'
        CHECK (limiar_ajuste_manual BETWEEN interval '1 minute' AND interval '1 hour'),
    ativa         boolean NOT NULL DEFAULT true,
    criado_em     timestamptz NOT NULL DEFAULT clock_timestamp(),
    atualizado_em timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao        integer NOT NULL DEFAULT 0
);

CREATE TABLE fluxo.setor (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    unidade_id    uuid NOT NULL REFERENCES fluxo.unidade (id),
    codigo        text NOT NULL CHECK (codigo ~ '^[A-Z0-9_]{2,32}$'),
    nome          text NOT NULL CHECK (length(btrim(nome)) BETWEEN 2 AND 120),
    ativo         boolean NOT NULL DEFAULT true,
    criado_em     timestamptz NOT NULL DEFAULT clock_timestamp(),
    atualizado_em timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao        integer NOT NULL DEFAULT 0,
    UNIQUE (unidade_id, codigo),
    UNIQUE (unidade_id, id)          -- alvo de FKs compostas (integridade entre unidades)
);

-- Catálogo global: destino/serviço requerido e filtro por especialidade (RF-012).
CREATE TABLE fluxo.especialidade (
    id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo text NOT NULL UNIQUE CHECK (codigo ~ '^[A-Z0-9_]{2,40}$'),
    nome   text NOT NULL CHECK (length(btrim(nome)) BETWEEN 2 AND 120),
    ativa  boolean NOT NULL DEFAULT true
);

-- -----------------------------------------------------------------------------
-- Usuários (RF-001, RNF-001). Credencial individual, nunca compartilhada.
-- senha_hash guarda o formato do DelegatingPasswordEncoder ("{argon2@...}...").
-- -----------------------------------------------------------------------------
CREATE TABLE fluxo.usuario (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    login                public.citext NOT NULL UNIQUE CHECK (login ~ '^[a-z0-9._-]{3,64}$'),
    nome                 text NOT NULL CHECK (length(btrim(nome)) BETWEEN 2 AND 200),
    email                public.citext UNIQUE CHECK (email IS NULL OR email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    -- Identificação profissional opcional (ex.: COREN/CRM) — útil para auditoria.
    registro_profissional text CHECK (registro_profissional IS NULL OR length(registro_profissional) <= 40),
    senha_hash           text NOT NULL CHECK (senha_hash ~ '^\{[A-Za-z0-9@._-]+\}.{20,}$'),
    ativo                boolean NOT NULL DEFAULT true,
    deve_trocar_senha    boolean NOT NULL DEFAULT true,
    senha_alterada_em    timestamptz NOT NULL DEFAULT clock_timestamp(),
    criado_em            timestamptz NOT NULL DEFAULT clock_timestamp(),
    atualizado_em        timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao               integer NOT NULL DEFAULT 0
);

-- Estado de acesso (alta rotatividade: cada tentativa de login escreve aqui).
-- Separado de "usuario" para que o perfil seja auditado por trigger e as
-- tentativas de login sejam auditadas por eventos explícitos (LOGIN_SUCESSO,
-- LOGIN_FALHA, CONTA_BLOQUEADA) — que ocorrem antes de existir usuário autenticado.
CREATE TABLE fluxo.usuario_acesso (
    usuario_id           uuid PRIMARY KEY REFERENCES fluxo.usuario (id),
    falhas_consecutivas  integer NOT NULL DEFAULT 0 CHECK (falhas_consecutivas >= 0),
    bloqueado_ate        timestamptz,
    ultimo_login_em      timestamptz,
    ultima_falha_em      timestamptz
);

-- Lotação = (usuário, unidade, papel). Mínimo privilégio: sem lotação, sem acesso.
CREATE TABLE fluxo.lotacao (
    usuario_id    uuid NOT NULL REFERENCES fluxo.usuario (id),
    unidade_id    uuid NOT NULL REFERENCES fluxo.unidade (id),
    papel         fluxo.papel NOT NULL,
    concedido_em  timestamptz NOT NULL DEFAULT clock_timestamp(),
    concedido_por uuid REFERENCES fluxo.usuario (id),
    PRIMARY KEY (usuario_id, unidade_id, papel)
);
CREATE INDEX lotacao_unidade_idx ON fluxo.lotacao (unidade_id, papel);

CREATE TRIGGER unidade_atualizado_em BEFORE UPDATE ON fluxo.unidade
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();
CREATE TRIGGER setor_atualizado_em BEFORE UPDATE ON fluxo.setor
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();
CREATE TRIGGER usuario_atualizado_em BEFORE UPDATE ON fluxo.usuario
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();

CREATE TRIGGER unidade_versao BEFORE UPDATE ON fluxo.unidade
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
CREATE TRIGGER setor_versao BEFORE UPDATE ON fluxo.setor
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
CREATE TRIGGER usuario_versao BEFORE UPDATE ON fluxo.usuario
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
