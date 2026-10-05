# Política de segurança

O Fluxo Saúde trata dados de saúde (LGPD, art. 11). Reporte vulnerabilidades **em privado**
ao responsável pelo projeto (não abra issue pública).

## Regras para contribuições

- Consultas SQL **sempre parametrizadas**. Concatenação de entrada em SQL é recusada em revisão
  (o RLS não protege contra injeção — ver ADR-0004).
- Nenhum segredo no repositório: senhas e chaves só por variável de ambiente / cofre.
- Nunca registrar em log nome de paciente, CNS, textos livres ou tokens de sessão.
- Toda nova tabela com `unidade_id` precisa de política RLS e trigger de auditoria
  (verificado em `BancoDeDadosIT` e nos testes SQL).
- Regras de negócio novas: implementar no domínio **e** no banco, com teste nos dois.
- Dependências: atualizações do Dependabot revisadas semanalmente.
