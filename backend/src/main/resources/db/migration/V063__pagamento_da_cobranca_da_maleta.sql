-- V063: receber a cobrança da falta do Fechamento da maleta (27/11/2026; ESPECIFICACAO §23.10, item 1a).
--
-- A dona lança o que o representante pagou da falta "Cobrar do representante" (pendencias_da_maleta, V062): valor em Real (pode ser
-- parte), forma do caixa (Dinheiro ou Transferência) e, como no recebimento do representante (V058), a moeda em que foi digitado
-- (o valor em Dólar ou Guarani e a cotação que converteu ficam só como informação). Cada pagamento é imutável e tem, na mesma
-- transação, UMA entrada no caixa (origem COBRANCA_DA_MALETA). Corrigir = estorno (valor negativo, com motivo), que tem a saída
-- oposta no caixa. Só do servidor: nunca vai ao Corona Vendas. Nenhuma migração publicada é editada: o CHECK da origem do caixa
-- é trocado e a função que confere o lançamento é recriada inteira (a da V046, com um ramo novo).

CREATE TABLE corona.pagamentos_cobranca_maleta (
    id uuid PRIMARY KEY,
    pendencia_id uuid NOT NULL REFERENCES corona.pendencias_da_maleta (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    -- Em Real, com sinal: o pagamento é positivo e o estorno é o negativo do original.
    valor numeric(14, 2) NOT NULL CONSTRAINT pagamentos_cobranca_maleta_valor_nao_zero CHECK (valor <> 0),
    forma text NOT NULL CONSTRAINT pagamentos_cobranca_maleta_forma_conhecida CHECK (forma IN ('DINHEIRO', 'PIX')),
    moeda text NOT NULL DEFAULT 'REAL' CONSTRAINT pagamentos_cobranca_maleta_moeda_conhecida CHECK (moeda IN ('REAL', 'DOLAR', 'GUARANI')),
    valor_na_moeda numeric(14, 2),
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    data_pagamento date NOT NULL,
    estorna_id uuid CONSTRAINT pagamentos_cobranca_maleta_um_estorno UNIQUE REFERENCES corona.pagamentos_cobranca_maleta (id),
    motivo text CONSTRAINT pagamentos_cobranca_maleta_motivo_curto CHECK (length(motivo) <= 500),
    registrado_por uuid NOT NULL REFERENCES corona.usuarios (id),
    CONSTRAINT pagamentos_cobranca_maleta_sinal CHECK ((estorna_id IS NULL) = (valor > 0)),
    CONSTRAINT pagamentos_cobranca_maleta_estorno_com_motivo CHECK (estorna_id IS NULL OR coalesce(btrim(motivo), '') <> ''),
    CONSTRAINT pagamentos_cobranca_maleta_moeda_completa CHECK (
        (moeda = 'REAL' AND valor_na_moeda IS NULL)
        OR (moeda <> 'REAL' AND valor_na_moeda IS NOT NULL AND sign(valor_na_moeda) = sign(valor))),
    CONSTRAINT pagamentos_cobranca_maleta_guarani_inteiro CHECK (moeda <> 'GUARANI' OR valor_na_moeda = trunc(valor_na_moeda))
);
SELECT corona.aplicar_convencoes_do_servidor('corona.pagamentos_cobranca_maleta', editavel => false);
COMMENT ON TABLE corona.pagamentos_cobranca_maleta IS
    'O que o representante pagou da falta cobrada no Fechamento da maleta (§23.10, item 1a). Imutável; estorno corrige. Só do servidor.';
CREATE INDEX pagamentos_cobranca_maleta_por_pendencia ON corona.pagamentos_cobranca_maleta (pendencia_id);
CREATE INDEX pagamentos_cobranca_maleta_por_vendedor ON corona.pagamentos_cobranca_maleta (vendedor_id);
REVOKE ALL ON corona.pagamentos_cobranca_maleta FROM PUBLIC;

-- O banco também confere: só falta cobrada recebe pagamento; o estorno desfaz um pagamento da mesma falta, pelo valor oposto e com a
-- mesma forma e moeda; a soma paga da falta fica entre zero e o valor dela (a falta é lida com FOR UPDATE: dois pagamentos ao mesmo
-- tempo esperam um pelo outro).
CREATE FUNCTION corona.conferir_pagamento_da_cobranca() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p record;
    o record;
    pago numeric(14, 2);
BEGIN
    SELECT decisao, valor, vendedor_id INTO p FROM corona.pendencias_da_maleta WHERE id = NEW.pendencia_id FOR UPDATE;
    IF NOT FOUND OR p.decisao <> 'COBRAR_DO_REPRESENTANTE' OR p.vendedor_id <> NEW.vendedor_id THEN
        RAISE EXCEPTION 'O pagamento % precisa ser de uma falta cobrada do mesmo representante.', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.estorna_id IS NOT NULL THEN
        SELECT pendencia_id, valor, forma, moeda, valor_na_moeda, cotacao_id, estorna_id INTO o
          FROM corona.pagamentos_cobranca_maleta WHERE id = NEW.estorna_id;
        IF NOT FOUND OR o.estorna_id IS NOT NULL OR o.pendencia_id <> NEW.pendencia_id OR o.valor <> -NEW.valor
                OR o.forma <> NEW.forma OR o.moeda <> NEW.moeda OR o.cotacao_id IS DISTINCT FROM NEW.cotacao_id
                OR o.valor_na_moeda IS DISTINCT FROM -NEW.valor_na_moeda THEN
            RAISE EXCEPTION 'O estorno % precisa desfazer um pagamento da mesma falta, da mesma forma e moeda, pelo mesmo valor.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    SELECT coalesce(sum(valor), 0) INTO pago FROM corona.pagamentos_cobranca_maleta WHERE pendencia_id = NEW.pendencia_id;
    IF pago + NEW.valor > p.valor OR pago + NEW.valor < 0 THEN
        RAISE EXCEPTION 'O pagamento % passa do valor da falta.', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER pagamentos_cobranca_maleta_conferidos BEFORE INSERT ON corona.pagamentos_cobranca_maleta
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_pagamento_da_cobranca();

-- No COMMIT: cada pagamento (e cada estorno) tem EXATAMENTE um lançamento de caixa, do mesmo valor e forma (C4).
CREATE FUNCTION corona.conferir_caixa_da_cobranca() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    lancamentos integer;
BEGIN
    SELECT count(*) INTO lancamentos FROM corona.lancamentos_caixa
     WHERE origem_tipo = 'COBRANCA_DA_MALETA' AND origem_id = NEW.id AND valor = NEW.valor AND forma = NEW.forma;
    IF lancamentos <> 1 THEN
        RAISE EXCEPTION 'O pagamento da cobrança % precisa de exatamente um lançamento de caixa, do mesmo valor e forma.', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER pagamentos_cobranca_maleta_com_caixa AFTER INSERT ON corona.pagamentos_cobranca_maleta
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION corona.conferir_caixa_da_cobranca();

-- O caixa: categoria do sistema e origem nova.
INSERT INTO corona.categorias_caixa (id, nome, tipo, sistema) VALUES (uuidv7(), 'Cobrança de falta da maleta', 'ENTRADA', true);
ALTER TABLE corona.lancamentos_caixa DROP CONSTRAINT lancamentos_caixa_origem_conhecida;
ALTER TABLE corona.lancamentos_caixa ADD CONSTRAINT lancamentos_caixa_origem_conhecida
    CHECK (origem_tipo IN ('RECEBIMENTO_VENDEDOR', 'ACERTO_A_FAVOR_DO_VENDEDOR', 'PAGAMENTO_FORNECEDOR', 'MANUAL', 'COBRANCA_DA_MALETA'));

-- A função da V046, inteira, com o ramo COBRANCA_DA_MALETA (o lançamento é de um pagamento da cobrança, com o mesmo valor e forma;
-- o estorno desfaz o lançamento do pagamento estornado).
CREATE OR REPLACE FUNCTION corona.conferir_lancamento_de_caixa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r record;
    a record;
    b record;
    c record;
    k record;
    original record;
BEGIN
    IF NEW.origem_tipo = 'RECEBIMENTO_VENDEDOR' THEN
        SELECT valor, forma, estorna_id INTO r FROM corona.recebimentos_vendedor WHERE id = NEW.origem_id;
        IF NOT FOUND OR r.valor <> NEW.valor OR r.forma <> NEW.forma OR (r.estorna_id IS NULL) <> (NEW.estorna_id IS NULL) THEN
            RAISE EXCEPTION 'O lançamento % precisa ser de um recebimento do vendedor, com a mesma forma e o mesmo valor.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.estorna_id IS NOT NULL THEN
            SELECT origem_tipo, origem_id INTO original FROM corona.lancamentos_caixa WHERE id = NEW.estorna_id;
            IF NOT FOUND OR original.origem_tipo <> 'RECEBIMENTO_VENDEDOR' OR original.origem_id <> r.estorna_id THEN
                RAISE EXCEPTION 'O estorno de lançamento % precisa desfazer o lançamento do recebimento estornado.', NEW.id
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    ELSIF NEW.origem_tipo = 'COBRANCA_DA_MALETA' THEN
        SELECT valor, forma, estorna_id INTO k FROM corona.pagamentos_cobranca_maleta WHERE id = NEW.origem_id;
        IF NOT FOUND OR k.valor <> NEW.valor OR k.forma <> NEW.forma OR (k.estorna_id IS NULL) <> (NEW.estorna_id IS NULL) THEN
            RAISE EXCEPTION 'O lançamento % precisa ser de um pagamento da cobrança da maleta, com a mesma forma e o mesmo valor.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.estorna_id IS NOT NULL THEN
            SELECT origem_tipo, origem_id INTO original FROM corona.lancamentos_caixa WHERE id = NEW.estorna_id;
            IF NOT FOUND OR original.origem_tipo <> 'COBRANCA_DA_MALETA' OR original.origem_id <> k.estorna_id THEN
                RAISE EXCEPTION 'O estorno de lançamento % precisa desfazer o lançamento do pagamento estornado.', NEW.id
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    ELSIF NEW.origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' THEN
        SELECT status, pago_ao_vendedor INTO a FROM corona.acertos WHERE id = NEW.origem_id;
        IF NOT FOUND OR a.status <> 'ACERTADO' OR NEW.estorna_id IS NOT NULL OR NEW.tipo <> 'SAIDA' OR a.pago_ao_vendedor <> -NEW.valor THEN
            RAISE EXCEPTION 'O lançamento % precisa ser a saída do que o acerto pagou ao vendedor.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.origem_tipo = 'PAGAMENTO_FORNECEDOR' THEN
        -- C4: o lançamento é de uma baixa de pagamento, com o valor oposto e a mesma forma; o estorno desfaz o lançamento do
        -- pagamento estornado.
        SELECT tipo, valor, forma, estorna_id INTO b FROM corona.baixas_pagar WHERE id = NEW.origem_id;
        IF NOT FOUND OR b.tipo <> 'PAGAMENTO' OR NEW.tipo <> 'SAIDA' OR b.valor <> -NEW.valor OR b.forma <> NEW.forma
                OR (b.estorna_id IS NULL) <> (NEW.estorna_id IS NULL) THEN
            RAISE EXCEPTION 'O lançamento % precisa ser de um pagamento a fornecedor, com a mesma forma e o valor oposto.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.estorna_id IS NOT NULL THEN
            SELECT origem_tipo, origem_id INTO original FROM corona.lancamentos_caixa WHERE id = NEW.estorna_id;
            IF NOT FOUND OR original.origem_tipo <> 'PAGAMENTO_FORNECEDOR' OR original.origem_id <> b.estorna_id THEN
                RAISE EXCEPTION 'O estorno de lançamento % precisa desfazer o lançamento do pagamento estornado.', NEW.id
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    ELSE
        -- MANUAL: categoria do usuário (não do sistema), do mesmo tipo; nova só com categoria ativa. O estorno desfaz um manual,
        -- com o mesmo tipo, forma e categoria e o valor oposto.
        SELECT tipo, ativo, sistema INTO c FROM corona.categorias_caixa WHERE id = NEW.categoria_id;
        IF NOT FOUND OR c.sistema OR c.tipo <> NEW.tipo OR (NEW.estorna_id IS NULL AND NOT c.ativo) THEN
            RAISE EXCEPTION 'O lançamento manual % precisa ter uma categoria ativa, do mesmo tipo e que não seja do sistema.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.estorna_id IS NOT NULL THEN
            SELECT origem_tipo, tipo, valor, forma, categoria_id INTO original FROM corona.lancamentos_caixa WHERE id = NEW.estorna_id;
            IF NOT FOUND OR original.origem_tipo <> 'MANUAL' OR original.tipo <> NEW.tipo OR original.valor <> -NEW.valor
                    OR original.forma <> NEW.forma OR original.categoria_id <> NEW.categoria_id THEN
                RAISE EXCEPTION 'O estorno manual % precisa desfazer um lançamento manual, pelo valor oposto.', NEW.id
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
