# ADR-0008 — Interface web: ES modules sem framework, mesma origem da API

- **Status:** proposto (PR da etapa 6)
- **Data:** 2026-10-06
- **Requisitos:** M02, M03, M04, M05, M08, RF-001, RF-010, RF-011, RF-018, RF-022, RNF-001, RNF-002,
  RNF-013, RNF-015, RNF-017, RN-007, RN-013, CA-05, CA-06, CA-11 (ERS v1.1)

## Contexto

A etapa 6 entrega a Torre de Controle e as telas operacionais sobre a API já existente. Restrições:

- monólito modular (ADR-0001);
- sessão no servidor e CSRF no modo SPA (ADR-0002);
- CSP `default-src 'self'`, sem script nem estilo inline;
- nenhum dado sensível no navegador;
- uso em desktop, tablet e telas pequenas, em pt-BR.

A equipe é pequena e o produto precisa durar anos com manutenção simples e superfície de
dependências mínima, porque é sistema de saúde.

## Decisão

1. **JavaScript padrão (ES modules) e CSS, sem framework e sem etapa de build**, servidos pelo
   próprio Spring Boot (`backend/src/main/resources/static`). Interface e API ficam na **mesma
   origem** em produção, sem CORS e sem servidor de front separado.
   - Alternativas avaliadas: React/Vue/Svelte com Vite e HTMX.
   - Descartadas por acrescentarem cadeia de build, centenas de dependências transitivas e
     ciclo de atualização próprio, sem ganho proporcional para cerca de 10 telas de formulário e tabela.
   - Não há biblioteca de terceiros no navegador.
   - Os navegadores atuais suportam ES2020+ e `Intl` nativamente.
2. **Exposição restrita** (`SegurancaConfig`):
   - só `GET` de `/`, `/index.html`, `/favicon.svg` e `/app/**` é público, e esses arquivos não
     contêm dado algum;
   - `/api/**` continua exigindo sessão;
   - o resto é `denyAll`;
   - coberto por `InterfaceEstaticaIT`.
3. **Construção de DOM só por `textContent`** (`nucleo/dom.js`):
   - atributos `on*`, `style` e `srcdoc` são recusados;
   - links só aceitam rotas internas (`#/...`);
   - não há `innerHTML`.
4. **Estado só em memória.**
   - Nada em `localStorage`, `sessionStorage`, IndexedDB, Cache API ou service worker; não há
     modo offline.
   - Uma **geração** muda ao entrar, ao sair, ao trocar de unidade e quando a sessão é revogada.
   - Toda operação pertence ao **contexto** (geração + unidade exibida) em que foi iniciada,
     capturado na entrada de `executar()` e nunca substituído pelo atual:
     - é conferido de novo **depois de obter o token CSRF** e **imediatamente antes do envio**
       (sem `await` entre a conferência e o `fetch`); se mudou, a operação **não é enviada**
       (`RespostaDescartada` com `enviada = false`);
     - é conferido de novo **depois dos cabeçalhos** e **depois do corpo** da resposta; resposta
       de contexto anterior é descartada, inclusive 401 e 409, que então **não** acionam os
       tratadores globais (encerrar sessão, recarregar unidade);
     - não há exceção genérica; só a obtenção do token CSRF (não traz dado) e a saída
       (`encerrarSessao()`, vale para qualquer unidade) têm métodos próprios fora dessa regra;
     - `carregarContexto()` confere a geração e a unidade do catálogo antes de gravar o estado.
   - A tela anterior é desmontada antes da troca de unidade.
5. **Unidade esperada conferida no servidor.**
   - A unidade ativa fica na sessão, que é compartilhada entre as abas.
   - A interface envia a unidade que exibe no cabeçalho `X-Fluxo-Unidade`. Se for outra, o
     servidor responde `409 UNIDADE_ATIVA_ALTERADA` (exceto em `/api/sessao/**`), sem ler nem
     gravar.
   - Um `BroadcastChannel` sem dados apenas avisa as outras abas para recarregar.
6. **CSRF.** O token é lido do cookie `XSRF-TOKEN` antes de cada escrita e renovado sob demanda
   em `/api/sessao/csrf`, porque o servidor o rotaciona no login e na troca de senha. O
   `fetch` usa `credentials: 'same-origin'`, `cache: 'no-store'` e `redirect: 'error'`.
7. **Concorrência.**
   - Toda escrita envia a `versao` lida.
   - Em **409** a tela informa e oferece "Recarregar dados". Nunca reenvia nem sobrescreve, e
     o texto digitado fica no formulário.
   - A **ciência** envia exatamente a `regraVersao` exibida. Se a regra mudou, a lista é relida
     e é preciso um novo clique.
8. **Atualização periódica** (`nucleo/atualizador.js`, 30 s):
   - nunca sobrepõe leituras, e um pedido manual durante uma leitura roda uma vez depois dela;
   - pausa enquanto há edição ou foco num formulário;
   - nunca escreve;
   - falha de conexão mostra a faixa "dados podem estar desatualizados" e mantém a lista.
9. **Tempo.**
   - Os cronômetros partem do `agora` do servidor; o navegador só avança a contagem.
   - Datas aparecem no **fuso da unidade** (`catalogo.unidade.fusoHorario`), nunca no do
     computador.
   - Os campos `datetime-local` são interpretados nesse fuso.
   - As regras de alerta **não** são recalculadas no navegador.
10. **Permissões.**
    - O menu mostra só as telas permitidas, mas o servidor decide sempre.
    - O painel coletivo usa só o endpoint pseudonimizado.
11. **Acessibilidade.**
    - Rótulos associados, foco visível e foco no título a cada tela.
    - Mensagens em `role=alert/status` e estados com ícone e texto, nunca só cor.
    - Atalho "pular para o conteúdo" e tabelas que viram cartões em telas estreitas.
    - O foco do teclado é preservado nas atualizações periódicas.
12. **Endpoints mínimos novos** (V15):
    - `GET /api/catalogo`, com setores, etapas, transições, motivos, especialidades e
      profissionais, estes só para quem pode vê-los;
    - `GET /api/sessao/unidades`;
    - `GET /api/pacientes?cns=|identificador=`, busca exata e auditada;
    - `alertas` no detalhe do caso.

    Todos passam por RLS e permissão, sem acesso genérico ao banco.

## Testes

- **Núcleo:** `node --test` sem dependências (`backend/src/test/js`), no job `interface` do CI.
  `contexto.test.mjs` controla separadamente a espera do token CSRF, a chegada dos cabeçalhos e
  a conclusão do corpo, com troca de unidade e encerramento de sessão (revisão do PR #8).
- **E2E** (`e2e/`, Playwright):
  - roda contra o **jar real** e o **PostgreSQL 16 real**, com o bootstrap de produção, o job
    de migração e dados fictícios;
  - job `e2e` do CI, com relatório, capturas de tela e log da aplicação como artefatos;
  - os cenários estão em `e2e/testes/*.spec.mjs`.

## Consequências

- Não há tipagem estática nem componentes reativos. O custo é compensado por módulos pequenos e
  pelo helper de DOM seguro e de formulário. Se a interface crescer muito, migrar para um
  framework é uma decisão nova.
- Navegadores sem suporte a ES modules (muito antigos) não são atendidos.
- O cabeçalho `X-Fluxo-Unidade` é opcional para outros clientes da API: sem ele, vale a
  unidade da sessão, como antes.
