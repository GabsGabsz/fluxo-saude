-- =============================================================================
-- V16 — Passagem de plantão (M06: RF-016, RF-017, CA-07) e indicadores (M07: RF-019,
-- RF-020, RF-039 parcial, CA-09) — ver docs/adr/0009-plantao-e-indicadores.md
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Papel na unidade (defesa em profundidade, espelha a MatrizPermissoes): o usuário do
-- contexto, ATIVO, lotado na unidade com algum dos papéis informados.
-- -----------------------------------------------------------------------------
CREATE FUNCTION fluxo.ctx_tem_papel(p_unidade_id uuid, p_papeis fluxo.papel[]) RETURNS boolean
    LANGUAGE sql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_unidade_id IS NOT NULL
       AND fluxo.ctx_usuario() IS NOT NULL
       AND p_unidade_id = ANY (fluxo.ctx_unidades())
       AND EXISTS (SELECT 1
                     FROM fluxo.lotacao l
                     JOIN fluxo.usuario u ON u.id = l.usuario_id AND u.ativo
                    WHERE l.usuario_id = fluxo.ctx_usuario()
                      AND l.unidade_id = p_unidade_id
                      AND l.papel = ANY (p_papeis))
$$;
REVOKE ALL ON FUNCTION fluxo.ctx_tem_papel(uuid, fluxo.papel[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.ctx_tem_papel(uuid, fluxo.papel[]) TO ${app_role};

-- Papéis com PLANTAO_GERENCIAR (MatrizPermissoes): coordenação, enfermagem e médico.
CREATE FUNCTION fluxo.ctx_gerencia_plantao(p_unidade_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$ SELECT fluxo.ctx_tem_papel(p_unidade_id, ARRAY['COORDENACAO_FLUXO', 'ENFERMAGEM', 'MEDICO']::fluxo.papel[]) $$;
REVOKE ALL ON FUNCTION fluxo.ctx_gerencia_plantao(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.ctx_gerencia_plantao(uuid) TO ${app_role};

-- -----------------------------------------------------------------------------
-- Passagem de plantão. Quem ENTREGA gera o conteúdo a partir do estado atual da unidade e
-- confirma pela assinatura (SHA-256) do conteúdo que viu; OUTRO profissional RECEBE,
-- confirmando o conteúdo entregue e as diferenças desde a entrega (assinatura própria).
-- A passagem é só um registro: não resolve pendência, não registra ciência, não muda
-- responsável nem encerra episódio. Sem horários fixos de turno: o período representado vai
-- da entrega da última passagem RECEBIDA até esta entrega.
-- -----------------------------------------------------------------------------
CREATE TYPE fluxo.status_passagem AS ENUM ('ENTREGUE', 'RECEBIDA', 'CANCELADA');

CREATE TABLE fluxo.passagem_plantao (
    id                      uuid PRIMARY KEY,
    unidade_id              uuid NOT NULL REFERENCES fluxo.unidade (id),
    status                  fluxo.status_passagem NOT NULL DEFAULT 'ENTREGUE',
    periodo_inicio          timestamptz,               -- definido pelo banco (gatilho)
    entregue_por            uuid NOT NULL DEFAULT fluxo.ctx_usuario() REFERENCES fluxo.usuario (id),
    entregue_em             timestamptz NOT NULL DEFAULT clock_timestamp(),
    assinatura              text NOT NULL CHECK (assinatura ~ '^[0-9a-f]{64}$'),
    total_casos             integer NOT NULL CHECK (total_casos >= 0),
    total_criticos          integer NOT NULL CHECK (total_criticos >= 0),
    total_transferencias    integer NOT NULL CHECK (total_transferencias >= 0),
    total_pendencias        integer NOT NULL CHECK (total_pendencias >= 0),
    total_vencidas          integer NOT NULL CHECK (total_vencidas >= 0),
    observacao              text CHECK (observacao IS NULL OR length(btrim(observacao)) BETWEEN 3 AND 500),
    recebida_por            uuid REFERENCES fluxo.usuario (id),
    recebida_em             timestamptz,
    assinatura_recebimento  text CHECK (assinatura_recebimento IS NULL OR assinatura_recebimento ~ '^[0-9a-f]{64}$'),
    -- Só contagens das diferenças vistas no recebimento (sem nomes nem ids).
    diferencas_recebimento  jsonb CHECK (diferencas_recebimento IS NULL OR jsonb_typeof(diferencas_recebimento) = 'object'),
    cancelada_por           uuid REFERENCES fluxo.usuario (id),
    cancelada_em            timestamptz,
    justificativa_cancelamento text CHECK (justificativa_cancelamento IS NULL
                                           OR length(btrim(justificativa_cancelamento)) BETWEEN 3 AND 500),
    atualizado_em           timestamptz NOT NULL DEFAULT clock_timestamp(),
    versao                  integer NOT NULL DEFAULT 0,
    UNIQUE (unidade_id, id),
    CHECK (total_criticos <= total_casos AND total_transferencias <= total_casos AND total_vencidas <= total_pendencias),
    CHECK (periodo_inicio IS NULL OR periodo_inicio <= entregue_em),
    CONSTRAINT passagem_situacao_coerente CHECK (
        (status = 'ENTREGUE'  AND recebida_por IS NULL AND recebida_em IS NULL AND assinatura_recebimento IS NULL
                              AND diferencas_recebimento IS NULL
                              AND cancelada_por IS NULL AND cancelada_em IS NULL AND justificativa_cancelamento IS NULL)
     OR (status = 'RECEBIDA'  AND recebida_por IS NOT NULL AND recebida_em IS NOT NULL
                              AND assinatura_recebimento IS NOT NULL AND diferencas_recebimento IS NOT NULL
                              AND cancelada_por IS NULL AND cancelada_em IS NULL AND justificativa_cancelamento IS NULL)
     OR (status = 'CANCELADA' AND cancelada_por IS NOT NULL AND cancelada_em IS NOT NULL
                              AND justificativa_cancelamento IS NOT NULL
                              AND recebida_por IS NULL AND recebida_em IS NULL AND assinatura_recebimento IS NULL)),
    -- RF-017: quem recebe não é quem entregou; só quem entregou cancela (proposta, V-01/V-06).
    CONSTRAINT passagem_recebedor_distinto CHECK (recebida_por IS NULL OR recebida_por <> entregue_por),
    CONSTRAINT passagem_cancelamento_pelo_autor CHECK (cancelada_por IS NULL OR cancelada_por = entregue_por),
    CHECK (recebida_em IS NULL OR recebida_em >= entregue_em),
    CHECK (cancelada_em IS NULL OR cancelada_em >= entregue_em)
);
-- No máximo UMA passagem aguardando recebimento por unidade (entregas concorrentes: a segunda falha).
CREATE UNIQUE INDEX passagem_pendente_uq ON fluxo.passagem_plantao (unidade_id) WHERE status = 'ENTREGUE';
CREATE INDEX passagem_historico_idx ON fluxo.passagem_plantao (unidade_id, entregue_em DESC, id);

-- Inserção: sempre ENTREGUE, autoria e instante do banco, período calculado pelo banco.
CREATE FUNCTION fluxo.tg_passagem_entrega() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.status <> 'ENTREGUE' THEN
        RAISE EXCEPTION 'passagem nasce entregue' USING ERRCODE = '23514';
    END IF;
    NEW.versao := 0;
    NEW.periodo_inicio := (SELECT max(p.entregue_em) FROM fluxo.passagem_plantao p
                            WHERE p.unidade_id = NEW.unidade_id AND p.status = 'RECEBIDA');
    RETURN NEW;
END $$;

-- Alteração: só ENTREGUE -> RECEBIDA (por outro usuário) ou ENTREGUE -> CANCELADA (pelo autor).
-- Autor e instante vêm do contexto do banco; o conteúdo entregue nunca muda.
CREATE FUNCTION fluxo.tg_passagem_transicao() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF OLD.status <> 'ENTREGUE' THEN
        RAISE EXCEPTION 'passagem já % não pode ser alterada', lower(OLD.status::text) USING ERRCODE = '55000';
    END IF;
    IF (NEW.id, NEW.unidade_id, NEW.periodo_inicio, NEW.entregue_por, NEW.entregue_em, NEW.assinatura,
        NEW.total_casos, NEW.total_criticos, NEW.total_transferencias, NEW.total_pendencias, NEW.total_vencidas,
        NEW.observacao)
       IS DISTINCT FROM
       (OLD.id, OLD.unidade_id, OLD.periodo_inicio, OLD.entregue_por, OLD.entregue_em, OLD.assinatura,
        OLD.total_casos, OLD.total_criticos, OLD.total_transferencias, OLD.total_pendencias, OLD.total_vencidas,
        OLD.observacao) THEN
        RAISE EXCEPTION 'conteúdo entregue da passagem é imutável' USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'RECEBIDA' THEN
        NEW.recebida_por := fluxo.exigir_usuario();
        NEW.recebida_em := clock_timestamp();
    ELSIF NEW.status = 'CANCELADA' THEN
        NEW.cancelada_por := fluxo.exigir_usuario();
        NEW.cancelada_em := clock_timestamp();
    ELSE
        RAISE EXCEPTION 'transição de passagem inválida' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER passagem_autoria BEFORE INSERT ON fluxo.passagem_plantao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_autoria('entregue_por', 'entregue_em');
CREATE TRIGGER passagem_entrega BEFORE INSERT ON fluxo.passagem_plantao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_passagem_entrega();
CREATE TRIGGER passagem_transicao BEFORE UPDATE ON fluxo.passagem_plantao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_passagem_transicao();
CREATE TRIGGER passagem_versao BEFORE UPDATE ON fluxo.passagem_plantao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_versao();
CREATE TRIGGER passagem_atualizado_em BEFORE UPDATE ON fluxo.passagem_plantao
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_atualizado_em();
CREATE TRIGGER passagem_sem_exclusao BEFORE DELETE OR TRUNCATE ON fluxo.passagem_plantao
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();
-- Auditoria por linha com os textos livres redigidos (o fato e a assinatura ficam).
CREATE TRIGGER auditoria AFTER INSERT OR UPDATE OR DELETE ON fluxo.passagem_plantao
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_capturar('observacao', 'justificativa_cancelamento');

-- Conteúdo entregue: SÓ identificadores, códigos, instantes e versões (nenhum nome, CNS ou
-- texto livre). Nomes e descrições são lidos do estado atual por quem pode vê-los.
CREATE TABLE fluxo.passagem_conteudo (
    passagem_id  uuid PRIMARY KEY,
    unidade_id   uuid NOT NULL,
    conteudo     jsonb NOT NULL CHECK (jsonb_typeof(conteudo) = 'object' AND jsonb_typeof(conteudo -> 'casos') = 'array'),
    FOREIGN KEY (unidade_id, passagem_id) REFERENCES fluxo.passagem_plantao (unidade_id, id)
);

-- O conteúdo é gravado uma única vez (chave primária), pelo próprio autor, enquanto a
-- passagem está ENTREGUE; depois é imutável.
CREATE FUNCTION fluxo.tg_passagem_conteudo() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM fluxo.passagem_plantao p
                    WHERE p.id = NEW.passagem_id AND p.unidade_id = NEW.unidade_id AND p.status = 'ENTREGUE'
                      AND p.entregue_por = fluxo.exigir_usuario()) THEN
        RAISE EXCEPTION 'conteúdo da passagem só é gravado pelo autor, na entrega' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER passagem_conteudo_entrega BEFORE INSERT ON fluxo.passagem_conteudo
    FOR EACH ROW EXECUTE FUNCTION fluxo.tg_passagem_conteudo();
CREATE TRIGGER passagem_conteudo_imutavel BEFORE UPDATE OR DELETE ON fluxo.passagem_conteudo
    FOR EACH ROW EXECUTE FUNCTION auditoria.tg_imutavel();
CREATE TRIGGER passagem_conteudo_sem_truncate BEFORE TRUNCATE ON fluxo.passagem_conteudo
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.tg_imutavel();

ALTER TABLE fluxo.passagem_plantao ENABLE ROW LEVEL SECURITY;
ALTER TABLE fluxo.passagem_conteudo ENABLE ROW LEVEL SECURITY;
CREATE POLICY unidade_isolamento ON fluxo.passagem_plantao TO ${app_role}
    USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]))
    WITH CHECK (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));
