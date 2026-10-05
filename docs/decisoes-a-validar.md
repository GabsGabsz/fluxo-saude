# Decisões a validar com a equipe assistencial (ERS §19)

Escolhas feitas para não bloquear o desenvolvimento. Todas são **configuráveis** ou de baixo
custo para mudar, mas precisam ser confirmadas nas entrevistas com profissionais.

1. **Etapas e transições padrão** (`fluxo.provisionar_unidade`). Incluí etapas que a ERS cita
   apenas no exemplo (§11 "Aguardando solicitação de transferência") e desfechos que a ERS
   agrupa em "outro desfecho": **Internado, Óbito, Evasão**. O grafo de transições define o
   que é permitido (ex.: não se vai de "Em atendimento" direto para "Aceito").
2. **Motivo obrigatório** em todas as etapas de espera e em "Aguardando transporte"; não
   obrigatório em "Aceito".
3. **"Transferência solicitada" exige o nº do protocolo** no sistema oficial (RF-009).
4. **Desfecho encerra as pendências abertas** (status `ENCERRADA_POR_DESFECHO`, justificativa
   automática e evento na linha do tempo), na mesma transação do desfecho. O banco verifica no
   COMMIT que não sobrou pendência aberta. Alternativa: impedir o desfecho enquanto houver
   pendência aberta (mais atrito para a equipe).
5. **RF-003 estrito:** o banco impede dois episódios ativos do mesmo paciente na mesma
   unidade. A "suspeita" (nome/nascimento parecidos com outro paciente) gera pedido de
   confirmação na tela — próxima etapa.
6. **Registro retroativo** limitado a 24 h; tolerância de 2 min para relógio adiantado.
7. **Mesmo motivo mantém o relógio do bloqueio** ao trocar de etapa (o gargalo "sem leito"
   não zera ao passar de "Transferência solicitada" para "Aguardando recurso").
8. **Dados do paciente:** nome, data de nascimento (opcional), CNS (opcional, validado) e
   identificador institucional (opcional). Nada de CPF, endereço, telefone ou diagnóstico.
9. **Sessão:** inatividade 30 min / absoluta 12 h / até 2 sessões simultâneas. Telas de TV
   no corredor (modo painel) precisarão de um perfil de visualização agregada, sem nomes.
