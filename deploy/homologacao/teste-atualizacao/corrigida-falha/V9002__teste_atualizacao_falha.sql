-- SOMENTE TESTE (CI de homologação): "correção" que ainda falha, SEM novo avanço (a V9001 já aplicada é
-- idêntica; esta V9002 falha e é desfeita). A aplicação deve continuar parada.
CREATE TABLE fluxo.teste_atualizacao_9002 (id integer PRIMARY KEY);
SELECT 2 / 0;