CREATE POLICY unidade_isolamento ON fluxo.passagem_conteudo TO ${app_role}
    USING (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]))
    WITH CHECK (unidade_id = ANY ((SELECT fluxo.ctx_unidades())::uuid[]));
-- Leitura e escrita só para quem gerencia plantão na unidade (PLANTAO_GERENCIAR).
CREATE POLICY passagem_papel ON fluxo.passagem_plantao AS RESTRICTIVE TO ${app_role}
    USING (fluxo.ctx_gerencia_plantao(unidade_id))
    WITH CHECK (fluxo.ctx_gerencia_plantao(unidade_id));
CREATE POLICY passagem_conteudo_papel ON fluxo.passagem_conteudo AS RESTRICTIVE TO ${app_role}
    USING (fluxo.ctx_gerencia_plantao(unidade_id))
    WITH CHECK (fluxo.ctx_gerencia_plantao(unidade_id));

REVOKE ALL ON fluxo.passagem_plantao, fluxo.passagem_conteudo FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON fluxo.passagem_plantao TO ${app_role};   -- sem DELETE
GRANT SELECT, INSERT ON fluxo.passagem_conteudo TO ${app_role};          -- imutável

-- =============================================================================
-- Indicadores (M07). Funções SECURITY INVOKER: o RLS por unidade vale normalmente. Todas
-- devolvem SÓ agregados (contagens, durações), nunca identificação de paciente ou episódio.
-- Fórmulas: PROPOSTAS até a validação institucional (V-09) — dicionário em
-- docs/indicadores.md e em GET /api/indicadores/dicionario.
-- Período: datas LOCAIS da unidade [início, fim], convertidas para o intervalo semiaberto
-- [início 00:00, (fim+1) 00:00) no fuso da unidade (mudança de horário tratada pelo banco).
-- =============================================================================

