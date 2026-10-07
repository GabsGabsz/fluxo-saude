# Ambiente de homologação — instalação, operação e recuperação

> **Escopo.** Este ambiente serve à **homologação com dados fictícios**. Ele **não** autoriza
> implantação pública nem uso com pacientes reais: há decisões institucionais e limitações
> pendentes, listadas em [`impedimentos-uso-real.md`](impedimentos-uso-real.md). O CI passar não
> significa conformidade legal nem prontidão para produção.

Arquitetura e justificativas: [ADR-0011](../adr/0011-ambiente-de-homologacao.md). Roteiro de testes
do piloto: [`roteiro-homologacao.md`](roteiro-homologacao.md).

## 1. O que sobe

| Serviço | Papel | Exposição |
|---|---|---|
| `db` | PostgreSQL 16, volume persistente `<projeto>_dados`. Bootstrap existente (`infra/db/init`) cria `fluxo_owner`, `fluxo_app` e o banco `fluxo` na 1ª inicialização. | Nenhuma porta publicada. Rede interna `dados` (sem saída externa). |
| `migracao` | O mesmo jar no perfil `migracao` (Flyway). É o **único** processo com a senha do dono. | Só a rede `dados`. Roda e termina. |
| `app` | Aplicação com o papel `fluxo_app` (RLS). Sobe **somente** se a migração terminar com sucesso. Aceita `X-Forwarded-*` **apenas** do IP fixo do proxy. | Sem porta publicada. |
| `proxy` | Caddy: termina o HTTPS (CA interna de homologação) e encaminha na mesma origem. Ignora `X-Forwarded-*` enviados pelo cliente. | `127.0.0.1:8443` por padrão. |

Os segredos são arquivos gerados localmente, montados como *secrets* do Compose. Ficam fora da
imagem, do Git, dos logs e dos artefatos do CI.

## 2. Pré-requisitos

**Linux**
- Docker Engine 24 ou superior com o plugin Compose v2.24 ou superior (`docker compose version`).
- bash, openssl, curl, coreutils (`sha256sum`).
- python3, só para as verificações pela API (`verificar.py`).
- ~4 GB de RAM livres e ~3 GB de disco.

**Windows**
- Docker Desktop com o backend **WSL2** e uma distribuição Linux (ex.: Ubuntu) com a integração do
  Docker Desktop habilitada (*Settings → Resources → WSL integration*).
- Clone e execute **dentro da distribuição WSL**, num caminho Linux como `~/fluxo-saude`, e **não**
  em `/mnt/c/...`. Isso preserva permissões, finais de linha LF e desempenho.
- Os comandos abaixo são os mesmos do Linux, digitados no terminal da distribuição. O navegador do
  Windows alcança `https://localhost:8443` normalmente.
- O `.gitattributes` força LF nos scripts. Se o clone foi feito pelo Windows, refaça o clone no WSL.

## 3. Instalação (primeira vez)

```bash
git clone https://github.com/GabsGabsz/fluxo-saude.git && cd fluxo-saude
H=deploy/homologacao
bash $H/fluxo.sh preparar     # segredos aleatórios em deploy/homologacao/segredos/<projeto> (700)
bash $H/fluxo.sh subir        # imagem -> banco -> MIGRAÇÃO -> aplicação -> proxy HTTPS
```

O `subir` só termina com sucesso se, nesta ordem:
1. o banco ficar saudável;
2. a migração terminar com código 0;
3. a aplicação ficar saudável;
4. `https://localhost:8443/actuator/health` responder `UP`.

Se a migração falhar, a aplicação **não** é iniciada e o comando retorna erro com as últimas linhas
do log da migração.

**Opções de `preparar`** (cada projeto tem as suas):

| Opção | Padrão |
|---|---|
| `--porta` | 8443 |
| `--rede` (prefixo /24 da rede `borda`) | 172.31.240 |
| `--dominio` | localhost |
| `--endereco` (endereço de publicação) | 127.0.0.1 |

