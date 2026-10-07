-- SOMENTE TESTE: migração artificial que FALHA de propósito (divisão por zero) depois de criar algo,
-- para provar que só ELA é desfeita: a V9001, anterior, continua aplicada.
CREATE TABLE fluxo.teste_atualizacao_9002 (id integer PRIMARY KEY);
SELECT 1 / 0;