CREATE FUNCTION fluxo.ind_periodo(p_fuso text, p_inicio date, p_fim date,
                                  OUT inicio timestamptz, OUT fim timestamptz)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$ SELECT (p_inicio::timestamp AT TIME ZONE p_fuso), ((p_fim + 1)::timestamp AT TIME ZONE p_fuso) $$;

-- I-01/I-02: permanência dos episódios ENCERRADOS no período (marco final no período).
-- Exclusão (proposta): desfecho "encerramento administrativo" (cancelamento/registro indevido).
CREATE FUNCTION fluxo.ind_permanencia(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_setor uuid)
    RETURNS TABLE (incluidos bigint, excluidos bigint, media_min double precision, mediana_min double precision,
                   minimo_min double precision, maximo_min double precision)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH base AS (
        SELECT e.desfecho, extract(epoch FROM e.encerrado_em - e.entrada_em) / 60.0 AS minutos
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade
           AND e.encerrado_em >= p_inicio AND e.encerrado_em < p_fim
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    )
    SELECT count(*) FILTER (WHERE desfecho <> 'ENCERRAMENTO_ADMINISTRATIVO'),
           count(*) FILTER (WHERE desfecho = 'ENCERRAMENTO_ADMINISTRATIVO'),
           avg(minutos) FILTER (WHERE desfecho <> 'ENCERRAMENTO_ADMINISTRATIVO'),
           percentile_cont(0.5) WITHIN GROUP (ORDER BY minutos) FILTER (WHERE desfecho <> 'ENCERRAMENTO_ADMINISTRATIVO'),
           min(minutos) FILTER (WHERE desfecho <> 'ENCERRAMENTO_ADMINISTRATIVO'),
           max(minutos) FILTER (WHERE desfecho <> 'ENCERRAMENTO_ADMINISTRATIVO')
      FROM base