Vários projetos podem coexistir com `-p NOME` e portas e redes diferentes. O `preparar` nunca
sobrescreve segredos nem configuração existentes.

### Confiar no HTTPS de homologação

O proxy usa a CA interna do Caddy. Para o navegador aceitar o certificado:

```bash
bash $H/fluxo.sh certificado ~/fluxo-homologacao-ca.crt
```

Instale esse arquivo como autoridade confiável **somente em máquinas de teste**:
- Windows: `certmgr.msc` → *Autoridades de Certificação Raiz Confiáveis*.
- Linux: siga o procedimento da distribuição (ex.: `/usr/local/share/ca-certificates` + `update-ca-certificates`).

Nunca reutilize essa CA fora da homologação.

### Primeiro acesso: 1ª unidade e 1º administrador

```bash
bash $H/fluxo.sh primeiro-acesso --unidade-codigo UPA_TESTE --unidade-nome "UPA Fictícia" \
  --admin-login admin.teste --admin-nome "Administrador Fictício"
```

- Reutiliza `infra/db/admin-inicial.sql` (regras existentes: `provisionar_unidade`, lotação ADMINISTRADOR).
- O hash Argon2id é gerado pelo utilitário da própria aplicação (`GerarHashSenha`, dentro da imagem).
- A senha provisória é aleatória, aparece **uma única vez** no terminal e não vai para log nem arquivo.
  - Para automação, use `--senha-em ARQ`, que grava com permissão 600.
- No 1º login a troca de senha é **obrigatória**. Até ela acontecer, a API responde 403 `TROCA_DE_SENHA_OBRIGATORIA`.
- O comando recusa rodar se já existir um administrador. A partir daí, use a tela **Usuários**.
- Não existe senha administrativa fixa nem conta de demonstração habilitada por padrão.

### Dados de demonstração (somente por ação explícita)

```bash
bash $H/fluxo.sh demo --confirmo-dados-ficticios
```

Carrega os dados **fictícios** do E2E: unidades `E2E_NORTE` e `E2E_SUL` e usuários `*.e2e`.
**As senhas dessas contas são públicas** (`e2e/dados/fixtures.json`), por isso:
- use apenas em ambiente isolado, nunca acessível por terceiros;
- nunca rode este comando num ambiente que receberá dados reais.

Uma instalação normal **não** carrega esses dados.

## 4. Operação do dia a dia

| Ação | Comando |
|---|---|
| Iniciar / verificar | `bash $H/fluxo.sh subir` (idempotente) · `bash $H/fluxo.sh estado` |
| Parar (preserva dados) | `bash $H/fluxo.sh parar` |
| Reiniciar banco e aplicação | `bash $H/fluxo.sh reiniciar` |
| Logs | `bash $H/fluxo.sh logs app` (ou `migracao`, `db`, `proxy`) |
| Diagnóstico | `bash $H/fluxo.sh diagnostico` |

O diagnóstico mostra:
- os contêineres;
- se o banco aceita conexões ("BANCO INDISPONÍVEL" caso não aceite);
- o código de saída da migração;
- a saúde da aplicação (Docker e HTTPS);
- as últimas linhas de log.

Os dados persistem no volume `<projeto>_dados` após `parar`, `reiniciar`, reinício da máquina ou
recriação dos contêineres. Só `remover --confirmo-apagar-dados` apaga o volume.

**Sinais de saúde.** `GET /actuator/health` é público e responde apenas `{"status":"UP"}` (200) ou
`DOWN` (503). Inclui o banco, sem detalhes internos (`show-details: never`). Nenhum outro endpoint
do actuator é exposto.

| Sintoma | Provável causa | O que fazer |
|---|---|---|
| `health` = 503 | Banco indisponível | `diagnostico`; `logs db`; com o banco de volta, a aplicação reconecta sozinha (o pool tenta de novo). |
| Sem resposta em `:8443` | Proxy parado ou porta ocupada | `estado`; `logs proxy`. |
| `subir` falha na migração | Senha do dono incorreta ou migração inválida | `logs migracao`. A aplicação continua parada (proposital). |
| Aplicação `unhealthy` | Banco ou credencial do `fluxo_app` | `logs app`. Os segredos ficam em `segredos/<projeto>/db_app`. |

