-- V046: contas a pagar das compras, pagamento a fornecedor, caixa manual com categorias e fechar semana (Fase 7, tarefa 7.7;
-- ADR-0012, itens 2, 3.8, 11, 12, 13, 14, 15, 16 e 17; condições C4, C5 e C10; ESPECIFICACAO §7.4.7 e §7.5; plano aprovado no
-- PR #168).
--
-- A dona paga o fornecedor em dinheiro, Transferência (PIX) ou boleto, de uma vez ou em parcelas; cada pagamento é uma baixa
-- imutável e gera, na mesma transação, UMA saída de caixa. O caixa também recebe lançamentos manuais (com categoria). A semana
-- fecha uma vez e nunca reabre. Tudo em Real. Aqui o banco é a segunda trava: o serviço pode errar, o banco não deixa gravar
-- errado. Sem entrada, sem cheque e sem cartão. Nenhuma migração publicada (V001 a V045) é editada: os CHECK são trocados e as
-- funções recriadas inteiras (CREATE OR REPLACE).

-- ---------------------------------------------------------------------------------------------
-- 1. categorias_caixa: conteúdo para a idempotência, categoria do sistema intocável, tipo imutável, uma categoria nova do sistema.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.categorias_caixa ADD COLUMN conteudo text;
INSERT INTO corona.categorias_caixa (id, nome, tipo, sistema) VALUES (uuidv7(), 'Pagamento a fornecedor', 'SAIDA', true);

CREATE FUNCTION corona.proteger_categoria_do_caixa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tipo IS DISTINCT FROM OLD.tipo OR NEW.sistema IS DISTINCT FROM OLD.sistema THEN
        RAISE EXCEPTION 'O tipo da categoria % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.sistema AND (NEW.nome IS DISTINCT FROM OLD.nome OR NEW.ativo IS DISTINCT FROM OLD.ativo
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at) THEN
        RAISE EXCEPTION 'A categoria do sistema % não pode ser alterada.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER categorias_caixa_protegida BEFORE UPDATE ON corona.categorias_caixa
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_categoria_do_caixa();