$$;

-- I-03: encerrados no período com permanência >= limite de cada regra TEMPO_TOTAL ativa
-- (sem filtro de etapa). Limite VIGENTE na consulta (as versões anteriores não são reaplicadas).
CREATE FUNCTION fluxo.ind_acima_dos_limites(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_setor uuid)
    RETURNS TABLE (regra_id uuid, regra_nome text, regra_versao integer, limite_min bigint,
                   populacao bigint, acima bigint)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    SELECT r.id, r.nome, r.versao, (extract(epoch FROM r.limite) / 60)::bigint,
           count(e.id),
           count(e.id) FILTER (WHERE e.encerrado_em - e.entrada_em >= r.limite)
      FROM fluxo.regra_alerta r
      LEFT JOIN fluxo.episodio e
             ON e.unidade_id = r.unidade_id
            AND e.encerrado_em >= p_inicio AND e.encerrado_em < p_fim
            AND e.desfecho <> 'ENCERRAMENTO_ADMINISTRATIVO'
            AND (p_setor IS NULL OR e.setor_id = p_setor)
     WHERE r.unidade_id = p_unidade AND r.ativa AND r.tipo = 'TEMPO_TOTAL' AND r.etapa_id IS NULL
     GROUP BY r.id, r.nome, r.versao, r.limite
     ORDER BY r.limite, r.nome, r.id
