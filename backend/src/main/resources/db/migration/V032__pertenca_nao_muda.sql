-- V032: a "pertença" das linhas que vão ao vendedor nunca muda depois de entregue (Fase 5, tarefa 5.4; ADR-0013,
-- item 5, prova 3).
--
-- O pull do vendedor entrega a linha enquanto ela é dele. Se a coluna que diz de quem é a linha mudasse depois, o
-- cursor já teria passado e o vendedor nunca saberia (ficaria com dado de outro) ou nunca receberia (faltaria dado).
-- A regra 2 do ADR (tabela de saídas por gatilho) só é preciso para colunas que podem mudar. Com os clientes da loja
-- (11/11/2026), nenhuma pode: estes gatilhos GARANTEM isso no banco, e o teste de convenções confere que cada coluna
-- de pertença das sincronizáveis está aqui.
--
--   remessas.vendedor_id      não muda depois do envio (no rascunho a dona ainda pode trocar);
--   remessas.enviada_em       uma vez preenchida, não volta a vazio (a maleta enviada continua enviada);
--   vendas.vendedor_id        nunca muda;
--   itens_remessa.remessa_id e produto_id nunca mudam; o item não é excluído nem volta depois do envio da maleta;
--   produto_codigos.produto_id e produtos_fotos.produto_id nunca mudam.
-- (conferencias, itens_conferencia, itens_venda, pagamentos_cliente e movimentos_remessa já são imutáveis.)

CREATE FUNCTION corona.recusar_mudanca_de_pertenca() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'remessas' THEN
        IF OLD.enviada_em IS NOT NULL AND (NEW.enviada_em IS NULL OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id) THEN
            RAISE EXCEPTION 'A maleta já foi enviada: o vendedor e a data do envio não mudam (ADR-0013, item 5).'
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'vendas' THEN
        IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id THEN
            RAISE EXCEPTION 'O vendedor da venda não muda (ADR-0013, item 5).' USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'itens_remessa' THEN
        IF NEW.remessa_id IS DISTINCT FROM OLD.remessa_id OR NEW.produto_id IS DISTINCT FROM OLD.produto_id THEN
            RAISE EXCEPTION 'A maleta e a peça do item não mudam (ADR-0013, item 5).' USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
           AND EXISTS (SELECT 1 FROM corona.remessas r WHERE r.id = OLD.remessa_id AND r.enviada_em IS NOT NULL) THEN
            RAISE EXCEPTION 'O item da maleta enviada não é excluído (ADR-0013, item 5).' USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME IN ('produto_codigos', 'produtos_fotos') THEN
        IF NEW.produto_id IS DISTINCT FROM OLD.produto_id THEN
            RAISE EXCEPTION 'A peça do código ou da foto não muda (ADR-0013, item 5).' USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION corona.recusar_mudanca_de_pertenca() IS
    'BEFORE UPDATE: a coluna que diz de quem é a linha (para o pull do vendedor) não muda depois de entregue. ADR-0013, item 5; V032.';

CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.remessas
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.vendas
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.itens_remessa
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.produto_codigos
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.produtos_fotos
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();

-- Índices do filtro do vendedor (o dos produtos nos itens, itens_remessa_por_produto, já existe desde a V021).
CREATE INDEX remessas_do_vendedor_enviadas ON corona.remessas (vendedor_id) WHERE enviada_em IS NOT NULL;
CREATE INDEX vendas_do_vendedor ON corona.vendas (vendedor_id);
