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
bash $H/fluxo.sh construir    # imagem com tag NOVA (commit + data) e o commit no rótulo; passa a ser a do projeto
bash $H/fluxo.sh subir        # banco -> MIGRAÇÃO -> compatibilidade -> aplicação -> proxy HTTPS
```

A imagem do projeto fica em `ambientes/<projeto>.env` (`FLUXO_IMAGEM`). Nenhum comando além de
`construir` e `atualizar` constrói imagem: `subir` e `restaurar` recusam imagem ausente.

O `subir` só termina com sucesso se, nesta ordem:
1. o banco ficar saudável;
2. a migração (job separado) terminar com código 0;
3. as migrações aplicadas no banco forem **exatamente** as embutidas na imagem (inventário
   `/app/migracoes.txt` gerado no build). Isso é conferido **antes** de a aplicação subir;
4. a aplicação ficar saudável usando a imagem configurada (ID conferido);
5. `https://localhost:8443/actuator/health` responder `UP`.

Se a migração falhar ou a imagem não for compatível com o esquema, a aplicação e o proxy ficam
**parados** e o comando retorna erro com as últimas linhas de log.

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
| `subir` recusa: "imagem incompatível com o esquema" | Imagem não corresponde às migrações aplicadas (ex.: após avanço parcial) | §7. Nunca force a imagem antiga. |
| `subir`/`restaurar` recusa: "imagem não existe" | Imagem não construída ou não carregada neste host | `construir` (projeto) ou reconstruir/`docker load` a imagem registrada no backup. |
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
- `.manifesto`, lido no **mesmo instantâneo** do dump (snapshot exportado):
  - migrações aplicadas e versão do esquema;
  - contagens por tabela;
  - hash da cabeça da cadeia de auditoria e problemas da cadeia;
  - **identidade imutável da imagem** que atendia o banco: ID do conteúdo `sha256:…` (não só a tag), commit do rótulo e migrações que ela embute.
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
  [--projeto-destino NOME] [--porta 9443] [--rede 172.31.241] [--imagem REF]
```

1. Confere o SHA-256 e exige o manifesto. Recusa arquivo alterado ou corrompido.
2. **Escolhe e verifica a imagem de recuperação ANTES de qualquer processo de migração:**
   - por padrão, usa a imagem registrada no backup (ID `sha256:…`); com `--imagem`, outra explicitamente informada;
   - a imagem precisa existir no host **e** embutir exatamente as migrações aplicadas no backup;
   - imagem ausente ou incompatível é recusada **antes de criar qualquer coisa**, com instruções: reconstruir a partir do commit registrado (`git checkout <commit> && fluxo.sh construir --tag … --sem-ativar`) ou `docker load` da imagem preservada;
   - a tag atual do projeto **nunca** é usada por omissão e nada é construído.
3. Cria um projeto **novo e identificado** (padrão `<projeto>-recuperacao-<data>`), com volume, segredos e porta próprios.
   - A imagem escolhida é marcada como `fluxo-saude:recuperacao-<destino>`.
   - O Flyway fica com alvo `current`: só **valida** o histórico e as somas, **nunca** migra para a frente. Restaurar e atualizar ficam separados.
   - Recusa se o destino já existir ou se for o próprio original. **Nada é sobrescrito; o original não é tocado.**
4. `pg_restore --single-transaction --exit-on-error` no banco vazio criado pelo bootstrap.
5. Verifica a **cadeia de auditoria** (`auditoria.verificar_cadeia()` sem problemas).
6. Compara contagens, migrações aplicadas e hash da cabeça da auditoria com o manifesto. Divergência interrompe a restauração.
7. **Invalida sessões:** apaga qualquer linha de `sessao.spring_session`. O backup não as contém, mas um dump antigo poderia conter. O histórico de auditoria permanece intacto.
8. Sobe a migração (só validação), confere a compatibilidade imagem × esquema e sobe a aplicação.
9. Confere o **estado final**, depois da subida: o manifesto do banco continua idêntico e a aplicação em execução usa o ID da imagem escolhida.
10. Grava o relatório `deploy/homologacao/relatorios/restauracao-<destino>.txt` (sem segredos), com imagem do backup, imagem de recuperação, imagem em execução, conferências e `estado_final`.

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
bash $H/fluxo.sh atualizar        # ou: atualizar --imagem REF (imagem já construída/carregada)
```