$$;

-- I-04: saídas (encerramentos) no período por desfecho; transferências = TRANSFERENCIA.
CREATE FUNCTION fluxo.ind_desfechos(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_setor uuid)
    RETURNS TABLE (desfecho text, quantidade bigint)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    SELECT e.desfecho::text, count(*)
      FROM fluxo.episodio e
     WHERE e.unidade_id = p_unidade
       AND e.encerrado_em >= p_inicio AND e.encerrado_em < p_fim
       AND (p_setor IS NULL OR e.setor_id = p_setor)
     GROUP BY e.desfecho
     ORDER BY e.desfecho::text
$$;

-- I-05: solicitação -> aceite, reconstruído da linha do tempo (ETAPA_ALTERADA; o estado atual
-- não reconstrói o percurso). Solicitação = etapa que exige protocolo externo; aceite = etapa de
-- natureza ACEITO (configuração da unidade, não códigos fixos). Marco inicial: PRIMEIRA entrada em
-- solicitação; marco final: PRIMEIRA entrada em aceite a partir dela; incluído se o marco final
-- está no período. "sem_marco": episódios com aceite no período sem solicitação registrada antes
-- (dado ausente, não zero). Só os episódios com aceite no período são lidos (índice por unidade,
-- tipo e instante; depois a linha do tempo de cada um pelo índice por episódio).
CREATE FUNCTION fluxo.ind_solicitacao_aceite(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_setor uuid)
    RETURNS TABLE (incluidos bigint, sem_marco bigint, media_min double precision, mediana_min double precision)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH eps AS (
        SELECT DISTINCT ev.episodio_id
          FROM fluxo.evento_episodio ev
          JOIN fluxo.etapa et ON et.unidade_id = ev.unidade_id AND et.codigo = ev.dados ->> 'para'
          JOIN fluxo.episodio e ON e.id = ev.episodio_id
         WHERE ev.unidade_id = p_unidade AND ev.tipo = 'ETAPA_ALTERADA'
           AND ev.ocorrido_em >= p_inicio AND ev.ocorrido_em < p_fim
           AND et.natureza = 'ACEITO'
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    ent AS (
        SELECT ev.episodio_id, ev.ocorrido_em, et.natureza, et.exige_protocolo_externo AS solicitacao
          FROM eps
          JOIN fluxo.evento_episodio ev ON ev.episodio_id = eps.episodio_id AND ev.tipo = 'ETAPA_ALTERADA'
          JOIN fluxo.etapa et ON et.unidade_id = ev.unidade_id AND et.codigo = ev.dados ->> 'para'
    ),
    por_episodio AS (
        SELECT episodio_id,
               min(ocorrido_em) FILTER (WHERE solicitacao) AS sol_em,
               min(ocorrido_em) FILTER (WHERE natureza = 'ACEITO' AND ocorrido_em >= p_inicio AND ocorrido_em < p_fim)
                   AS primeiro_aceite_no_periodo
          FROM ent GROUP BY episodio_id
    ),
    pares AS (
        SELECT p.episodio_id, p.sol_em, p.primeiro_aceite_no_periodo,
               min(a.ocorrido_em) FILTER (WHERE a.natureza = 'ACEITO' AND a.ocorrido_em >= p.sol_em) AS aceite_em
          FROM por_episodio p
          JOIN ent a ON a.episodio_id = p.episodio_id
         GROUP BY p.episodio_id, p.sol_em, p.primeiro_aceite_no_periodo
    ),
    dur AS (SELECT extract(epoch FROM aceite_em - sol_em) / 60.0 AS minutos FROM pares
             WHERE sol_em IS NOT NULL AND aceite_em >= p_inicio AND aceite_em < p_fim)
    SELECT (SELECT count(*) FROM dur),
           (SELECT count(*) FROM pares WHERE sol_em IS NULL OR sol_em > primeiro_aceite_no_periodo),
           (SELECT avg(minutos) FROM dur),
           (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY minutos) FROM dur)
