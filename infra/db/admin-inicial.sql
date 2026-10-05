-- =============================================================================
-- Criação da PRIMEIRA unidade e do primeiro administrador (operação de implantação).
-- Executar UMA vez, como fluxo_owner, após as migrações:
--
--   psql -U fluxo_owner -d fluxo -v ON_ERROR_STOP=1 \
--        -v unidade_codigo=UPA_BJ -v unidade_nome='UPA Bom Jesus' -v unidade_tipo=UPA \
--        -v internacao_encerra=true \
--        -v admin_login=admin.upa -v admin_nome='Nome do Administrador' \
--        -v admin_hash='{argon2@SpringSecurity_v5_8}$argon2id$...' \
--        -f infra/db/admin-inicial.sql
--
-- O hash é gerado SEM expor a senha em linha de comando/histórico:
--   cd backend && mvn -q spring-boot:run -Dspring-boot.run.main-class=br.fluxosaude.identidade.infra.GerarHashSenha
-- O administrador é obrigado a trocar a senha no primeiro acesso (deve_trocar_senha).
-- =============================================================================
BEGIN;

INSERT INTO fluxo.unidade (codigo, nome, tipo)
VALUES (:'unidade_codigo', :'unidade_nome', :'unidade_tipo'::fluxo.tipo_unidade);

SELECT fluxo.provisionar_unidade(id, :'internacao_encerra'::boolean)
  FROM fluxo.unidade WHERE codigo = :'unidade_codigo';

INSERT INTO fluxo.usuario (login, nome, senha_hash, deve_trocar_senha)
VALUES (lower(:'admin_login'), :'admin_nome', :'admin_hash', true);

INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel)
SELECT u.id, un.id, 'ADMINISTRADOR'
  FROM fluxo.usuario u, fluxo.unidade un
 WHERE u.login = lower(:'admin_login') AND un.codigo = :'unidade_codigo';

COMMIT;
