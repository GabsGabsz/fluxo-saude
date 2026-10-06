-- =============================================================================
-- V15 — Unidades do usuário para a interface (troca de unidade ativa; etapa 6)
-- =============================================================================
-- O RLS mostra só a unidade ATIVA (mínimo privilégio); para trocar de unidade a interface
-- precisa do nome e do fuso das OUTRAS unidades em que o próprio usuário está lotado.
-- SECURITY DEFINER restrita ao usuário do contexto: nada sobre unidades alheias.

CREATE FUNCTION fluxo.unidades_do_usuario()
    RETURNS TABLE (id uuid, codigo text, nome text, fuso_horario text)
    LANGUAGE plpgsql STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF fluxo.ctx_usuario() IS NULL THEN
        RAISE EXCEPTION 'contexto de usuário ausente' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
    SELECT DISTINCT un.id, un.codigo, un.nome, un.fuso_horario
      FROM fluxo.lotacao l
      JOIN fluxo.unidade un ON un.id = l.unidade_id AND un.ativa
     WHERE l.usuario_id = fluxo.ctx_usuario();
END $$;

REVOKE ALL ON FUNCTION fluxo.unidades_do_usuario() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fluxo.unidades_do_usuario() TO ${app_role};