$$;

-- I-06: aceite -> saída dos TRANSFERIDOS no período. Marco inicial: ÚLTIMA entrada em aceite
-- antes do encerramento; marco final: encerramento. "sem_marco": transferidos sem aceite registrado.
CREATE FUNCTION fluxo.ind_aceite_saida(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_setor uuid)
    RETURNS TABLE (incluidos bigint, sem_marco bigint, media_min double precision, mediana_min double precision)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH saidas AS (
        SELECT e.id, e.encerrado_em,
               (SELECT max(ev.ocorrido_em)
                  FROM fluxo.evento_episodio ev
                  JOIN fluxo.etapa et ON et.unidade_id = ev.unidade_id AND et.codigo = ev.dados ->> 'para'
                 WHERE ev.episodio_id = e.id AND ev.tipo = 'ETAPA_ALTERADA' AND et.natureza = 'ACEITO'
                   AND ev.ocorrido_em <= e.encerrado_em) AS aceite_em
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.desfecho = 'TRANSFERENCIA'
           AND e.encerrado_em >= p_inicio AND e.encerrado_em < p_fim
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    )
    SELECT count(*) FILTER (WHERE aceite_em IS NOT NULL), count(*) FILTER (WHERE aceite_em IS NULL),
           avg(extract(epoch FROM encerrado_em - aceite_em) / 60.0) FILTER (WHERE aceite_em IS NOT NULL),
           percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM encerrado_em - aceite_em) / 60.0)
               FILTER (WHERE aceite_em IS NOT NULL)
      FROM saidas
$$;

