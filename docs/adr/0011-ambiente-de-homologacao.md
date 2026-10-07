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
8. **Atualização** (revisada no PR #12, §10):
   - backup obrigatório antes;
   - cada build recebe uma tag nova, e a imagem anterior continua disponível;
   - aplicação e proxy ficam parados durante a migração separada;
   - comparação das migrações aplicadas antes e depois da tentativa.

   Não há rollback automático de migrações.
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

10. **Revisão do PR #12** (head `8371b30`; job Homologação falhou na página inicial).

   **10.1 Página inicial.** A verificação pedia `GET /` com `Accept: application/json`. A página
   inicial do Spring Boot (`WelcomePageHandlerMapping`) só é servida quando o `Accept` inclui
   `text/html`; nos demais casos não há manipulador e o Spring responde 406.

   Conclusão: defeito do **teste**, não da aplicação nem da segurança.
   - A verificação agora pede a página como navegador e carrega os recursos estáticos (CSS, ícone,
     `main.js` e módulos importados).
   - Mantém as asserções de HSTS, CSP, frame, cookies e saúde.
   - Registra, como diagnóstico, o status da mesma requisição com `Accept: application/json`, para o
     CI confirmar a causa.
   - Em caso de falha, grava diagnóstico sanitizado (status, Content-Type, cabeçalhos seguros, trecho
     da resposta sem cookies nem tokens) e trechos de log sanitizados no artefato.

   **10.2 Imagem compatível na recuperação.** A imagem é identificada pelo ID de conteúdo e pelo
   commit (rótulo) e carrega o inventário das migrações (`/app/migracoes.txt`).
   - O manifesto do backup registra o ID da imagem que atendia o banco e as migrações aplicadas.
   - `restaurar` escolhe e verifica a imagem **antes** de qualquer migração: deve existir e embutir
     exatamente as migrações do backup. Por padrão é a registrada; com `--imagem`, outra explícita.
   - Ausente ou incompatível: recusa acionável, sem construir nem cair na tag atual.
   - O projeto restaurado roda o Flyway com alvo `current` (só valida). O `subir` confere imagem ×
     esquema antes da aplicação, e o estado final é conferido depois da subida.
   - Ninguém constrói imagem implicitamente: `subir` usa `--no-build`.

   **10.3 Avanço parcial.** Uma transação **por migração** não torna o lote atômico. `atualizar`
   compara as migrações aplicadas antes e depois e distingue dois casos:
   - **Falha sem avanço:** volta automaticamente à imagem anterior, cuja compatibilidade está
     comprovada pela igualdade dos conjuntos.
   - **Avanço parcial:** aplicação e proxy ficam **parados**, código 3 e relatório. As orientações são
     correção para a frente ou restauração isolada com a imagem do backup.

   O Flyway aceita uma imagem antiga sobre um esquema mais novo (ignora migrações "futuras"), por isso
   a verificação de compatibilidade do `subir` é o que impede essa combinação. O CI testa os dois
   casos com imagens derivadas que acrescentam migrações **artificiais exclusivas do teste**
   (`deploy/homologacao/teste-atualizacao/`, V9001 que confirma e V9002 que falha). As migrações do
   produto não são alteradas.

   **10.4 Origem forjada.** Cada requisição de teste leva um `X-Correlation-Id` próprio, que a
   aplicação adota e grava na auditoria.
   - O CI exige o status esperado: 401 por credencial; 403 indicaria CSRF.
   - Exige **exatamente um** evento com aquele identificador e confere o IP desse evento.
   - Um autoteste mostra que a conferência reprova quando o evento não existe.
   - No HTTP direto, o cookie CSRF não é `Secure`, porque segue `request.isSecure()`; por HTTPS, é.
     Assim, a requisição direta passa pelo CSRF e chega à verificação de credencial, sem afrouxar a
     configuração.

11. **Segunda revisão do PR #12** (head `a1e4bee`; job Homologação falhou no cookie CSRF).

   **11.1 Cookie CSRF.** O teste usava o mesmo cliente das requisições anteriores e exigia um
   `Set-Cookie` novo. O `CookieCsrfTokenRepository` só emite o cookie quando **cria** o token; com o
   cookie já no jar, ele é reutilizado sem reemissão. A premissa do teste estava errada; a aplicação
   não foi alterada. A verificação agora tem três fases:
   - **emissão:** cliente novo (jar vazio) recebe `XSRF-TOKEN` com `Secure`, `Path=/` e sem `Domain`;
   - **armazenamento:** o cookie guardado no jar é `secure`, host-only e com `Path=/`;
   - **reutilização:** com o mesmo jar, reemissão não é exigida (se houver, também precisa ser segura) e
     o token guardado continua **válido**: um POST com ele é recusado por credencial (401), não por
     CSRF (403).

   Os atributos são conferidos como **tokens** do `Set-Cookie`, não por substring (o critério antigo
   aceitaria `secure` dentro do valor). `verificar.py autoteste` prova que o validador rejeita cookie
   sem `Secure` e variantes; no CI, o mesmo validador rejeita o cookie **real** emitido por HTTP direto
   (valor removido dentro do contêiner). Nenhum valor de cookie é impresso.

   **11.2 Correção para a frente.** `atualizar` normal exige banco = imagem atual e por isso recusava
   a imagem corrigida depois de um avanço parcial — o caminho documentado não funcionava. Agora:
   - a falha com avanço parcial registra uma **marca** com as migrações antes/depois, as imagens e o
     backup prévio, e um **inventário** (arquivo + SHA-256) das migrações aplicadas, tirado das imagens
     que as aplicaram;
   - com a marca, `subir`, `reiniciar` e `atualizar` normal são recusados (a restauração cria projeto
     separado e não herda a marca);
   - `atualizar --imagem C --continuar-parcial` mantém tudo parado, confere o inventário contra C
     (recusa antes de migrar se divergir), faz backup separado do estado parcial, aplica só as
     pendentes e só volta a atender quando o `subir` confirma esquema = C, imagem em execução e saúde;
   - falha sem novo avanço → continua parado (código 4); novo avanço parcial → marca atualizada
     (código 3).

   Alternativas descartadas: relaxar a igualdade da atualização normal (perderia a proteção);
   `flyway repair` ou edição do histórico (apagaria a evidência e mascararia divergências); reativar a
   imagem anterior após falha da correção (incompatível com o esquema parcial).

## Consequências

- Linux e Windows usam o mesmo script; no Windows, ele roda **dentro do WSL2** (Docker Desktop). Não há script PowerShell paralelo para manter.
- Uma instância da aplicação: o comprovante HMAC dos relatórios é por processo (ADR-0010).
- Os valores de frequência, retenção, RPO e RTO são **propostas** (V-10).
- O item 25 (contexto do banco forjável pelo papel da aplicação) continua aberto e impede o uso real sem aceite formal do risco.
- Pendências humanas do roteiro de homologação: Windows/WSL2, acessibilidade, usabilidade e ensaio de recuperação com tempo medido.
