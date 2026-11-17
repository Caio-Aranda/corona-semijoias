-- Issue 208 (ADR-0014, itens 8 e 10): a diferença da troca é o total da venda nova menos o valor EM CRÉDITO da devolução de A
-- (o que A abate nas próprias parcelas não chega a B). Só troca a regra de conferir_troca(): o gatilho trocas_coerente e o de
-- imutabilidade continuam como estão. Não corrige dados: o sistema ainda não está em produção (não há troca real gravada).
CREATE OR REPLACE FUNCTION corona.conferir_troca() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    t record;
    d record;
    vo record;
    vn record;
BEGIN
    SELECT * INTO t FROM corona.trocas WHERE id = NEW.id;
    SELECT venda_id, cliente_id, vendedor_id, destino, valor_em_credito INTO d FROM corona.devolucoes WHERE id = t.devolucao_id;
    SELECT cliente_id, vendedor_id INTO vo FROM corona.vendas WHERE id = t.venda_origem_id;
    SELECT cliente_id, vendedor_id, troca_id, total INTO vn FROM corona.vendas WHERE id = t.venda_nova_id;
    IF d.destino IS DISTINCT FROM 'TROCA' OR d.venda_id IS DISTINCT FROM t.venda_origem_id THEN
        RAISE EXCEPTION 'A troca % precisa de uma devolução de troca da venda de origem.', t.id USING ERRCODE = 'check_violation';
    END IF;
    IF vn.troca_id IS DISTINCT FROM t.id THEN
        RAISE EXCEPTION 'A venda nova da troca % não aponta para ela.', t.id USING ERRCODE = 'check_violation';
    END IF;
    IF t.cliente_id IS DISTINCT FROM vo.cliente_id OR t.cliente_id IS DISTINCT FROM vn.cliente_id
            OR t.cliente_id IS DISTINCT FROM d.cliente_id OR t.vendedor_id IS DISTINCT FROM vo.vendedor_id
            OR t.vendedor_id IS DISTINCT FROM vn.vendedor_id OR t.vendedor_id IS DISTINCT FROM d.vendedor_id THEN
        RAISE EXCEPTION 'A troca % precisa ter o mesmo cliente e o mesmo vendedor nas duas vendas e na devolução.', t.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF t.diferenca IS DISTINCT FROM vn.total - d.valor_em_credito THEN
        RAISE EXCEPTION 'A diferença da troca % (R$ %) não confere com a venda nova (R$ %) e o crédito da devolução (R$ %).', t.id,
            t.diferenca, vn.total, d.valor_em_credito USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON COLUMN corona.trocas.diferenca IS
    'Total da venda nova menos o valor em crédito da devolução de A (o que A abate nas próprias parcelas não entra). Positiva: o cliente paga (ou deve) o resto. Negativa: a sobra ficou de crédito.';
