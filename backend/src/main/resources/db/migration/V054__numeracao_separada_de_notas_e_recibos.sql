-- V054: numeração separada de notas e recibos (Leva 9, tarefa 9.8; ADR-0016, item 3 e D2; ESPECIFICACAO §23.2).
--
-- Até a V053 o número semana-sequência era único entre notas e recibos juntos. Agora a nota e o recibo têm contador próprio
-- na mesma semana (o recibo aparece com o prefixo "R " na tela): o que era único junto continua único por tipo. Nenhum
-- número já gravado muda. A V050 não se toca.

DROP INDEX corona.numeros_da_semana_unico;
CREATE UNIQUE INDEX numeros_da_semana_unico_por_tipo ON corona.numeros_da_semana (vendedor_id, semana_id, tipo, sequencia);
COMMENT ON TABLE corona.numeros_da_semana IS
    'Um número semana-sequência por representante e por tipo: notas e recibos têm contador próprio na semana (ADR-0016, item 3 e D2). Só do servidor.';
