-- V022: vendas, itens e pagamentos do cliente (Fase 4, tarefa 4.2; ESPECIFICACAO §7.2 e §9; ADR-0011).
--
-- A venda sai da maleta do vendedor (movimentos_remessa, tipo VENDA): o saldo da maleta nunca é um número
-- guardado. Venda, itens e pagamentos são imutáveis: corrigir = estornar (a venda vira ESTORNADA e nascem
-- movimentos e reembolso novos). Dinheiro em NUMERIC(14,2); tudo em Real (ADR-0006); datas em UTC.
-- Os ids vêm do aplicativo (idempotência); os itens e o reembolso nascem no servidor.

CREATE TABLE corona.vendas (
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    cliente_id uuid NOT NULL REFERENCES corona.clientes (id),
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    status text NOT NULL DEFAULT 'CONCLUIDA' CONSTRAINT vendas_status_conhecido CHECK (status IN ('CONCLUIDA', 'ESTORNADA')),
    -- Calculados pelo servidor (o app nunca manda preço nem total).
    subtotal numeric(14, 2) NOT NULL CONSTRAINT vendas_subtotal_nao_negativo CHECK (subtotal >= 0),
    desconto numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT vendas_desconto_nao_negativo CHECK (desconto >= 0),
    total numeric(14, 2) NOT NULL CONSTRAINT vendas_total_nao_negativo CHECK (total >= 0),
    CONSTRAINT vendas_total_confere CHECK (total = subtotal - desconto),
    -- Cotação do dia da venda, só para mostrar Dólar e Guarani (ADR-0006). Vazio se não havia cotação.
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    vendida_em timestamptz NOT NULL DEFAULT now(),
    -- Estorno (a venda inteira; ADR-0011, item 8).
    estorno_id uuid,
    estornada_em timestamptz,
    estornada_por uuid REFERENCES corona.usuarios (id),
    motivo_estorno text CONSTRAINT vendas_motivo_preenchido CHECK (motivo_estorno IS NULL OR btrim(motivo_estorno) <> ''),
    CONSTRAINT vendas_estorno_completo CHECK (
        (status = 'CONCLUIDA' AND estorno_id IS NULL AND estornada_em IS NULL AND motivo_estorno IS NULL)
        OR (status = 'ESTORNADA' AND estorno_id IS NOT NULL AND estornada_em IS NOT NULL AND motivo_estorno IS NOT NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.vendas');
COMMENT ON TABLE corona.vendas IS
    'Venda do vendedor (ADR-0011). Imutável: só o estorno muda o status (gatilho); o resto é correção por registro novo.';
CREATE INDEX vendas_por_vendedor ON corona.vendas (vendedor_id, vendida_em DESC) WHERE deleted_at IS NULL;
CREATE INDEX vendas_por_cliente ON corona.vendas (cliente_id) WHERE deleted_at IS NULL;
CREATE INDEX vendas_por_remessa ON corona.vendas (remessa_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX vendas_estorno_unico ON corona.vendas (estorno_id) WHERE estorno_id IS NOT NULL;

CREATE FUNCTION corona.proteger_venda() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
            OR NEW.remessa_id IS DISTINCT FROM OLD.remessa_id OR NEW.subtotal IS DISTINCT FROM OLD.subtotal
            OR NEW.desconto IS DISTINCT FROM OLD.desconto OR NEW.total IS DISTINCT FROM OLD.total
            OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id OR NEW.vendida_em IS DISTINCT FROM OLD.vendida_em
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'A venda % não pode ser alterada: a correção é feita com estorno.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'ESTORNADA' AND (NEW.status IS DISTINCT FROM OLD.status OR NEW.estorno_id IS DISTINCT FROM OLD.estorno_id
            OR NEW.estornada_em IS DISTINCT FROM OLD.estornada_em OR NEW.motivo_estorno IS DISTINCT FROM OLD.motivo_estorno) THEN
        RAISE EXCEPTION 'A venda % já foi estornada.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vendas_conteudo_imutavel BEFORE UPDATE ON corona.vendas
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_venda();

CREATE TABLE corona.itens_venda (
    id uuid PRIMARY KEY,
    venda_id uuid NOT NULL REFERENCES corona.vendas (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    quantidade integer NOT NULL CONSTRAINT itens_venda_quantidade_positiva CHECK (quantidade > 0),
    -- O preco_remessa da maleta (preço congelado, ADR-0010, item 6). Nunca vem do app.
    preco_unitario numeric(14, 2) NOT NULL CONSTRAINT itens_venda_preco_nao_negativo CHECK (preco_unitario >= 0),
    -- Desconto da linha, em Real (valor, não porcentagem; ADR-0011, item 5).
    desconto numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT itens_venda_desconto_nao_negativo CHECK (desconto >= 0),
    CONSTRAINT itens_venda_desconto_cabe CHECK (desconto <= preco_unitario * quantidade)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.itens_venda', imutavel => true);
COMMENT ON TABLE corona.itens_venda IS 'Itens da venda: uma linha por produto, imutáveis (ADR-0011).';
CREATE UNIQUE INDEX itens_venda_produto_unico ON corona.itens_venda (venda_id, produto_id);
CREATE INDEX itens_venda_por_produto ON corona.itens_venda (produto_id);

CREATE TABLE corona.pagamentos_cliente (
    id uuid PRIMARY KEY,
    venda_id uuid NOT NULL REFERENCES corona.vendas (id),
    tipo text NOT NULL DEFAULT 'PAGAMENTO' CONSTRAINT pagamentos_cliente_tipo_conhecido CHECK (tipo IN ('PAGAMENTO', 'REEMBOLSO')),
    forma text NOT NULL CONSTRAINT pagamentos_cliente_forma_conhecida CHECK (forma IN ('DINHEIRO', 'PIX', 'CARTAO', 'CHEQUE')),
    -- Reembolso é registro negativo (§9). Nunca zero.
    valor numeric(14, 2) NOT NULL,
    pago_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pagamentos_cliente_sinal CHECK ((tipo = 'PAGAMENTO' AND valor > 0) OR (tipo = 'REEMBOLSO' AND valor < 0))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.pagamentos_cliente', imutavel => true);
COMMENT ON TABLE corona.pagamentos_cliente IS
    'Pagamentos do cliente são eventos imutáveis (§7.2); o reembolso de um estorno é um registro negativo.';
CREATE INDEX pagamentos_cliente_por_venda ON corona.pagamentos_cliente (venda_id);

-- Venda e estorno gravam o movimento da maleta com o mesmo venda_id; o movimento aponta para a venda.
ALTER TABLE corona.movimentos_remessa
    ADD CONSTRAINT movimentos_remessa_venda_fk FOREIGN KEY (venda_id) REFERENCES corona.vendas (id);

-- Limite de desconto (§7.2): % do subtotal da venda. Padrão 0 (sem desconto) até a dona definir (dúvida 1 do ADR-0011).
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_limite_desconto_faixa
    CHECK (chave <> 'limite_desconto_percentual' OR valor_inteiro BETWEEN 0 AND 100);
INSERT INTO corona.configuracoes (id, chave, tipo, valor_inteiro) VALUES (uuidv7(), 'limite_desconto_percentual', 'INTEIRO', 0);

REVOKE ALL ON corona.vendas, corona.itens_venda, corona.pagamentos_cliente FROM PUBLIC;
