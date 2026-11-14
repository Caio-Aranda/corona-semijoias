-- V041: contas a receber do cliente, a venda a prazo em parcelas (Fase 7, tarefa 7.3; ADR-0012, itens 2 e 7; ESPECIFICACAO
-- §7.2, §9 e §10; plano aprovado no PR #162).
--
-- A venda a prazo ganha parcelas (contas_receber) calculadas só pelo servidor; o recebimento posterior do cliente é um
-- pagamentos_cliente novo (posterior = true) que o servidor distribui nas parcelas por vencimento, gravando baixas
-- (baixas_receber, imutáveis). Estornar o recebimento é outro pagamento (ESTORNO_PAGAMENTO, negativo) e baixas negativas.
-- Os atalhos da parcela (valor_pago, valor_cancelado, status) só mudam pelo serviço, na transação da baixa, e o PRÓPRIO
-- BANCO confere que batem com a soma das baixas. "Vencida" nunca é gravada: é calculada pela data de hoje em Brasília.
-- Dinheiro em NUMERIC(14,2), tudo em Real (ADR-0006); datas em UTC; vencimento é o dia do calendário de Brasília.
-- Nenhuma migração publicada é editada: os CHECK de pagamentos_cliente são recriados (como na V036) e a função de
-- pertença da V039 é recriada inteira, com o ramo novo.

-- ---------------------------------------------------------------------------------------------
-- pagamentos_cliente: o recebimento posterior e o estorno dele.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.pagamentos_cliente ADD COLUMN posterior boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN corona.pagamentos_cliente.posterior IS
    'Recebimento depois da venda (§7.2: registro novo), distribuído nas parcelas da venda. O pagamento da hora da venda é false.';
ALTER TABLE corona.pagamentos_cliente DROP CONSTRAINT pagamentos_cliente_tipo_conhecido;
ALTER TABLE corona.pagamentos_cliente ADD CONSTRAINT pagamentos_cliente_tipo_conhecido
    CHECK (tipo IN ('PAGAMENTO', 'REEMBOLSO', 'ESTORNO_REEMBOLSO', 'ESTORNO_PAGAMENTO'));
ALTER TABLE corona.pagamentos_cliente DROP CONSTRAINT pagamentos_cliente_sinal;
ALTER TABLE corona.pagamentos_cliente ADD CONSTRAINT pagamentos_cliente_sinal CHECK (
    (tipo = 'PAGAMENTO' AND valor > 0 AND estorna_id IS NULL)
    OR (tipo = 'REEMBOLSO' AND valor < 0 AND estorna_id IS NULL)
    OR (tipo = 'ESTORNO_REEMBOLSO' AND valor > 0 AND estorna_id IS NOT NULL AND devolucao_id IS NOT NULL)
    OR (tipo = 'ESTORNO_PAGAMENTO' AND valor < 0 AND estorna_id IS NOT NULL AND devolucao_id IS NULL AND posterior));
-- O recebimento posterior só entra em dinheiro ou transferência (o crédito é para compra nova, §7.3).
ALTER TABLE corona.pagamentos_cliente ADD CONSTRAINT pagamentos_cliente_posterior_so_em_dinheiro_ou_pix
    CHECK (NOT posterior OR (tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO') AND forma IN ('DINHEIRO', 'PIX')));

-- O estorno do recebimento desfaz um recebimento posterior da mesma venda, pelo mesmo valor com sinal oposto.
CREATE FUNCTION corona.conferir_estorno_de_pagamento() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    o record;
BEGIN
    IF NEW.tipo <> 'ESTORNO_PAGAMENTO' THEN
        RETURN NEW;
    END IF;
    SELECT venda_id, tipo, posterior, valor INTO o FROM corona.pagamentos_cliente WHERE id = NEW.estorna_id;
    IF NOT FOUND OR o.tipo <> 'PAGAMENTO' OR NOT o.posterior OR o.venda_id <> NEW.venda_id OR o.valor <> -NEW.valor THEN
        RAISE EXCEPTION 'O estorno % precisa desfazer um recebimento posterior desta venda, pelo mesmo valor.', NEW.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER pagamentos_cliente_estorno_conferido BEFORE INSERT ON corona.pagamentos_cliente
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_estorno_de_pagamento();

-- ---------------------------------------------------------------------------------------------
-- contas_receber: a parcela (sincronizável; só os atalhos mudam).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.contas_receber (
    -- Do servidor (v7): a parcela nasce como efeito da venda.
    id uuid PRIMARY KEY,
    -- Nível 1 (cliente deve ao vendedor). O nível 2 (vendedor deve à loja) chega na 7.5 e na 7.6, por migração nova.
    devedor_tipo text NOT NULL CONSTRAINT contas_receber_devedor_conhecido CHECK (devedor_tipo IN ('CLIENTE')),
    cliente_id uuid REFERENCES corona.clientes (id),
    vendedor_id uuid REFERENCES corona.vendedores (id),
    CONSTRAINT contas_receber_um_devedor CHECK (
        (devedor_tipo = 'CLIENTE' AND cliente_id IS NOT NULL AND vendedor_id IS NULL)
        OR (devedor_tipo = 'VENDEDOR' AND vendedor_id IS NOT NULL AND cliente_id IS NULL)),
    origem_tipo text NOT NULL CONSTRAINT contas_receber_origem_conhecida CHECK (origem_tipo IN ('VENDA')),
    origem_id uuid NOT NULL,
    venda_id uuid REFERENCES corona.vendas (id),
    CONSTRAINT contas_receber_venda_da_origem CHECK (origem_tipo <> 'VENDA' OR (venda_id IS NOT NULL AND venda_id = origem_id)),
    parcela smallint NOT NULL,
    total_parcelas smallint NOT NULL,
    CONSTRAINT contas_receber_parcela_na_faixa CHECK (parcela >= 1 AND parcela <= total_parcelas AND total_parcelas <= 24),
    vencimento date NOT NULL,
    valor numeric(14, 2) NOT NULL CONSTRAINT contas_receber_valor_positivo CHECK (valor > 0),
    -- Atalhos: só o ServicoDeContasAReceber os muda, na transação da baixa, e o gatilho confere com a soma das baixas.
    valor_pago numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT contas_receber_pago_nao_negativo CHECK (valor_pago >= 0),
    valor_cancelado numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT contas_receber_cancelado_nao_negativo CHECK (valor_cancelado >= 0),
    CONSTRAINT contas_receber_pago_e_cancelado_cabem CHECK (valor_pago + valor_cancelado <= valor),
    -- VENCIDA nunca é gravada: é calculada pela data de hoje em Brasília.
    status text NOT NULL DEFAULT 'ABERTA'
        CONSTRAINT contas_receber_status_conhecido CHECK (status IN ('ABERTA', 'PARCIAL', 'PAGA', 'CANCELADA')),
    CONSTRAINT contas_receber_status_confere CHECK (
        (status = 'ABERTA' AND valor_pago = 0 AND valor_cancelado = 0)
        OR (status = 'PARCIAL' AND valor_pago > 0 AND valor_pago < valor AND valor_cancelado = 0)
        OR (status = 'PAGA' AND valor_pago = valor AND valor_cancelado = 0)
        OR (status = 'CANCELADA' AND valor_cancelado > 0 AND valor_pago + valor_cancelado = valor))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.contas_receber');
COMMENT ON TABLE corona.contas_receber IS
    'Parcela a receber (ADR-0012, item 7). O conteúdo nunca muda; só valor_pago, valor_cancelado e status, pelo serviço, e o gatilho confere com baixas_receber.';
COMMENT ON COLUMN corona.contas_receber.vencimento IS 'Dia do calendário de Brasília.';
COMMENT ON COLUMN corona.contas_receber.status IS
    'ABERTA | PARCIAL | PAGA | CANCELADA. "Vencida" é calculada pela data de hoje em Brasília, nunca gravada.';
CREATE UNIQUE INDEX contas_receber_parcela_unica ON corona.contas_receber (venda_id, parcela) WHERE venda_id IS NOT NULL;
CREATE INDEX contas_receber_por_cliente ON corona.contas_receber (cliente_id) WHERE cliente_id IS NOT NULL;
CREATE INDEX contas_receber_em_aberto ON corona.contas_receber (status, vencimento) WHERE status IN ('ABERTA', 'PARCIAL');

-- Nasce em aberto, de uma venda concluída e do mesmo cliente dela.
CREATE FUNCTION corona.conferir_conta_a_receber() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v record;
BEGIN
    IF NEW.status <> 'ABERTA' THEN
        RAISE EXCEPTION 'A conta a receber % nasce em aberto.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.origem_tipo = 'VENDA' THEN
        SELECT status, cliente_id INTO v FROM corona.vendas WHERE id = NEW.venda_id;
        IF NOT FOUND OR v.status <> 'CONCLUIDA' OR v.cliente_id IS DISTINCT FROM NEW.cliente_id THEN
            RAISE EXCEPTION 'A conta a receber % precisa ser de uma venda concluída e do cliente dela.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER contas_receber_nasce_conferida BEFORE INSERT ON corona.contas_receber
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_conta_a_receber();

-- O conteúdo é imutável; os atalhos só mudam para a soma das baixas (feitas antes, na mesma transação).
CREATE FUNCTION corona.proteger_conta_a_receber() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    pago numeric(14, 2);
    cancelado numeric(14, 2);
BEGIN
    IF NEW.devedor_tipo IS DISTINCT FROM OLD.devedor_tipo OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
            OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.origem_tipo IS DISTINCT FROM OLD.origem_tipo
            OR NEW.origem_id IS DISTINCT FROM OLD.origem_id OR NEW.venda_id IS DISTINCT FROM OLD.venda_id
            OR NEW.parcela IS DISTINCT FROM OLD.parcela OR NEW.total_parcelas IS DISTINCT FROM OLD.total_parcelas
            OR NEW.vencimento IS DISTINCT FROM OLD.vencimento OR NEW.valor IS DISTINCT FROM OLD.valor
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'A conta a receber % não pode ser alterada: a correção é feita com baixa e estorno.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.valor_pago IS DISTINCT FROM OLD.valor_pago OR NEW.valor_cancelado IS DISTINCT FROM OLD.valor_cancelado THEN
        SELECT coalesce(sum(valor) FILTER (WHERE tipo = 'PAGAMENTO'), 0), coalesce(sum(valor) FILTER (WHERE tipo = 'CANCELAMENTO'), 0)
          INTO pago, cancelado FROM corona.baixas_receber WHERE conta_id = OLD.id;
        IF NEW.valor_pago <> pago OR NEW.valor_cancelado <> cancelado THEN
            RAISE EXCEPTION 'Os valores pago e cancelado da conta % precisam bater com a soma das baixas.', OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER contas_receber_conteudo_imutavel BEFORE UPDATE ON corona.contas_receber
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_conta_a_receber();

-- ---------------------------------------------------------------------------------------------
-- baixas_receber: o que baixou a parcela (imutável; o estorno é registro negativo).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.baixas_receber (
    -- Do servidor (v7): a baixa é efeito do recebimento ou do estorno da venda.
    id uuid PRIMARY KEY,
    conta_id uuid NOT NULL REFERENCES corona.contas_receber (id),
    tipo text NOT NULL CONSTRAINT baixas_receber_tipo_conhecido CHECK (tipo IN ('PAGAMENTO', 'CANCELAMENTO')),
    valor numeric(14, 2) NOT NULL,
    baixada_em timestamptz NOT NULL DEFAULT now(),
    pagamento_cliente_id uuid REFERENCES corona.pagamentos_cliente (id),
    -- A devolução de venda a prazo (tarefa 7.3b) amplia este CHECK por migração nova.
    motivo_cancelamento text CONSTRAINT baixas_receber_motivo_conhecido CHECK (motivo_cancelamento IN ('ESTORNO_VENDA')),
    estorna_id uuid REFERENCES corona.baixas_receber (id),
    CONSTRAINT baixas_receber_forma CHECK (
        (tipo = 'PAGAMENTO' AND pagamento_cliente_id IS NOT NULL AND motivo_cancelamento IS NULL
            AND ((estorna_id IS NULL AND valor > 0) OR (estorna_id IS NOT NULL AND valor < 0)))
        OR (tipo = 'CANCELAMENTO' AND pagamento_cliente_id IS NULL AND motivo_cancelamento IS NOT NULL
            AND estorna_id IS NULL AND valor > 0))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.baixas_receber', imutavel => true);
COMMENT ON TABLE corona.baixas_receber IS
    'Baixa de parcela (ADR-0012, item 7): PAGAMENTO (do recebimento posterior; o estorno é a baixa negativa) ou CANCELAMENTO (do estorno da venda). Imutável.';
CREATE UNIQUE INDEX baixas_receber_estorno_unico ON corona.baixas_receber (estorna_id) WHERE estorna_id IS NOT NULL;
CREATE INDEX baixas_receber_por_conta ON corona.baixas_receber (conta_id);
CREATE INDEX baixas_receber_por_pagamento ON corona.baixas_receber (pagamento_cliente_id) WHERE pagamento_cliente_id IS NOT NULL;

-- A baixa é do recebimento posterior da mesma venda, cabe no que sobra da parcela e no que sobra do recebimento; o estorno
-- desfaz uma baixa PAGAMENTO da mesma conta, do mesmo valor, ligada ao ESTORNO_PAGAMENTO do recebimento original.
CREATE FUNCTION corona.conferir_baixa_receber() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c record;
    p record;
    o record;
    pago numeric(14, 2);
    cancelado numeric(14, 2);
    distribuido numeric(14, 2);
BEGIN
    SELECT venda_id, valor INTO c FROM corona.contas_receber WHERE id = NEW.conta_id;
    IF NEW.tipo = 'PAGAMENTO' THEN
        SELECT venda_id, tipo, posterior, valor, estorna_id INTO p FROM corona.pagamentos_cliente WHERE id = NEW.pagamento_cliente_id;
        IF NOT FOUND OR NOT p.posterior OR p.venda_id IS DISTINCT FROM c.venda_id THEN
            RAISE EXCEPTION 'A baixa % precisa ser de um recebimento posterior da mesma venda.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.estorna_id IS NULL THEN
            IF p.tipo <> 'PAGAMENTO' THEN
                RAISE EXCEPTION 'A baixa % precisa ser de um recebimento, não de um estorno.', NEW.id USING ERRCODE = 'restrict_violation';
            END IF;
            SELECT coalesce(sum(valor), 0) INTO distribuido FROM corona.baixas_receber
             WHERE pagamento_cliente_id = NEW.pagamento_cliente_id AND estorna_id IS NULL;
            IF distribuido + NEW.valor > p.valor THEN
                RAISE EXCEPTION 'As baixas do recebimento % passam do valor recebido.', NEW.pagamento_cliente_id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        ELSE
            SELECT conta_id, tipo, valor, estorna_id, pagamento_cliente_id INTO o FROM corona.baixas_receber WHERE id = NEW.estorna_id;
            IF NOT FOUND OR o.tipo <> 'PAGAMENTO' OR o.estorna_id IS NOT NULL OR o.valor <= 0 OR o.conta_id <> NEW.conta_id
                    OR o.valor <> -NEW.valor OR p.tipo <> 'ESTORNO_PAGAMENTO' OR p.estorna_id IS DISTINCT FROM o.pagamento_cliente_id THEN
                RAISE EXCEPTION 'O estorno da baixa % precisa desfazer uma baixa de pagamento desta conta, pelo mesmo valor.', NEW.id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;
    END IF;
    -- Nunca baixar mais do que a parcela vale, nem desfazer mais do que foi baixado.
    SELECT coalesce(sum(valor) FILTER (WHERE tipo = 'PAGAMENTO'), 0), coalesce(sum(valor) FILTER (WHERE tipo = 'CANCELAMENTO'), 0)
      INTO pago, cancelado FROM corona.baixas_receber WHERE conta_id = NEW.conta_id;
    IF NEW.tipo = 'PAGAMENTO' THEN
        pago := pago + NEW.valor;
    ELSE
        cancelado := cancelado + NEW.valor;
    END IF;
    IF pago < 0 OR pago + cancelado > c.valor THEN
        RAISE EXCEPTION 'A baixa % passa do valor da conta a receber.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER baixas_receber_nasce_conferida BEFORE INSERT ON corona.baixas_receber
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_baixa_receber();

-- ---------------------------------------------------------------------------------------------
-- A pertença de contas_receber não muda (ADR-0013, item 5; RegrasDoVendedorTest): a função da V039, inteira, com o ramo novo.
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
    ELSIF TG_TABLE_NAME = 'contas_receber' THEN
        IF NEW.venda_id IS DISTINCT FROM OLD.venda_id OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
                OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id THEN
            RAISE EXCEPTION 'O cliente, o vendedor e a venda da conta a receber não mudam (ADR-0013, item 5).'
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.contas_receber
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();

-- ---------------------------------------------------------------------------------------------
-- A venda a prazo já gravada (só existe em teste; não há produção): uma parcela única no dia da venda em Brasília
-- (ADR-0012, item 7), com o que falta pagar (total menos os pagamentos).
-- ---------------------------------------------------------------------------------------------
INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela, total_parcelas,
                                   vencimento, valor)
SELECT uuidv7(), 'CLIENTE', v.cliente_id, 'VENDA', v.id, v.id, 1, 1,
       (v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date,
       v.total - coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p WHERE p.venda_id = v.id AND p.tipo = 'PAGAMENTO'), 0)
  FROM corona.vendas v
 WHERE v.status = 'CONCLUIDA'
   AND v.total - coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p WHERE p.venda_id = v.id AND p.tipo = 'PAGAMENTO'), 0) > 0;

REVOKE ALL ON corona.contas_receber, corona.baixas_receber FROM PUBLIC;