## 5. Configuração segura (o que já está pronto e o que a implantação precisa)

**Já configurado**
- Cookie de sessão `__Host-FLUXO` (Secure, HttpOnly, SameSite=Strict, sem Domain), CSRF (`XSRF-TOKEN` → `X-XSRF-TOKEN`) e mesma origem.
- CSP restritiva, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` e HSTS. A aplicação reconhece o HTTPS informado pelo proxy confiável.
- **Proxy confiável explícito:**
  - a aplicação só aceita `X-Forwarded-For`/`-Proto` vindos de `FLUXO_PROXY_CONFIAVEL` (IP fixo do proxy, expressão regular exata);
  - o Caddy descarta esses cabeçalhos quando vêm do cliente.
  - O CI confere que um `X-Forwarded-For` forjado não vira o IP registrado na auditoria, nem via proxy nem direto na aplicação.
- O banco fica numa rede `internal` (sem porta publicada nem saída externa).
- A aplicação recebe só a senha do `fluxo_app` (sem SUPERUSER/BYPASSRLS).
- Imagem: usuário sem privilégios, sistema de arquivos somente leitura, `no-new-privileges`, sem capabilities.

**A definir na implantação institucional (não feito aqui)**
- Domínio e certificado da instituição: troque `tls internal` no `Caddyfile` por `tls /cert.pem /chave.pem` e use `preparar --dominio`.
- Publicação em interface de rede, firewall e regras de acesso.
- Guarda dos segredos num cofre da instituição.
- Retenção de logs.
- Monitoramento externo do `/actuator/health`.

Nenhum serviço foi contratado, nenhum domínio registrado e nada foi publicado.

**Uma instância da aplicação.** O comprovante HMAC dos relatórios usa uma chave aleatória **por
processo** (ADR-0010):
- reiniciar a aplicação invalida comprovantes emitidos antes (o usuário recalcula o relatório);
- várias instâncias exigiriam uma chave compartilhada, fora do escopo desta etapa.

Sessões ficam no banco e sobrevivem a reinícios.

## 6. Backup e restauração

### Backup

```bash
bash $H/fluxo.sh backup                      # destino padrão: deploy/homologacao/backups (700/600)
bash $H/fluxo.sh backup --destino /caminho/protegido
```

Gera três arquivos:
- `fluxo-<projeto>-<UTC>.dump`: `pg_dump -Fc` de todo o banco `fluxo`. Inclui episódios, eventos, pendências, configurações, regras, usuários, lotações, permissões, auditoria e histórico de migrações.
- `.manifesto`: versão da migração, contagens por tabela, hash da cabeça da cadeia de auditoria e problemas da cadeia. Lido no **mesmo instantâneo** do dump (snapshot exportado).
- `.sha256`: somas de verificação.

**Os dados das sessões HTTP (`sessao.*`) são excluídos**: um identificador de sessão é uma credencial.

O backup **não** contém as senhas dos papéis do banco. A recuperação cria papéis novos com segredos
novos.

**Proteção dos arquivos:**
- os arquivos são criados com permissão 600 em diretório 700;
- o dump contém **dados pessoais e de saúde** quando houver dados reais;
- proposta: copiar para armazenamento institucional cifrado (ex.: `gpg --symmetric` ou volume cifrado), com a senha de cifragem guardada num cofre e com acesso nominal;
- guardar pelo menos uma cópia fora do servidor;
- nunca anexar backups a chamados, e-mails ou artefatos de CI.

**Propostas (a validar — V-10; não são garantias):**

| Parâmetro | Proposta |
|---|---|
| Frequência | diária (fora do pico) e antes de toda atualização (automático em `atualizar`) |
| Retenção | 7 diários + 4 semanais + 3 mensais |
| Perda máxima aceitável (RPO) | até 24 h com backup diário. Menor exige arquivamento contínuo de WAL, fora do escopo. |
| Tempo de recuperação (RTO) | algumas horas, incluindo conferência. A restauração automatizada em si leva minutos no volume de homologação. |
| Teste | uma restauração completa por mês, com o relatório arquivado |

### Restauração (sempre num ambiente SEPARADO)

```bash
bash $H/fluxo.sh restaurar deploy/homologacao/backups/fluxo-<projeto>-<UTC>.dump \
  [--projeto-destino NOME] [--porta 9443] [--rede 172.31.241]