-- ---------------------------------------------------------------------------------------------
-- 2. fechamentos_semana: o registro de que a semana foi fechada (só do servidor, imutável). Fica fora de semanas para não mexer
-- no contrato sincronizável. O id é o UUID do comando da dona (idempotência).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.fechamentos_semana (
    id uuid PRIMARY KEY,
    semana_id uuid NOT NULL CONSTRAINT fechamentos_semana_unico UNIQUE REFERENCES corona.semanas (id),
    fechada_em timestamptz NOT NULL DEFAULT now(),
    fechada_por uuid NOT NULL REFERENCES corona.usuarios (id)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.fechamentos_semana', editavel => false);
COMMENT ON TABLE corona.fechamentos_semana IS
    'Fechamento de semana (ADR-0012, item 17): um por semana, imutável. A semana só vai de ABERTA para FECHADA com a linha aqui, e nunca reabre. Só do servidor.';

-- Semana fechada não muda nada; ABERTA só vira FECHADA com o fechamento registrado.
CREATE FUNCTION corona.proteger_semana() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.situacao = 'FECHADA' THEN
        IF NEW.situacao IS DISTINCT FROM OLD.situacao OR NEW.numero IS DISTINCT FROM OLD.numero OR NEW.inicio IS DISTINCT FROM OLD.inicio
                OR NEW.fim IS DISTINCT FROM OLD.fim OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
            RAISE EXCEPTION 'A semana % já foi fechada e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF NEW.situacao = 'FECHADA' AND NOT EXISTS (SELECT 1 FROM corona.fechamentos_semana f WHERE f.semana_id = OLD.id) THEN
        RAISE EXCEPTION 'A semana % só fecha pelo fechamento registrado.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER semanas_protegida BEFORE UPDATE ON corona.semanas
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_semana();

-- Semana fechada não nasce por INSERT (só pelo fechamento).
CREATE FUNCTION corona.semana_nasce_aberta() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.situacao <> 'ABERTA' THEN
        RAISE EXCEPTION 'A semana % nasce aberta.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER semanas_nasce_aberta BEFORE INSERT ON corona.semanas
    FOR EACH ROW EXECUTE FUNCTION corona.semana_nasce_aberta();

-- ---------------------------------------------------------------------------------------------
-- 3. A segunda trava da semana: quem grava com data numa semana lê a semana com FOR SHARE (o fechar a trava com FOR UPDATE).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION corona.proteger_acerto() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    situacao text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'EM_ACERTO' THEN
            RAISE EXCEPTION 'O acerto % nasce em andamento.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
        SELECT s.situacao INTO situacao FROM corona.semanas s WHERE s.id = NEW.semana_id FOR SHARE;
        IF situacao IS DISTINCT FROM 'ABERTA' THEN
            RAISE EXCEPTION 'A semana do acerto % precisa estar aberta.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.semana_id IS DISTINCT FROM OLD.semana_id
            OR NEW.pedido_pelo_vendedor IS DISTINCT FROM OLD.pedido_pelo_vendedor OR NEW.iniciado_em IS DISTINCT FROM OLD.iniciado_em
            OR NEW.iniciado_por IS DISTINCT FROM OLD.iniciado_por OR NEW.dispositivo_id IS DISTINCT FROM OLD.dispositivo_id
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'O conteúdo do acerto % não pode ser alterado.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'EM_ACERTO' THEN
        -- Em andamento: desiste (V043) ou fecha (o CHECK acertos_fechamento_completo exige o fechamento inteiro).
        IF NEW.status NOT IN ('EM_ACERTO', 'DESISTIDO', 'ACERTADO') THEN
            RAISE EXCEPTION 'O acerto % só passa de em andamento para desistido ou acertado.', OLD.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF ROW(NEW.status, NEW.desistencia_id, NEW.desistido_em, NEW.desistido_por, NEW.motivo_desistencia, NEW.fechamento_id,
              NEW.conteudo_do_fechamento, NEW.fechado_em, NEW.fechado_por, NEW.cotacao_id, NEW.meta_guarani, NEW.percentual_comissao,
              NEW.vendido_para_meta_guarani, NEW.vendido_bruto, NEW.descontos, NEW.devolvido, NEW.vendido_liquido, NEW.comissao,
              NEW.faltas_cobradas, NEW.liquido, NEW.recebido_no_acerto, NEW.ficou_devendo, NEW.pago_ao_vendedor, NEW.credito_usado,
              NEW.credito_do_acerto_id)
          IS DISTINCT FROM
          ROW(OLD.status, OLD.desistencia_id, OLD.desistido_em, OLD.desistido_por, OLD.motivo_desistencia, OLD.fechamento_id,
              OLD.conteudo_do_fechamento, OLD.fechado_em, OLD.fechado_por, OLD.cotacao_id, OLD.meta_guarani, OLD.percentual_comissao,
              OLD.vendido_para_meta_guarani, OLD.vendido_bruto, OLD.descontos, OLD.devolvido, OLD.vendido_liquido, OLD.comissao,
              OLD.faltas_cobradas, OLD.liquido, OLD.recebido_no_acerto, OLD.ficou_devendo, OLD.pago_ao_vendedor, OLD.credito_usado,
              OLD.credito_do_acerto_id) THEN
        RAISE EXCEPTION 'O acerto % já terminou e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- Maleta nova (rascunho) ou enviada numa semana fechada: recusada.
CREATE FUNCTION corona.remessa_em_semana_aberta() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    situacao text;
BEGIN
    IF TG_OP = 'INSERT' OR (NEW.status = 'ENVIADA' AND OLD.status IS DISTINCT FROM 'ENVIADA') THEN
        SELECT s.situacao INTO situacao FROM corona.semanas s WHERE s.id = NEW.semana_id FOR SHARE;
        IF situacao = 'FECHADA' THEN
            RAISE EXCEPTION 'A semana da maleta % já foi fechada.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER remessas_semana_aberta BEFORE INSERT OR UPDATE ON corona.remessas
    FOR EACH ROW EXECUTE FUNCTION corona.remessa_em_semana_aberta();

-- ---------------------------------------------------------------------------------------------
-- 4. contas_pagar: a parcela que a dona deve ao fornecedor (só do servidor; só os atalhos mudam).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.contas_pagar (
    -- Do servidor (v7): a parcela nasce como efeito do comando "gerar contas".
    id uuid PRIMARY KEY,
    compra_id uuid NOT NULL REFERENCES corona.compras (id),
    fornecedor_id uuid NOT NULL REFERENCES corona.fornecedores (id),
    -- O UUID do comando "gerar contas" (do aplicativo), igual em todas as parcelas da geração.
    geracao_id uuid NOT NULL,
    parcela smallint NOT NULL,
    total_parcelas smallint NOT NULL,
    CONSTRAINT contas_pagar_parcela_na_faixa CHECK (parcela >= 1 AND parcela <= total_parcelas AND total_parcelas <= 24),
    vencimento date NOT NULL,
    valor numeric(14, 2) NOT NULL CONSTRAINT contas_pagar_valor_positivo CHECK (valor > 0),
    -- Atalhos: só o ServicoDeContasAPagar os muda, na transação da baixa, e o gatilho confere com a soma das baixas (C5).
    valor_pago numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT contas_pagar_pago_nao_negativo CHECK (valor_pago >= 0),
    valor_cancelado numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT contas_pagar_cancelado_nao_negativo CHECK (valor_cancelado >= 0),
    CONSTRAINT contas_pagar_pago_e_cancelado_cabem CHECK (valor_pago + valor_cancelado <= valor),
    -- VENCIDA nunca é gravada: é calculada pela data de hoje em Brasília.
    status text NOT NULL DEFAULT 'ABERTA' CONSTRAINT contas_pagar_status_conhecido CHECK (status IN ('ABERTA', 'PARCIAL', 'PAGA', 'CANCELADA')),
    CONSTRAINT contas_pagar_status_confere CHECK (
        (status = 'ABERTA' AND valor_pago = 0 AND valor_cancelado = 0)
        OR (status = 'PARCIAL' AND valor_pago > 0 AND valor_pago < valor AND valor_cancelado = 0)
        OR (status = 'PAGA' AND valor_pago = valor AND valor_cancelado = 0)
        OR (status = 'CANCELADA' AND valor_cancelado > 0 AND valor_pago + valor_cancelado = valor)),
    -- O resumo (SHA-256) do pedido que gerou as contas, para o reenvio igual dar 200 e o diferente dar 409.
    conteudo text NOT NULL,
    criada_em timestamptz NOT NULL DEFAULT now(),
    criada_por uuid NOT NULL REFERENCES corona.usuarios (id),
    deleted_at timestamptz,
    CONSTRAINT contas_pagar_parcela_unica UNIQUE (compra_id, parcela)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.contas_pagar', editavel => true);
COMMENT ON TABLE corona.contas_pagar IS
    'Parcela a pagar de uma compra (ADR-0012, item 12). O conteúdo nunca muda; só valor_pago, valor_cancelado e status, pelo serviço, e o gatilho confere com baixas_pagar. A soma das parcelas é o total da compra. Só do servidor.';
CREATE INDEX contas_pagar_por_fornecedor ON corona.contas_pagar (fornecedor_id, vencimento);
CREATE INDEX contas_pagar_em_aberto ON corona.contas_pagar (status, vencimento) WHERE status IN ('ABERTA', 'PARCIAL');

-- Nasce em aberto, de compra registrada e do fornecedor dela.
CREATE FUNCTION corona.conferir_conta_a_pagar() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c record;
BEGIN
    IF NEW.status <> 'ABERTA' THEN
        RAISE EXCEPTION 'A conta a pagar % nasce em aberto.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    SELECT situacao, fornecedor_id INTO c FROM corona.compras WHERE id = NEW.compra_id;
    IF NOT FOUND OR c.situacao <> 'REGISTRADA' OR c.fornecedor_id <> NEW.fornecedor_id THEN
        RAISE EXCEPTION 'A conta a pagar % precisa ser de uma compra registrada e do fornecedor dela.', NEW.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER contas_pagar_nasce_conferida BEFORE INSERT ON corona.contas_pagar
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_conta_a_pagar();

-- C10: a soma das parcelas da compra é o total da compra, e todas as parcelas existem (conferido no COMMIT).
CREATE FUNCTION corona.conferir_parcelas_da_compra() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    soma numeric(14, 2);
    quantas integer;
    total numeric(14, 2);
BEGIN
    SELECT sum(valor), count(*) INTO soma, quantas FROM corona.contas_pagar WHERE compra_id = NEW.compra_id;
    SELECT c.total INTO total FROM corona.compras c WHERE c.id = NEW.compra_id;
    IF soma IS DISTINCT FROM total OR quantas <> NEW.total_parcelas THEN
        RAISE EXCEPTION 'As parcelas da compra % precisam somar o total da compra.', NEW.compra_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER contas_pagar_soma_confere AFTER INSERT ON corona.contas_pagar
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION corona.conferir_parcelas_da_compra();

-- O conteúdo é imutável; os atalhos só mudam para a soma das baixas (feitas antes, na mesma transação).
CREATE FUNCTION corona.proteger_conta_a_pagar() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    pago numeric(14, 2);
    cancelado numeric(14, 2);
BEGIN
    IF NEW.compra_id IS DISTINCT FROM OLD.compra_id OR NEW.fornecedor_id IS DISTINCT FROM OLD.fornecedor_id
            OR NEW.geracao_id IS DISTINCT FROM OLD.geracao_id OR NEW.parcela IS DISTINCT FROM OLD.parcela
            OR NEW.total_parcelas IS DISTINCT FROM OLD.total_parcelas OR NEW.vencimento IS DISTINCT FROM OLD.vencimento
            OR NEW.valor IS DISTINCT FROM OLD.valor OR NEW.conteudo IS DISTINCT FROM OLD.conteudo
            OR NEW.criada_em IS DISTINCT FROM OLD.criada_em OR NEW.criada_por IS DISTINCT FROM OLD.criada_por
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'A conta a pagar % não pode ser alterada: a correção é feita com baixa e estorno.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.valor_pago IS DISTINCT FROM OLD.valor_pago OR NEW.valor_cancelado IS DISTINCT FROM OLD.valor_cancelado THEN
        SELECT coalesce(sum(valor) FILTER (WHERE tipo = 'PAGAMENTO'), 0), coalesce(sum(valor) FILTER (WHERE tipo = 'CANCELAMENTO'), 0)
          INTO pago, cancelado FROM corona.baixas_pagar WHERE conta_id = OLD.id;
        IF NEW.valor_pago <> pago OR NEW.valor_cancelado <> cancelado THEN
            RAISE EXCEPTION 'Os valores pago e cancelado da conta % precisam bater com a soma das baixas.', OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- 5. baixas_pagar: o que baixou a parcela (só do servidor, imutável; o estorno é registro negativo).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.baixas_pagar (
    -- O UUID do cliente no pagamento e no estorno; v7 do servidor no cancelamento.
    id uuid PRIMARY KEY,
    conta_id uuid NOT NULL REFERENCES corona.contas_pagar (id),
    tipo text NOT NULL CONSTRAINT baixas_pagar_tipo_conhecido CHECK (tipo IN ('PAGAMENTO', 'CANCELAMENTO')),
    -- Com sinal: o pagamento é positivo; o estorno do pagamento é negativo; o cancelamento é positivo.
    valor numeric(14, 2) NOT NULL,
    -- Sem CHEQUE e sem CARTAO (ADR-0012, item 16).
    forma text CONSTRAINT baixas_pagar_forma_conhecida CHECK (forma IN ('DINHEIRO', 'PIX', 'BOLETO')),
    data_pagamento date,
    motivo text CONSTRAINT baixas_pagar_motivo_curto CHECK (length(motivo) <= 400),
    estorna_id uuid CONSTRAINT baixas_pagar_estorno_unico UNIQUE REFERENCES corona.baixas_pagar (id),
    conteudo text,
    registrada_em timestamptz NOT NULL DEFAULT now(),
    registrada_por uuid REFERENCES corona.usuarios (id),
    CONSTRAINT baixas_pagar_forma CHECK (
        (tipo = 'PAGAMENTO' AND forma IS NOT NULL AND data_pagamento IS NOT NULL
            AND ((estorna_id IS NULL AND valor > 0 AND motivo IS NULL)
                 OR (estorna_id IS NOT NULL AND valor < 0 AND coalesce(btrim(motivo), '') <> '')))
        OR (tipo = 'CANCELAMENTO' AND forma IS NULL AND data_pagamento IS NULL AND estorna_id IS NULL AND valor > 0
            AND coalesce(btrim(motivo), '') <> ''))
);
SELECT corona.aplicar_convencoes_do_servidor('corona.baixas_pagar', editavel => false);
COMMENT ON TABLE corona.baixas_pagar IS
    'Baixa de parcela a pagar (ADR-0012, item 12): PAGAMENTO (com uma saída de caixa; o estorno é a baixa negativa) ou CANCELAMENTO (da compra desfeita; sem caixa). Imutável. Só do servidor.';
CREATE INDEX baixas_pagar_por_conta ON corona.baixas_pagar (conta_id);

-- Trava a conta, confere o estorno e que nunca se baixa mais do que a parcela vale (C5).
CREATE FUNCTION corona.conferir_baixa_pagar() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c record;
    o record;
    pago numeric(14, 2);
    cancelado numeric(14, 2);
BEGIN
    SELECT valor INTO c FROM corona.contas_pagar WHERE id = NEW.conta_id FOR UPDATE;
    IF NEW.tipo = 'PAGAMENTO' AND NEW.estorna_id IS NOT NULL THEN
        SELECT conta_id, tipo, valor, forma, estorna_id INTO o FROM corona.baixas_pagar WHERE id = NEW.estorna_id;
        IF NOT FOUND OR o.tipo <> 'PAGAMENTO' OR o.estorna_id IS NOT NULL OR o.valor <= 0 OR o.conta_id <> NEW.conta_id
                OR o.valor <> -NEW.valor OR o.forma <> NEW.forma THEN
            RAISE EXCEPTION 'O estorno da baixa % precisa desfazer um pagamento desta conta, pelo mesmo valor e forma.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    SELECT coalesce(sum(valor) FILTER (WHERE tipo = 'PAGAMENTO'), 0), coalesce(sum(valor) FILTER (WHERE tipo = 'CANCELAMENTO'), 0)
      INTO pago, cancelado FROM corona.baixas_pagar WHERE conta_id = NEW.conta_id;
    IF NEW.tipo = 'PAGAMENTO' THEN
        pago := pago + NEW.valor;
    ELSE
        cancelado := cancelado + NEW.valor;
    END IF;
    IF pago < 0 OR pago + cancelado > c.valor THEN
        RAISE EXCEPTION 'A baixa % passa do valor da conta a pagar.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER baixas_pagar_nasce_conferida BEFORE INSERT ON corona.baixas_pagar
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_baixa_pagar();
CREATE TRIGGER contas_pagar_conteudo_imutavel BEFORE UPDATE ON corona.contas_pagar
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_conta_a_pagar();

-- ---------------------------------------------------------------------------------------------
-- 6. lancamentos_caixa: origem MANUAL e PAGAMENTO_FORNECEDOR, motivo e conteúdo, semana fechada, conferência com quatro ramos.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.lancamentos_caixa DROP CONSTRAINT lancamentos_caixa_origem_conhecida;
ALTER TABLE corona.lancamentos_caixa ADD CONSTRAINT lancamentos_caixa_origem_conhecida
    CHECK (origem_tipo IN ('RECEBIMENTO_VENDEDOR', 'ACERTO_A_FAVOR_DO_VENDEDOR', 'PAGAMENTO_FORNECEDOR', 'MANUAL'));
ALTER TABLE corona.lancamentos_caixa ADD COLUMN motivo text;
ALTER TABLE corona.lancamentos_caixa ADD CONSTRAINT lancamentos_caixa_motivo_curto CHECK (length(motivo) <= 400);
ALTER TABLE corona.lancamentos_caixa ADD COLUMN conteudo text;
-- O lançamento manual é a própria origem; o estorno manual tem motivo.
ALTER TABLE corona.lancamentos_caixa ADD CONSTRAINT lancamentos_caixa_manual_e_a_propria_origem
    CHECK (origem_tipo <> 'MANUAL' OR origem_id = id);
ALTER TABLE corona.lancamentos_caixa ADD CONSTRAINT lancamentos_caixa_estorno_manual_com_motivo
    CHECK (origem_tipo <> 'MANUAL' OR estorna_id IS NULL OR coalesce(btrim(motivo), '') <> '');

CREATE OR REPLACE FUNCTION corona.conferir_lancamento_de_caixa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r record;
    a record;
    b record;
    c record;
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

-- Lançamento com data numa semana fechada: recusado (a semana é lida com FOR SHARE, a segunda trava do fechar semana).
CREATE FUNCTION corona.lancamento_em_semana_aberta() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    situacao text;
BEGIN
    SELECT s.situacao INTO situacao FROM corona.semanas s
     WHERE s.deleted_at IS NULL AND NEW.data_lancamento BETWEEN s.inicio AND s.fim FOR SHARE;
    IF situacao = 'FECHADA' THEN
        RAISE EXCEPTION 'O lançamento % tem data numa semana já fechada.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lancamentos_caixa_semana_aberta BEFORE INSERT ON corona.lancamentos_caixa
    FOR EACH ROW EXECUTE FUNCTION corona.lancamento_em_semana_aberta();

-- C4 (no COMMIT): cada baixa de pagamento (e o estorno dela) tem EXATAMENTE um lançamento de caixa de valor oposto e mesma forma, e
-- o cancelamento não tem lançamento.
CREATE FUNCTION corona.conferir_caixa_da_baixa_pagar() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    lancamentos integer;
    esperados integer := 0;
BEGIN
    IF NEW.tipo = 'PAGAMENTO' THEN
        esperados := 1;
    END IF;
    SELECT count(*) INTO lancamentos FROM corona.lancamentos_caixa
     WHERE origem_tipo = 'PAGAMENTO_FORNECEDOR' AND origem_id = NEW.id AND valor = -NEW.valor AND forma IS NOT DISTINCT FROM NEW.forma;
    IF lancamentos <> esperados THEN
        RAISE EXCEPTION 'A baixa % precisa de exatamente um lançamento de caixa, do mesmo valor e forma.', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER baixas_pagar_caixa_confere AFTER INSERT ON corona.baixas_pagar
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION corona.conferir_caixa_da_baixa_pagar();

REVOKE ALL ON corona.contas_pagar, corona.baixas_pagar, corona.fechamentos_semana FROM PUBLIC;
