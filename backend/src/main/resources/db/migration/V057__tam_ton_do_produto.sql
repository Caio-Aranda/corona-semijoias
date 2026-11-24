-- V057: Tam./Ton. do produto (Leva 9, tarefa 9.8, item 5; ESPECIFICACAO §23.11).
--
-- Campo opcional e curto (ex.: "17", "Dourado"): o tamanho ou o tom da peça, que sai nas notas e nos relatórios do consignado.
-- Vai ao Corona Vendas só no pull de formato 2 (o formato 1 não leva a coluna). Não é dinheiro nem custo.

ALTER TABLE corona.produtos ADD COLUMN tam_ton text
    CONSTRAINT produtos_tam_ton_curto CHECK (tam_ton IS NULL OR (btrim(tam_ton) <> '' AND length(tam_ton) <= 30));
COMMENT ON COLUMN corona.produtos.tam_ton IS 'Tamanho ou tom da peça (texto curto, opcional). Sai no pull de formato 2.';
