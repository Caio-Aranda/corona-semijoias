-- V025: o sinal da quantidade de movimentos_remessa confere com o tipo (auditoria de 11/11/2026, achado B7; ADR-0010, item 5;
-- mesma ideia do CHECK do estoque central, ADR-0008, item 2).
--
-- O que sai da maleta é negativo (VENDA, TROCA_SAIDA, PERDA, AVARIA, DEVOLUCAO_DONA); o que volta a ela é positivo
-- (ESTORNO_VENDA, DEVOLUCAO_CLIENTE, TROCA_ENTRADA). AJUSTE pode ter qualquer sinal. Um estorno (estorna_id preenchido)
-- tem o sinal oposto ao do movimento estornado, então fora ESTORNO_VENDA (sempre positivo) ele pode ter qualquer sinal;
-- o saldo continua protegido pelo gatilho da V024.
--
-- Os dados já gravados foram conferidos: se algum violar a regra, esta migração para com a lista abaixo em vez de
-- aplicar o CHECK no escuro.

DO $$
DECLARE
    ruins bigint;
BEGIN
    SELECT count(*) INTO ruins FROM corona.movimentos_remessa
     WHERE NOT (CASE
                    WHEN tipo = 'ESTORNO_VENDA' THEN quantidade > 0
                    WHEN estorna_id IS NOT NULL OR tipo = 'AJUSTE' THEN true
                    WHEN tipo IN ('VENDA', 'TROCA_SAIDA', 'PERDA', 'AVARIA', 'DEVOLUCAO_DONA') THEN quantidade < 0
                    ELSE quantidade > 0
                END);
    IF ruins > 0 THEN
        RAISE EXCEPTION 'Há % movimento(s) de maleta com sinal incompatível com o tipo; corrija antes de aplicar a V025.', ruins;
    END IF;
END
$$;

ALTER TABLE corona.movimentos_remessa ADD CONSTRAINT movimentos_remessa_sinal_pelo_tipo CHECK (
    CASE
        WHEN tipo = 'ESTORNO_VENDA' THEN quantidade > 0
        WHEN estorna_id IS NOT NULL OR tipo = 'AJUSTE' THEN true
        WHEN tipo IN ('VENDA', 'TROCA_SAIDA', 'PERDA', 'AVARIA', 'DEVOLUCAO_DONA') THEN quantidade < 0
        ELSE quantidade > 0
    END);