1. **Backup obrigatório**, com o manifesto registrando a imagem atual.
2. Constrói a nova imagem (tag nova; a atual continua existindo) ou usa `--imagem`.
3. Registra as migrações aplicadas **antes** e confere que coincidem com as da imagem atual.
4. **Manutenção:** aplicação e proxy são **parados** antes de migrar. Nenhuma aplicação atende enquanto o esquema muda, porque a antiga pode não ser compatível com o esquema parcialmente migrado.
5. Executa a **migração separada** com a nova imagem e registra as migrações aplicadas **depois**.
6. Decide pelo resultado. Há um relatório em `relatorios/atualizacao-<projeto>-<UTC>.txt` (código 0, 1 ou 3).

| Resultado | Situação | O que o script faz | Código |
|---|---|---|---|
| `SUCESSO` | migração ok e esquema = migrações da imagem nova | ativa a nova imagem e sobe | 0 |
| `FALHA_SEM_AVANCO` | migração falhou e as migrações aplicadas são **as mesmas de antes** | a compatibilidade da imagem anterior está **comprovada** (mesmo conjunto): restabelece a aplicação anterior e retorna erro | 1 |
| `FALHA_COM_AVANCO_PARCIAL` | migração falhou **depois** de confirmar parte das migrações novas | **deixa aplicação e proxy parados**; nenhuma imagem disponível é comprovadamente compatível | 3 |

### Por que pode haver avanço parcial

O Flyway executa **cada migração** numa transação. O PostgreSQL desfaz a migração que falhou, mas
**não** desfaz as anteriores, que já confirmaram. Numa atualização com V(n+1) e V(n+2), a primeira
pode ficar aplicada e a segunda falhar. Por isso **não há rollback integral automático**: o CI
demonstra esse caso com migrações artificiais exclusivas do teste (`teste-atualizacao/`).

### Recuperação após falha com avanço parcial

**Não** volte simplesmente para a imagem anterior. O Flyway a aceitaria, porque ignora migrações
"futuras" por padrão, mas ela não conhece o esquema novo. O `subir` a recusa pela verificação de
compatibilidade. Escolha uma das opções:

- **Correção para a frente** (preferível quando o defeito está na migração ou no código novo): nova
  imagem com as migrações corrigidas e `atualizar --imagem <corrigida>`. As migrações já aplicadas
  não podem ser alteradas (o Flyway confere as somas); corrija com migrações novas.
- **Restauração isolada** do backup feito no passo 1, com a imagem registrada nele:
  `fluxo.sh restaurar <backup>`. Ela cria um projeto separado, valida o esquema sem migrar e confere
  os dados. Depois de conferir, promova o projeto recuperado (§6).
  - O original, com o esquema parcial, fica preservado para análise.
  - Registros feitos **depois** do backup não estão nele (ver RPO, §6).

### Falha sem avanço e outros casos

- **Falha sem avanço:** o script já restabeleceu a aplicação anterior (compatibilidade comprovada).
  Investigue a migração (`logs migracao`, relatório) antes de tentar de novo.
- **A migração passou, mas a aplicação nova tem defeito:** não há reversão só da aplicação, porque o
  esquema já é o da versão nova. Faça correção para a frente ou a restauração isolada acima.
- Algumas migrações são irreversíveis por natureza (ex.: dados transformados). Nenhum procedimento
  aqui promete desfazê-las.

## 8. Remoção (somente ambientes de teste)

```bash
bash $H/fluxo.sh -p NOME remover --confirmo-apagar-dados   # apaga contêineres, VOLUMES, segredos e configuração do projeto
```