```

1. Confere o SHA-256 e recusa arquivo alterado ou corrompido.
2. Cria um projeto **novo e identificado** (padrão `<projeto>-recuperacao-<data>`), com volume, segredos e porta próprios.
   - Recusa se o destino já existir ou se for o próprio original. **Nada é sobrescrito; o original não é tocado.**
3. `pg_restore --single-transaction --exit-on-error` no banco vazio criado pelo bootstrap.
4. Verifica a **cadeia de auditoria** (`auditoria.verificar_cadeia()` sem problemas).
5. Compara contagens, versão da migração e hash da cabeça da auditoria com o manifesto. Divergência interrompe a restauração.
6. **Invalida sessões:** apaga qualquer linha de `sessao.spring_session`. O backup não as contém, mas um dump antigo poderia conter. O histórico de auditoria permanece intacto.
7. Sobe migração (apenas validação) e aplicação. Grava o relatório `deploy/homologacao/relatorios/restauracao-<destino>.txt` (sem segredos).

Depois, confira pela API e pela tela, no endereço do projeto recuperado, os dados conhecidos.
Exemplo do CI: `python3 $H/verificar.py --base https://localhost:9443 --ca ca.crt conferir-dados --entrada dados.json`.

**Promover o ambiente recuperado** é uma decisão humana:
1. pare o original (`parar`) sem apagar o volume;
2. ajuste a publicação (porta/proxy) para o projeto recuperado;
3. comunique os usuários (todos precisarão entrar de novo);
4. só depois de um período de conferência, decida o destino do volume antigo.

## 7. Atualização e recuperação de atualização malsucedida

```bash
git pull                          # ou checkout da versão aprovada
bash $H/fluxo.sh atualizar
```

1. Faz um **backup obrigatório** antes de qualquer mudança.
2. Preserva a imagem atual como `fluxo-saude:anterior-<data>`.
3. Constrói a nova imagem.
4. Executa `subir`: a **migração roda separada**, e a aplicação nova só sobe se ela terminar com sucesso.

### Recuperação após atualização malsucedida

- **A migração falhou.** Cada migração Flyway roda numa transação, e o PostgreSQL desfaz DDL e dados
  daquela migração. O banco permanece na versão anterior e a aplicação nova não sobe.
  - **Reverter a APLICAÇÃO:** em `ambientes/<projeto>.env`, troque `FLUXO_VERSAO=local` por `FLUXO_VERSAO=anterior-<data>` e rode `subir`.
  - A migração da versão antiga só valida, porque não há migração nova aplicada.
- **A migração passou, mas a versão nova tem defeito.** A aplicação antiga **não** pode simplesmente
  voltar: o Flyway recusa iniciar com migrações aplicadas que ela não conhece, e esse bloqueio é
  proposital.
  - Opções: corrigir para a frente com nova versão e nova migração, ou **restaurar o banco** do backup feito por `atualizar`, num projeto separado, com a imagem anterior. Depois, promover esse projeto.
  - Não há rollback automático de migrações; algumas são irreversíveis por natureza (ex.: dados transformados).
- **A aplicação nova não fica saudável por outro motivo:** `diagnostico` e `logs app`, depois reverter a aplicação como acima, se o banco não mudou.

## 8. Remoção (somente ambientes de teste)

```bash
bash $H/fluxo.sh -p NOME remover --confirmo-apagar-dados   # apaga contêineres, VOLUMES, segredos e configuração do projeto
```
