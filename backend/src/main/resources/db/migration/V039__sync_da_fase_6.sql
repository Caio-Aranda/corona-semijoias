-- V039: push e pull das tabelas da Fase 6 (tarefa 6.7; ADR-0014, item 9, linha de 13/11/2026; ESPECIFICACAO §7.3 e §9;
-- plano aprovado no PR #131, dúvidas S1 a S12 pelo padrão).
--
-- A venda que chega pelo envio do computador com crédito do cliente acima do saldo ENTRA (o que aconteceu no balcão
-- não se recusa): o pagamento em crédito fica limitado ao saldo que havia (nenhuma linha quando era zero), o resto fica
-- a receber do cliente na venda, e nasce uma linha creditos_sem_saldo EM_REVISAO para a dona decidir. A linha nunca
-- mexe em dinheiro: só a situação e a revisão mudam. Dinheiro em NUMERIC(14,2), tudo em Real (ADR-0006); datas em
-- UTC. Nenhuma migração publicada é editada: a função de pertença da V038 é recriada aqui, inteira, com o ramo novo.
--
-- Índices do filtro do vendedor: os das tabelas da Fase 6 já existem (devolucoes_por_vendedor, itens_devolucao_peca_unica
-- começa por devolucao_id, autorizacoes_por_vendedor, itens_autorizacao_peca_unica começa por autorizacao_id,
-- trocas_por_vendedor, creditos_cliente_por_vendedor, usos_credito_por_credito); só a tabela nova precisa dos dela.

-- ---------------------------------------------------------------------------------------------
-- creditos_sem_saldo: a venda paga pelo envio com crédito acima do saldo (sincronizável; só a revisão muda).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.creditos_sem_saldo (
    -- Do servidor. O pagamento em crédito que o computador mandou fica em pagamento_id (o pagamento gravado, quando o
    -- crédito ainda pagou alguma coisa, tem este id); um id próprio evita o mesmo UUID em duas tabelas sincronizáveis.
    id uuid PRIMARY KEY,
    pagamento_id uuid NOT NULL CONSTRAINT creditos_sem_saldo_pagamento_unico UNIQUE,
    venda_id uuid NOT NULL REFERENCES corona.vendas (id),
    credito_id uuid NOT NULL REFERENCES corona.creditos_cliente (id),
    -- O que o computador pediu em crédito e o que o crédito ainda tinha (o que ficou pago).
    valor_pedido numeric(14, 2) NOT NULL CONSTRAINT creditos_sem_saldo_pedido_positivo CHECK (valor_pedido > 0),
    valor_usado numeric(14, 2) NOT NULL
        CONSTRAINT creditos_sem_saldo_usado_abaixo_do_pedido CHECK (valor_usado >= 0 AND valor_usado < valor_pedido),
    situacao text NOT NULL DEFAULT 'EM_REVISAO'
        CONSTRAINT creditos_sem_saldo_situacao_conhecida CHECK (situacao IN ('EM_REVISAO', 'MANTIDA', 'NAO_ACEITA')),
    registrada_em timestamptz NOT NULL,
    -- A revisão da dona: tudo ou nada; a observação é obrigatória no "não aceita".
    revisada_em timestamptz,
    revisada_por uuid REFERENCES corona.usuarios (id),
    observacao_revisao text CONSTRAINT creditos_sem_saldo_observacao_preenchida
        CHECK (observacao_revisao IS NULL OR (btrim(observacao_revisao) <> '' AND length(observacao_revisao) <= 400)),
    CONSTRAINT creditos_sem_saldo_revisao_completa CHECK (
        (situacao = 'EM_REVISAO' AND revisada_em IS NULL AND revisada_por IS NULL AND observacao_revisao IS NULL)
        OR (situacao = 'MANTIDA' AND revisada_em IS NOT NULL AND revisada_por IS NOT NULL)
        OR (situacao = 'NAO_ACEITA' AND revisada_em IS NOT NULL AND revisada_por IS NOT NULL AND observacao_revisao IS NOT NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.creditos_sem_saldo');
COMMENT ON TABLE corona.creditos_sem_saldo IS
    'Venda paga pelo envio do computador com crédito do cliente acima do saldo (ADR-0014, item 9, linha de 13/11/2026): a venda entrou, o crédito pagou só o saldo e o resto ficou a receber. A dona mantém ou não aceita; a linha nunca mexe em dinheiro.';
COMMENT ON COLUMN corona.creditos_sem_saldo.situacao IS
    'EM_REVISAO -> MANTIDA (o cliente continua devendo) | NAO_ACEITA (pendência do vendedor, cobrada no acerto), sem volta.';
CREATE INDEX creditos_sem_saldo_por_venda ON corona.creditos_sem_saldo (venda_id);
CREATE INDEX creditos_sem_saldo_por_credito ON corona.creditos_sem_saldo (credito_id);
CREATE INDEX creditos_sem_saldo_para_revisar ON corona.creditos_sem_saldo (registrada_em) WHERE situacao = 'EM_REVISAO';

-- A linha nasce em revisão, com a venda e o crédito do mesmo vendedor e do mesmo cliente; com valor usado, existe o
-- pagamento CREDITO pagamento_id, deste crédito, desta venda e com esse valor; sem valor usado, não existe pagamento
-- com esse id (o crédito não pagou nada).
CREATE FUNCTION corona.conferir_credito_sem_saldo() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v record;
    c record;
    p record;
BEGIN
    IF NEW.situacao IS DISTINCT FROM 'EM_REVISAO' THEN
        RAISE EXCEPTION 'A venda com crédito sem saldo % nasce para revisão da dona.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    SELECT vendedor_id, cliente_id INTO v FROM corona.vendas WHERE id = NEW.venda_id;
    SELECT vendedor_id, cliente_id INTO c FROM corona.creditos_cliente WHERE id = NEW.credito_id;
    IF v.vendedor_id IS DISTINCT FROM c.vendedor_id OR v.cliente_id IS DISTINCT FROM c.cliente_id THEN
        RAISE EXCEPTION 'A venda % e o crédito % precisam ser do mesmo vendedor e do mesmo cliente.', NEW.venda_id, NEW.credito_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    SELECT venda_id, tipo, forma, valor, credito_id INTO p FROM corona.pagamentos_cliente WHERE id = NEW.pagamento_id;
    IF NEW.valor_usado > 0 THEN
        IF p.venda_id IS DISTINCT FROM NEW.venda_id OR p.tipo IS DISTINCT FROM 'PAGAMENTO' OR p.forma IS DISTINCT FROM 'CREDITO'
                OR p.credito_id IS DISTINCT FROM NEW.credito_id OR p.valor IS DISTINCT FROM NEW.valor_usado THEN
            RAISE EXCEPTION 'O pagamento % precisa ser o pagamento em crédito de R$ % desta venda e deste crédito.', NEW.pagamento_id,
                NEW.valor_usado USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF FOUND THEN
        RAISE EXCEPTION 'O crédito não pagou nada: não pode existir o pagamento %.', NEW.pagamento_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER creditos_sem_saldo_nasce_conferido BEFORE INSERT ON corona.creditos_sem_saldo
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_credito_sem_saldo();

-- Nada muda além da situação (só EM_REVISAO -> MANTIDA | NAO_ACEITA) e dos dados da revisão (junto com ela).
-- deleted_at nunca.
CREATE FUNCTION corona.proteger_credito_sem_saldo() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.venda_id IS DISTINCT FROM OLD.venda_id OR NEW.credito_id IS DISTINCT FROM OLD.credito_id
            OR NEW.pagamento_id IS DISTINCT FROM OLD.pagamento_id OR NEW.valor_pedido IS DISTINCT FROM OLD.valor_pedido OR NEW.valor_usado IS DISTINCT FROM OLD.valor_usado
            OR NEW.registrada_em IS DISTINCT FROM OLD.registrada_em OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'A venda com crédito sem saldo % não pode ser alterada.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.situacao IS DISTINCT FROM OLD.situacao
       AND NOT (OLD.situacao = 'EM_REVISAO' AND NEW.situacao IN ('MANTIDA', 'NAO_ACEITA')) THEN
        RAISE EXCEPTION 'A venda com crédito sem saldo % não pode passar de % para %.', OLD.id, OLD.situacao, NEW.situacao
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.revisada_em IS DISTINCT FROM OLD.revisada_em OR NEW.revisada_por IS DISTINCT FROM OLD.revisada_por
            OR NEW.observacao_revisao IS DISTINCT FROM OLD.observacao_revisao)
       AND NOT (OLD.situacao = 'EM_REVISAO' AND NEW.situacao IN ('MANTIDA', 'NAO_ACEITA')) THEN
        RAISE EXCEPTION 'A revisão da venda com crédito sem saldo % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER creditos_sem_saldo_conteudo_imutavel BEFORE UPDATE ON corona.creditos_sem_saldo
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_credito_sem_saldo();

-- ---------------------------------------------------------------------------------------------
-- A pertença de creditos_sem_saldo não muda (ADR-0013, item 5; RegrasDoVendedorTest): a função da V038, inteira, com o
-- ramo novo.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION corona.recusar_mudanca_de_pertenca() RETURNS trigger
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
    ELSIF TG_TABLE_NAME = 'devolucoes' THEN
        IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.venda_id IS DISTINCT FROM OLD.venda_id
                OR NEW.remessa_id IS DISTINCT FROM OLD.remessa_id THEN
            RAISE EXCEPTION 'O vendedor, a venda e a maleta da devolução não mudam (ADR-0013, item 5).'
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'autorizacoes_excecao' THEN
        IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.venda_id IS DISTINCT FROM OLD.venda_id THEN
            RAISE EXCEPTION 'O vendedor e a venda do pedido de autorização não mudam (ADR-0013, item 5).'
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'trocas' THEN
        IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
                OR NEW.venda_origem_id IS DISTINCT FROM OLD.venda_origem_id OR NEW.devolucao_id IS DISTINCT FROM OLD.devolucao_id
                OR NEW.venda_nova_id IS DISTINCT FROM OLD.venda_nova_id THEN
            RAISE EXCEPTION 'O vendedor, o cliente, as vendas e a devolução da troca não mudam (ADR-0013, item 5).'
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'creditos_cliente' THEN
        IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id THEN
            RAISE EXCEPTION 'O vendedor e o cliente do crédito não mudam (ADR-0013, item 5).'
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'creditos_sem_saldo' THEN
        IF NEW.venda_id IS DISTINCT FROM OLD.venda_id OR NEW.credito_id IS DISTINCT FROM OLD.credito_id THEN
            RAISE EXCEPTION 'A venda e o crédito da venda com crédito sem saldo não mudam (ADR-0013, item 5).'
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.creditos_sem_saldo
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();

REVOKE ALL ON corona.creditos_sem_saldo FROM PUBLIC;