-- I-07 (RF-020): motivos de bloqueio. Intervalos reconstruídos dos eventos BLOQUEIO_DEFINIDO/
-- REMOVIDO (ordem: ocorrido_em, registrado_em, id); um intervalo termina no próximo evento de
-- bloqueio, no encerramento ou em "agora" (ainda bloqueado). Tempo = sobreposição com
-- [início, min(fim, agora)). "inicios" = intervalos que COMEÇAM no período (troca de detalhe
-- do mesmo motivo não conta como novo início). Episódios = distintos com sobreposição > 0.
CREATE FUNCTION fluxo.ind_motivos(p_unidade uuid, p_inicio timestamptz, p_fim timestamptz, p_setor uuid,
                                  p_agora timestamptz)
    RETURNS TABLE (motivo_id uuid, codigo text, descricao text, categoria text, minutos double precision,
                   inicios bigint, episodios bigint)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH eps AS (
        SELECT e.id, e.encerrado_em
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade
           AND e.entrada_em < p_fim
           AND (e.encerrado_em IS NULL OR e.encerrado_em > p_inicio)
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    ev AS (
        SELECT ev.episodio_id, ev.ocorrido_em, ev.registrado_em, ev.id, ev.tipo, ev.dados ->> 'motivo' AS motivo,
               eps.encerrado_em
          FROM fluxo.evento_episodio ev
          JOIN eps ON eps.id = ev.episodio_id
         WHERE ev.unidade_id = p_unidade AND ev.tipo IN ('BLOQUEIO_DEFINIDO', 'BLOQUEIO_REMOVIDO')
           AND ev.ocorrido_em < p_fim
    ),
    seg AS (
        SELECT episodio_id, tipo, motivo, ocorrido_em AS ini,
               coalesce(lead(ocorrido_em) OVER w, encerrado_em, p_agora) AS fim_seg,
               lag(CASE WHEN tipo = 'BLOQUEIO_DEFINIDO' THEN motivo END) OVER w AS motivo_anterior
          FROM ev
        WINDOW w AS (PARTITION BY episodio_id ORDER BY ocorrido_em, registrado_em, id)
    ),
    sob AS (
        SELECT episodio_id, motivo, ini, motivo_anterior,
               greatest(0, extract(epoch FROM least(fim_seg, p_fim, p_agora) - greatest(ini, p_inicio)) / 60.0) AS minutos
          FROM seg
         WHERE tipo = 'BLOQUEIO_DEFINIDO'
    )
    SELECT m.id, s.motivo, m.descricao, m.categoria::text,
           sum(s.minutos),
           count(*) FILTER (WHERE s.motivo_anterior IS DISTINCT FROM s.motivo AND s.ini >= p_inicio AND s.ini < p_fim),
           count(DISTINCT s.episodio_id) FILTER (WHERE s.minutos > 0)
      FROM sob s
      LEFT JOIN fluxo.motivo_bloqueio m ON m.unidade_id = p_unidade AND m.codigo = s.motivo
     GROUP BY m.id, s.motivo, m.descricao, m.categoria
    HAVING sum(s.minutos) > 0
        OR count(*) FILTER (WHERE s.motivo_anterior IS DISTINCT FROM s.motivo AND s.ini >= p_inicio AND s.ini < p_fim) > 0
     ORDER BY sum(s.minutos) DESC, s.motivo
$$;

-- I-08: volume diário (tendência) — entradas e saídas por dia LOCAL da unidade.
CREATE FUNCTION fluxo.ind_volume_diario(p_unidade uuid, p_fuso text, p_inicio date, p_fim date, p_setor uuid)
    RETURNS TABLE (dia date, entradas bigint, saidas bigint)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH dias AS (
        SELECT d::date AS dia, (d::date::timestamp AT TIME ZONE p_fuso) AS ini,
               ((d::date + 1)::timestamp AT TIME ZONE p_fuso) AS fim
          FROM generate_series(p_inicio::timestamp, p_fim::timestamp, interval '1 day') AS d
    )
    SELECT d.dia,
           (SELECT count(*) FROM fluxo.episodio e WHERE e.unidade_id = p_unidade
               AND e.entrada_em >= d.ini AND e.entrada_em < d.fim AND (p_setor IS NULL OR e.setor_id = p_setor)),
           (SELECT count(*) FROM fluxo.episodio e WHERE e.unidade_id = p_unidade
               AND e.encerrado_em >= d.ini AND e.encerrado_em < d.fim AND (p_setor IS NULL OR e.setor_id = p_setor))
      FROM dias d
     ORDER BY d.dia
$$;

-- Retrato ATUAL (agora): contagens por dimensão, sobre TODOS os episódios abertos da unidade.
CREATE FUNCTION fluxo.ind_retrato(p_unidade uuid, p_setor uuid, p_agora timestamptz)
    RETURNS TABLE (dimensao text, chave uuid, nome text, quantidade bigint)
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    WITH abertos AS (
        SELECT e.id, e.etapa_id, e.motivo_bloqueio_id
          FROM fluxo.episodio e
         WHERE e.unidade_id = p_unidade AND e.encerrado_em IS NULL
           AND (p_setor IS NULL OR e.setor_id = p_setor)
    ),
    vencidas AS (
        SELECT p.episodio_id
          FROM fluxo.pendencia p JOIN abertos a ON a.id = p.episodio_id
         WHERE p.status = 'ABERTA' AND p.prazo < p_agora
    )
    SELECT 'ABERTOS', NULL::uuid, NULL::text, count(*) FROM abertos
    UNION ALL
    SELECT 'ETAPA', et.id, et.nome, count(a.id)
      FROM abertos a JOIN fluxo.etapa et ON et.id = a.etapa_id GROUP BY et.id, et.nome
    UNION ALL
    SELECT 'MOTIVO', m.id, m.descricao, count(a.id)
      FROM abertos a JOIN fluxo.motivo_bloqueio m ON m.id = a.motivo_bloqueio_id GROUP BY m.id, m.descricao
    UNION ALL
    SELECT 'SEM_BLOQUEIO', NULL, NULL, count(*) FROM abertos WHERE motivo_bloqueio_id IS NULL
    UNION ALL
    SELECT 'PENDENCIAS_VENCIDAS', NULL, NULL, count(*) FROM vencidas
    UNION ALL
    SELECT 'EPISODIOS_COM_VENCIDA', NULL, NULL, count(DISTINCT episodio_id) FROM vencidas
$$;

REVOKE ALL ON FUNCTION fluxo.ind_periodo(text, date, date), fluxo.ind_permanencia(uuid, timestamptz, timestamptz, uuid),
    fluxo.ind_acima_dos_limites(uuid, timestamptz, timestamptz, uuid), fluxo.ind_desfechos(uuid, timestamptz, timestamptz, uuid),
    fluxo.ind_solicitacao_aceite(uuid, timestamptz, timestamptz, uuid),
    fluxo.ind_aceite_saida(uuid, timestamptz, timestamptz, uuid),
    fluxo.ind_motivos(uuid, timestamptz, timestamptz, uuid, timestamptz),
    fluxo.ind_volume_diario(uuid, text, date, date, uuid), fluxo.ind_retrato(uuid, uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.ind_periodo(text, date, date), fluxo.ind_permanencia(uuid, timestamptz, timestamptz, uuid),
    fluxo.ind_acima_dos_limites(uuid, timestamptz, timestamptz, uuid), fluxo.ind_desfechos(uuid, timestamptz, timestamptz, uuid),
    fluxo.ind_solicitacao_aceite(uuid, timestamptz, timestamptz, uuid),
    fluxo.ind_aceite_saida(uuid, timestamptz, timestamptz, uuid),
    fluxo.ind_motivos(uuid, timestamptz, timestamptz, uuid, timestamptz),
    fluxo.ind_volume_diario(uuid, text, date, date, uuid), fluxo.ind_retrato(uuid, uuid, timestamptz) TO ${app_role};

-- Índices das consultas históricas (planos conferidos com EXPLAIN em volume sintético; ver ADR-0009).
-- Encerramentos por unidade e período: episodio_encerrados_idx (V5) já existe.
-- Entradas por unidade e período (volume diário, motivos); eventos de etapa/bloqueio por unidade e tipo.
CREATE INDEX episodio_entrada_idx ON fluxo.episodio (unidade_id, entrada_em);
CREATE INDEX evento_episodio_indicadores_idx ON fluxo.evento_episodio (unidade_id, tipo, ocorrido_em)
    WHERE tipo IN ('ETAPA_ALTERADA', 'BLOQUEIO_DEFINIDO', 'BLOQUEIO_REMOVIDO');
