# ADR-0011 — Ambiente de homologação, operação e recuperação

- **Status:** proposto (PR da etapa 9)
- **Data:** 2026-10-07
- **Requisitos da ERS v1.1 relacionados:**
  - RNF-001 (sessão segura, criptografia em trânsito);
  - RNF-004 e RNF-009 (continuidade: backups, recuperação, monitoramento e contingência documentados);
  - RNF-012 (observabilidade);
  - §16 (backups testados e plano de recuperação);
  - §13 (fases protótipo → MVP → piloto).
- **Pendências institucionais:** V-08 e V-10. Ver [`impedimentos-uso-real.md`](../operacao/impedimentos-uso-real.md).

## Contexto

Até a etapa 8:
- o Compose principal subia só o PostgreSQL de desenvolvimento;
- o sistema completo existia apenas no CI (`e2e/preparar-ambiente.sh`).

A preparação do piloto exige instalar, iniciar, verificar, atualizar e recuperar o sistema em
ambiente controlado, com dados fictícios.

Restrições:
- manter Java/Spring, PostgreSQL 16 e a interface;
- sem serviços contratados nem publicação;
- a aplicação só com o papel sujeito a RLS;
- migração em processo separado;
- segredos fora de imagem, Git, logs e artefatos.

## Decisões

1. **Docker Compose** (`deploy/homologacao/compose.yaml`) com quatro serviços:
   - `db`: PostgreSQL 16 com volume. O bootstrap existente (`infra/db/init/01-bootstrap.sh`), agora com senhas também por `*_FILE`, roda só na primeira inicialização.
   - `migracao`: o mesmo jar no perfil `migracao`, único com a senha do dono.
   - `app`: `depends_on: service_completed_successfully` — migração com falha impede a aplicação.
   - `proxy`: único serviço publicado, em `127.0.0.1` por padrão.

   Kubernetes e outros orquestradores foram descartados: seriam infraestrutura nova sem necessidade
   demonstrada para uma instância.
2. **Imagem única** (`backend/Dockerfile`):
   - build Maven multi-estágio, sem rodar testes (os testes ficam no CI);
   - JRE 21, usuário sem privilégios, sistema de arquivos somente leitura, `cap_drop: ALL`;
   - saúde por `/actuator/health` (sem curl na imagem).

   O `entrypoint` lê as senhas de arquivos de segredo. Nada vai para variáveis visíveis no
   `docker inspect` nem para argumentos de linha de comando.
3. **Segredos:**
   - gerados por `fluxo.sh preparar` (aleatórios, 256 bits) em `deploy/homologacao/segredos/<projeto>`;
   - diretório 700, ignorado pelo Git;
   - montados como *secrets*.

   Os arquivos têm permissão 644 porque o usuário do contêiner precisa lê-los pelo *bind mount*;
   a proteção no host é o diretório 700. Em implantação real, a proposta é usar o cofre da instituição.
4. **HTTPS e proxy confiável:**
   - Caddy com CA interna (só homologação), descartando `X-Forwarded-*` do cliente.
   - A aplicação ativa `forward-headers-strategy=native` **somente** com `FLUXO_PROXY_CONFIAVEL` definido.
   - `internal-proxies` recebe a expressão exata do IP fixo do proxy. O padrão do Spring confiaria em
     todas as faixas privadas, o que tornaria o IP de origem forjável por qualquer contêiner.

   Com isso, cookies `__Host-`/Secure, HSTS e o IP registrado na auditoria funcionam atrás do proxy.
   O banco fica numa rede `internal`, sem porta publicada.
5. **Primeiro acesso por comando explícito.** `fluxo.sh primeiro-acesso` reaproveita
   `infra/db/admin-inicial.sql` e o `GerarHashSenha` da própria imagem.
   - A senha provisória é aleatória e exibida uma vez; a troca é obrigatória.
   - O comando é recusado se já houver administrador.
   - Dados fictícios só entram com `demo --confirmo-dados-ficticios`, porque as senhas são públicas.
6. **Backup lógico consistente:**
   - `pg_dump -Fc` a partir de um snapshot exportado; o manifesto é lido no mesmo snapshot.
   - O manifesto traz contagens, versão da migração e hash da cabeça da cadeia de auditoria.
   - SHA-256 dos arquivos e permissões 600/700.
   - **Dados de sessão excluídos** (são credenciais).

   Alternativa descartada nesta etapa: backup físico com arquivamento de WAL. Daria RPO menor, mas
   exige infraestrutura e decisão de RPO (V-10).
7. **Restauração só em projeto separado e identificado:**
   - confere o SHA-256;
   - `pg_restore --single-transaction`;
   - verifica `auditoria.verificar_cadeia()` e confronta com o manifesto;
   - apaga sessões residuais;
   - sobe a aplicação recuperada noutra porta e rede.

   Nunca sobrescreve o original; a promoção é decisão humana. Como os gatilhos de auditoria são
   criados depois da carga dos dados (*post-data*), a restauração não gera registros espúrios e a
   cadeia permanece verificável.
8. **Atualização:**
   - backup obrigatório antes;
   - imagem anterior preservada (`anterior-<data>`);
   - migração separada.

   Reverter a aplicação só é possível se nenhuma migração nova foi aplicada, porque o Flyway recusa
   versões desconhecidas. Caso contrário, restaura-se o banco. Não há rollback automático de migrações.
9. **CI:** novo job "Homologação" que exercita o ambiente entregue:
   - build;
   - isolamento;
   - primeiro acesso;
   - cabeçalhos;
   - proxy confiável;
   - persistência;
   - banco indisponível;
   - backup;
   - restauração isolada conferida pela API;
   - atualização;
   - migração com falha;
   - ausência de segredos nos logs.

   O artefato contém só o relatório de restauração. O job SQL passou a usar o `psql` da imagem do
   runner: o merge do PR #11 teve o job cancelado por um `apt-get` travado, falha de infraestrutura.

## Consequências

- Linux e Windows usam o mesmo script; no Windows, ele roda **dentro do WSL2** (Docker Desktop). Não há script PowerShell paralelo para manter.
- Uma instância da aplicação: o comprovante HMAC dos relatórios é por processo (ADR-0010).
- Os valores de frequência, retenção, RPO e RTO são **propostas** (V-10).
- O item 25 (contexto do banco forjável pelo papel da aplicação) continua aberto e impede o uso real sem aceite formal do risco.
- Pendências humanas do roteiro de homologação: Windows/WSL2, acessibilidade, usabilidade e ensaio de recuperação com tempo medido.
