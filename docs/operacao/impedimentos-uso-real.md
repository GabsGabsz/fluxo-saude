# Decisões institucionais e limitações que impedem o uso real

> O Fluxo Saúde está preparado para **homologação com dados fictícios**. **Não** está liberado para
> uso com pacientes reais. O CI verde mostra que o software faz o que os testes descrevem. Ele não
> substitui as decisões abaixo, que pertencem à instituição (ERS v1.1, §16 e §19), nem configura
> declaração de conformidade legal (LGPD) ou de prontidão para produção.

Detalhes e propostas técnicas em [`decisoes-a-validar.md`](../decisoes-a-validar.md).

## 1. Decisões institucionais pendentes (bloqueiam o uso real)

| Tema | Situação atual | Validação |
|---|---|---|
| Fluxo, etapas, desfechos e motivos reais da unidade | Padrões propostos (itens 1–6) | V-04, V-07 |
| **Limites de alerta / SLA** por etapa e escalonamento | Nenhuma regra vem cadastrada; escalonamento não implementado (itens 27–33) | V-05, V-06 |
| **Fórmulas oficiais** de indicadores e relatórios | Todas propostas (`relatorios-v2`, `docs/indicadores.md`, `docs/relatorios.md`) | V-09 |
| **Grupos pequenos** (risco de reidentificação, inclusive para a Direção) | Nenhuma supressão aplicada; risco declarado em todo relatório (item 56) | V-08 |
| Matriz de perfis e permissões; acesso nominal | Proposta da ERS §3 (itens 13, 22, 30, 55) | V-06, V-08 |
| Bases legais, finalidade, controlador/operador, encarregado, resposta a incidentes (LGPD) | Não definidos (ERS §16) | V-08 |
| Retenção de auditoria, leituras nominais, backups e logs | Não definida (itens 47, 50); backups com proposta em `homologacao.md` §6 | V-10 |
| RPO/RTO, disponibilidade, monitoramento e contingência (RNF-004, RNF-009, RNF-018) | Propostas (`homologacao.md` §6); metas dependem do contrato de implantação | V-10 |
| MFA e integração com SSO institucional | Não implementados (RNF-013) | V-10 |
| Procedimento de entrega da senha provisória por canal seguro | A definir (item 24) | V-10 |
| Domínio, certificado, rede, firewall e cofre de segredos da instituição | Não contratados nem configurados (por decisão desta etapa) | Implantação |

## 2. Limitações técnicas conhecidas

| Limitação | Efeito | Referência |
|---|---|---|
| **Item 25 — contexto do banco forjável pelo papel da aplicação.** As GUCs de contexto (usuário/unidade) podem ser definidas pelo próprio `fluxo_app`. | As regras do banco (RLS, autoria) protegem contra erros da aplicação, mas **não** contra quem execute SQL arbitrário como `fluxo_app` (ex.: aplicação comprometida). Proposta: contexto não forjável, numa etapa própria. | `decisoes-a-validar.md` item 25; ADR-0004 |
| **Acessibilidade** só com verificação automática (axe-core, WCAG 2 A/AA) | Auditoria eMAG completa e teste com leitor de tela pendentes. | RNF-016 |
| Uma única instância da aplicação | Comprovante HMAC dos relatórios por processo: reinício invalida comprovantes; várias instâncias exigiriam chave compartilhada. | ADR-0010, item 60 |
| HTTPS de homologação com CA interna | Não serve a usuários finais; exige certificado institucional. | `homologacao.md` §5 |
| Backup lógico diário (sem arquivamento contínuo de WAL) | Perda de até um dia de registros num desastre (proposta). | `homologacao.md` §6 |
| Sem rollback automático de migrações | Reverter após migração aplicada exige restaurar o banco. | `homologacao.md` §7 |
| Sem integração com sistemas oficiais/regulação | Protocolos e estados externos informados manualmente; o sistema não representa toda a rede. | ERS §15 |
| Relatórios e indicadores operacionais | Não são causais, clínicos nem ranking individual. | ADR-0009, ADR-0010 |
| Avaliações humanas do roteiro de homologação (usabilidade, legibilidade, Windows/WSL2, ensaios de recuperação) | Pendentes. | [`roteiro-homologacao.md`](roteiro-homologacao.md) |

## 3. Condição mínima proposta para avaliar um piloto real (não é autorização)

1. Decisões da seção 1 formalizadas pela instituição, com responsáveis nomeados.
2. Item 25 resolvido, ou o risco formalmente aceito pelo encarregado e pela TI.
3. Auditoria de acessibilidade e de segurança independentes.
4. Ensaio de restauração com tempo medido, registrado e aprovado.
5. Roteiro de homologação executado com todos os cenários **PENDENTE (humano)** aprovados.
