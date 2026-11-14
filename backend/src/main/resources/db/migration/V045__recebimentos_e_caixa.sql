-- V045: recebimentos do vendedor, contas do nível 2 e caixa automático (Fase 7, tarefa 7.6; ADR-0012, itens 1, 2, 3.6, 7, 8, 9, 11,
-- 13, 14, 15 e 16; ESPECIFICACAO §7.4.5, §7.5, §9, §10 e §22; plano aprovado no PR #167).
--
-- O vendedor paga o acerto à dona em dinheiro, Transferência (PIX) ou boleto. O que faltou vira conta a receber do vendedor
-- (nível 2); o que a loja deve a ele vira crédito no próximo acerto ou saída de caixa. Cada recebimento confirmado gera UM
-- lançamento de caixa, na mesma transação. Tudo em Real. Aqui o banco é a segunda trava: o serviço pode errar, o banco não
-- deixa gravar errado. Sem entrada e sem cheque: CHEQUE e CARTAO são recusados no repasse.
-- Nenhuma migração publicada (V001 a V044) é editada: os CHECK e as funções são recriados inteiros (CREATE OR REPLACE).

-- ---------------------------------------------------------------------------------------------
-- 1. categorias_caixa (só do servidor, editável): duas categorias do sistema para os lançamentos automáticos. As rotas e o
-- lançamento manual são da tarefa 7.7.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.categorias_caixa (
    id uuid PRIMARY KEY,
    nome text NOT NULL CONSTRAINT categorias_caixa_nome_preenchido CHECK (btrim(nome) <> '' AND length(nome) <= 80),
    tipo text NOT NULL CONSTRAINT categorias_caixa_tipo_conhecido CHECK (tipo IN ('ENTRADA', 'SAIDA')),
    ativo boolean NOT NULL DEFAULT true,
    sistema boolean NOT NULL DEFAULT false,
    deleted_at timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.categorias_caixa', editavel => true);
CREATE UNIQUE INDEX categorias_caixa_nome_unico ON corona.categorias_caixa (lower(btrim(nome)));
COMMENT ON TABLE corona.categorias_caixa IS
    'Categorias do caixa (ADR-0012, item 11). As do sistema (sistema = true) são usadas pelos lançamentos automáticos. Só do servidor.';
INSERT INTO corona.categorias_caixa (id, nome, tipo, sistema) VALUES
    (uuidv7(), 'Repasse do vendedor', 'ENTRADA', true),
    (uuidv7(), 'Pagamento ao vendedor', 'SAIDA', true);

-- ---------------------------------------------------------------------------------------------
-- 2. recebimentos_vendedor: o que o vendedor pagou à dona (sincronizável e imutável; o estorno é registro negativo).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.recebimentos_vendedor (
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    -- ACERTO: feito no fechamento. AVULSO: depois, fora do acerto (inclusive o boleto pago).
    momento text NOT NULL CONSTRAINT recebimentos_vendedor_momento_conhecido CHECK (momento IN ('ACERTO', 'AVULSO')),
    acerto_id uuid REFERENCES corona.acertos (id),
    -- ACERTO: paga o acerto e entra em recebido_no_acerto. CONTAS: baixa contas do nível 2 (ADR-0012, item 8).
    aplicado_em text NOT NULL CONSTRAINT recebimentos_vendedor_aplicado_conhecido CHECK (aplicado_em IN ('ACERTO', 'CONTAS')),
    -- Só as três formas do repasse (ADR-0012, item 7): sem cheque e sem cartão.
    forma text NOT NULL CONSTRAINT recebimentos_vendedor_forma_conhecida CHECK (forma IN ('DINHEIRO', 'PIX', 'BOLETO')),
    valor numeric(14, 2) NOT NULL,
    recebido_em timestamptz NOT NULL DEFAULT now(),
    registrado_por uuid REFERENCES corona.usuarios (id),
    motivo text CONSTRAINT recebimentos_vendedor_motivo_curto CHECK (length(motivo) <= 400),
    estorna_id uuid CONSTRAINT recebimentos_vendedor_estorno_unico UNIQUE REFERENCES corona.recebimentos_vendedor (id),
    -- Resumo (SHA-256) do pedido, para o 409 de "mesmo id, outro conteúdo". Não vai para o aplicativo.
    conteudo text,
    CONSTRAINT recebimentos_vendedor_sinal CHECK (
        (estorna_id IS NULL AND valor > 0)
        OR (estorna_id IS NOT NULL AND valor < 0 AND motivo IS NOT NULL AND btrim(motivo) <> '')),
    CONSTRAINT recebimentos_vendedor_acerto_do_momento CHECK ((momento = 'ACERTO') = (acerto_id IS NOT NULL)),
    CONSTRAINT recebimentos_vendedor_aplicado_no_acerto_so_no_acerto CHECK (aplicado_em <> 'ACERTO' OR momento = 'ACERTO'),
    -- No fechamento o boleto é conta a receber, não recebimento: o boleto pago é avulso e baixa a conta do boleto.
    CONSTRAINT recebimentos_vendedor_boleto_so_avulso_nas_contas CHECK (forma <> 'BOLETO' OR (aplicado_em = 'CONTAS' AND momento = 'AVULSO'))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.recebimentos_vendedor', imutavel => true);
COMMENT ON TABLE corona.recebimentos_vendedor IS
    'Recebimento do vendedor à dona (ADR-0012, itens 7 e 8): DINHEIRO, PIX (Transferência) ou BOLETO pago. Imutável; o estorno é registro negativo (estorna_id). Só a dona registra.';
CREATE INDEX recebimentos_vendedor_por_vendedor ON corona.recebimentos_vendedor (vendedor_id);
CREATE INDEX recebimentos_vendedor_por_acerto ON corona.recebimentos_vendedor (acerto_id) WHERE acerto_id IS NOT NULL;

-- Nasce no fechamento (acerto em andamento do mesmo vendedor) ou avulso; o estorno copia o original e inverte o valor.
CREATE FUNCTION corona.conferir_recebimento_do_vendedor() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a record;
    o record;
BEGIN
    IF NEW.estorna_id IS NULL THEN
        IF NEW.momento = 'ACERTO' THEN
            SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = NEW.acerto_id FOR SHARE;
            IF a.status IS DISTINCT FROM 'EM_ACERTO' OR a.vendedor_id IS DISTINCT FROM NEW.vendedor_id THEN
                RAISE EXCEPTION 'O recebimento % só nasce dentro do fechamento de acerto em andamento do mesmo vendedor.', NEW.id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;
    ELSE
        SELECT vendedor_id, momento, acerto_id, aplicado_em, forma, valor, estorna_id INTO o
          FROM corona.recebimentos_vendedor WHERE id = NEW.estorna_id;
        IF NOT FOUND OR o.estorna_id IS NOT NULL OR o.vendedor_id <> NEW.vendedor_id OR o.momento <> NEW.momento
                OR o.acerto_id IS DISTINCT FROM NEW.acerto_id OR o.aplicado_em <> NEW.aplicado_em OR o.forma <> NEW.forma
                OR o.valor <> -NEW.valor THEN
            RAISE EXCEPTION 'O estorno % precisa desfazer um recebimento do mesmo vendedor, da mesma forma, pelo mesmo valor.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER recebimentos_vendedor_nasce_conferido BEFORE INSERT ON corona.recebimentos_vendedor
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_recebimento_do_vendedor();

-- ---------------------------------------------------------------------------------------------
-- 3. lancamentos_caixa: o caixa (só do servidor, imutável; o saldo é sempre a soma).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.lancamentos_caixa (
    id uuid PRIMARY KEY,
    tipo text NOT NULL CONSTRAINT lancamentos_caixa_tipo_conhecido CHECK (tipo IN ('ENTRADA', 'SAIDA')),
    -- Com sinal: entrada positiva, saída negativa; o estorno tem o sinal oposto ao do original e o mesmo tipo.
    valor numeric(14, 2) NOT NULL,
    forma text NOT NULL CONSTRAINT lancamentos_caixa_forma_conhecida CHECK (forma IN ('DINHEIRO', 'PIX', 'BOLETO')),
    categoria_id uuid NOT NULL REFERENCES corona.categorias_caixa (id),
    -- A 7.7 amplia com MANUAL e PAGAMENTO_FORNECEDOR.
    origem_tipo text NOT NULL CONSTRAINT lancamentos_caixa_origem_conhecida
        CHECK (origem_tipo IN ('RECEBIMENTO_VENDEDOR', 'ACERTO_A_FAVOR_DO_VENDEDOR')),
    origem_id uuid NOT NULL,
    -- O dia do calendário de Brasília.
    data_lancamento date NOT NULL,
    lancado_em timestamptz NOT NULL DEFAULT now(),
    descricao text CONSTRAINT lancamentos_caixa_descricao_curta CHECK (length(descricao) <= 200),
    estorna_id uuid CONSTRAINT lancamentos_caixa_estorno_unico UNIQUE REFERENCES corona.lancamentos_caixa (id),
    registrado_por uuid REFERENCES corona.usuarios (id),
    CONSTRAINT lancamentos_caixa_sinal CHECK (
        (estorna_id IS NULL AND ((tipo = 'ENTRADA' AND valor > 0) OR (tipo = 'SAIDA' AND valor < 0)))
        OR (estorna_id IS NOT NULL AND ((tipo = 'ENTRADA' AND valor < 0) OR (tipo = 'SAIDA' AND valor > 0)))),
    CONSTRAINT lancamentos_caixa_origem_unica UNIQUE (origem_tipo, origem_id)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.lancamentos_caixa', editavel => false);
COMMENT ON TABLE corona.lancamentos_caixa IS
    'Caixa (ADR-0012, item 11): um lançamento por recebimento confirmado e por pagamento ao vendedor, com origem única. Imutável; só o ServicoDeCaixa grava. O saldo é a soma. Nunca vai ao aplicativo do vendedor.';
CREATE INDEX lancamentos_caixa_por_data ON corona.lancamentos_caixa (data_lancamento, lancado_em);

-- O lançamento automático confere com a origem (no COMMIT, porque o acerto ganha o valor pago depois do lançamento).
CREATE FUNCTION corona.conferir_lancamento_de_caixa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r record;
    a record;
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
    ELSE
        SELECT status, pago_ao_vendedor INTO a FROM corona.acertos WHERE id = NEW.origem_id;
        IF NOT FOUND OR a.status <> 'ACERTADO' OR NEW.estorna_id IS NOT NULL OR NEW.tipo <> 'SAIDA' OR a.pago_ao_vendedor <> -NEW.valor THEN
            RAISE EXCEPTION 'O lançamento % precisa ser a saída do que o acerto pagou ao vendedor.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER lancamentos_caixa_confere AFTER INSERT ON corona.lancamentos_caixa
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION corona.conferir_lancamento_de_caixa();

-- ---------------------------------------------------------------------------------------------
-- 4. contas_receber: o nível 2 (o vendedor deve à loja).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.contas_receber DROP CONSTRAINT contas_receber_devedor_conhecido;
ALTER TABLE corona.contas_receber ADD CONSTRAINT contas_receber_devedor_conhecido CHECK (devedor_tipo IN ('CLIENTE', 'VENDEDOR'));
ALTER TABLE corona.contas_receber DROP CONSTRAINT contas_receber_origem_conhecida;
ALTER TABLE corona.contas_receber ADD CONSTRAINT contas_receber_origem_conhecida
    CHECK (origem_tipo IN ('VENDA', 'ACERTO', 'ESTORNO_RECEBIMENTO'));
ALTER TABLE corona.contas_receber ADD CONSTRAINT contas_receber_origem_do_devedor CHECK (
    (devedor_tipo = 'CLIENTE' AND origem_tipo = 'VENDA')
    OR (devedor_tipo = 'VENDEDOR' AND origem_tipo IN ('ACERTO', 'ESTORNO_RECEBIMENTO') AND venda_id IS NULL));
-- A conta do boleto: o vencimento e o valor do boleto; só uma conta de acerto do vendedor.
ALTER TABLE corona.contas_receber ADD COLUMN forma_prevista text
    CONSTRAINT contas_receber_forma_prevista_boleto CHECK (forma_prevista = 'BOLETO');
ALTER TABLE corona.contas_receber ADD CONSTRAINT contas_receber_boleto_do_acerto CHECK (
    forma_prevista IS NULL OR (devedor_tipo = 'VENDEDOR' AND origem_tipo = 'ACERTO'));
COMMENT ON COLUMN corona.contas_receber.forma_prevista IS
    'BOLETO: a conta do boleto do fechamento (paga só inteira, por recebimento BOLETO). Vazio nas outras.';
CREATE UNIQUE INDEX contas_receber_parcela_do_vendedor_unica ON corona.contas_receber (origem_tipo, origem_id, parcela)
    WHERE devedor_tipo = 'VENDEDOR';
CREATE INDEX contas_receber_por_vendedor ON corona.contas_receber (vendedor_id, vencimento)
    WHERE vendedor_id IS NOT NULL AND status IN ('ABERTA', 'PARCIAL');

CREATE OR REPLACE FUNCTION corona.conferir_conta_a_receber() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v record;
    a record;
    r record;
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
    ELSIF NEW.origem_tipo = 'ACERTO' THEN
        SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = NEW.origem_id FOR SHARE;
        IF NOT FOUND OR a.status <> 'EM_ACERTO' OR a.vendedor_id IS DISTINCT FROM NEW.vendedor_id THEN
            RAISE EXCEPTION 'A conta a receber % só nasce dentro do fechamento de acerto em andamento do mesmo vendedor.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSE
        SELECT vendedor_id, aplicado_em, valor, estorna_id INTO r FROM corona.recebimentos_vendedor WHERE id = NEW.origem_id;
        IF NOT FOUND OR r.estorna_id IS NULL OR r.aplicado_em <> 'ACERTO' OR r.vendedor_id IS DISTINCT FROM NEW.vendedor_id
                OR r.valor <> -NEW.valor THEN
            RAISE EXCEPTION 'A conta a receber % precisa ser do estorno de um recebimento do acerto, do mesmo vendedor e do mesmo valor.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION corona.proteger_conta_a_receber() RETURNS trigger
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
            OR NEW.forma_prevista IS DISTINCT FROM OLD.forma_prevista OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
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

-- ---------------------------------------------------------------------------------------------
-- 5. baixas_receber: a baixa pelo recebimento do vendedor.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.baixas_receber ADD COLUMN recebimento_vendedor_id uuid REFERENCES corona.recebimentos_vendedor (id);
COMMENT ON COLUMN corona.baixas_receber.recebimento_vendedor_id IS
    'O recebimento do vendedor que baixou a conta do nível 2 (e o estorno dele). Nas baixas do cliente, vazio.';
CREATE INDEX baixas_receber_por_recebimento_vendedor ON corona.baixas_receber (recebimento_vendedor_id)
    WHERE recebimento_vendedor_id IS NOT NULL;

ALTER TABLE corona.baixas_receber DROP CONSTRAINT baixas_receber_forma;
ALTER TABLE corona.baixas_receber ADD CONSTRAINT baixas_receber_forma CHECK (
    (tipo = 'PAGAMENTO' AND (pagamento_cliente_id IS NULL) <> (recebimento_vendedor_id IS NULL) AND motivo_cancelamento IS NULL
        AND devolucao_id IS NULL AND ((estorna_id IS NULL AND valor > 0) OR (estorna_id IS NOT NULL AND valor < 0)))
    OR (tipo = 'CANCELAMENTO' AND pagamento_cliente_id IS NULL AND recebimento_vendedor_id IS NULL AND motivo_cancelamento IS NOT NULL
        AND motivo_cancelamento = 'ESTORNO_VENDA' AND devolucao_id IS NULL AND estorna_id IS NULL AND valor > 0)
    OR (tipo = 'CANCELAMENTO' AND pagamento_cliente_id IS NULL AND recebimento_vendedor_id IS NULL AND motivo_cancelamento IS NOT NULL
        AND motivo_cancelamento = 'DEVOLUCAO' AND devolucao_id IS NOT NULL
        AND ((estorna_id IS NULL AND valor > 0) OR (estorna_id IS NOT NULL AND valor < 0))));

-- A função da V042, inteira, com o ramo do recebimento do vendedor.
CREATE OR REPLACE FUNCTION corona.conferir_baixa_receber() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c record;
    p record;
    o record;
    d record;
    r record;
    pago numeric(14, 2);
    cancelado numeric(14, 2);
    distribuido numeric(14, 2);
    abatido numeric(14, 2);
BEGIN
    SELECT venda_id, valor, devedor_tipo, vendedor_id, forma_prevista INTO c FROM corona.contas_receber WHERE id = NEW.conta_id;
    SELECT coalesce(sum(valor) FILTER (WHERE tipo = 'PAGAMENTO'), 0), coalesce(sum(valor) FILTER (WHERE tipo = 'CANCELAMENTO'), 0)
      INTO pago, cancelado FROM corona.baixas_receber WHERE conta_id = NEW.conta_id;
    IF NEW.tipo = 'PAGAMENTO' AND NEW.recebimento_vendedor_id IS NOT NULL THEN
        SELECT vendedor_id, aplicado_em, forma, valor, estorna_id INTO r FROM corona.recebimentos_vendedor
         WHERE id = NEW.recebimento_vendedor_id;
        IF NOT FOUND OR c.devedor_tipo <> 'VENDEDOR' OR r.vendedor_id IS DISTINCT FROM c.vendedor_id OR r.aplicado_em <> 'CONTAS' THEN
            RAISE EXCEPTION 'A baixa % precisa ser de um recebimento do mesmo vendedor da conta, aplicado nas contas.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.estorna_id IS NULL THEN
            IF r.estorna_id IS NOT NULL THEN
                RAISE EXCEPTION 'A baixa % precisa ser de um recebimento, não de um estorno.', NEW.id USING ERRCODE = 'restrict_violation';
            END IF;
            SELECT coalesce(sum(valor), 0) INTO distribuido FROM corona.baixas_receber
             WHERE recebimento_vendedor_id = NEW.recebimento_vendedor_id AND estorna_id IS NULL;
            IF distribuido + NEW.valor > r.valor THEN
                RAISE EXCEPTION 'As baixas do recebimento % passam do valor recebido.', NEW.recebimento_vendedor_id
                    USING ERRCODE = 'restrict_violation';
            END IF;
            -- O boleto pago é o boleto inteiro: só baixa a conta do boleto e pelo saldo todo.
            IF r.forma = 'BOLETO' AND (c.forma_prevista IS DISTINCT FROM 'BOLETO' OR NEW.valor <> c.valor - pago - cancelado) THEN
                RAISE EXCEPTION 'O boleto pago precisa ser do valor inteiro da conta do boleto (baixa %).', NEW.id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        ELSE
            SELECT conta_id, tipo, valor, estorna_id, recebimento_vendedor_id INTO o FROM corona.baixas_receber WHERE id = NEW.estorna_id;
            IF NOT FOUND OR o.tipo <> 'PAGAMENTO' OR o.estorna_id IS NOT NULL OR o.valor <= 0 OR o.conta_id <> NEW.conta_id
                    OR o.valor <> -NEW.valor OR r.estorna_id IS DISTINCT FROM o.recebimento_vendedor_id THEN
                RAISE EXCEPTION 'O estorno da baixa % precisa desfazer uma baixa de recebimento desta conta, pelo mesmo valor.', NEW.id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;
    ELSIF NEW.tipo = 'PAGAMENTO' THEN
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
    ELSIF NEW.motivo_cancelamento = 'DEVOLUCAO' THEN
        SELECT venda_id, valor_abatido INTO d FROM corona.devolucoes WHERE id = NEW.devolucao_id;
        IF NOT FOUND OR d.venda_id IS DISTINCT FROM c.venda_id THEN
            RAISE EXCEPTION 'A baixa % precisa ser de uma devolução da mesma venda da parcela.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.estorna_id IS NULL THEN
            SELECT coalesce(sum(valor), 0) INTO abatido FROM corona.baixas_receber WHERE devolucao_id = NEW.devolucao_id;
            IF abatido + NEW.valor > d.valor_abatido THEN
                RAISE EXCEPTION 'As baixas da devolução % passam do valor que ela abateu (R$ %).', NEW.devolucao_id, d.valor_abatido
                    USING ERRCODE = 'restrict_violation';
            END IF;
        ELSE
            SELECT conta_id, tipo, motivo_cancelamento, valor, estorna_id, devolucao_id INTO o
              FROM corona.baixas_receber WHERE id = NEW.estorna_id;
            IF NOT FOUND OR o.tipo <> 'CANCELAMENTO' OR o.motivo_cancelamento <> 'DEVOLUCAO' OR o.estorna_id IS NOT NULL
                    OR o.valor <= 0 OR o.conta_id <> NEW.conta_id OR o.valor <> -NEW.valor
                    OR o.devolucao_id IS DISTINCT FROM NEW.devolucao_id THEN
                RAISE EXCEPTION 'O estorno da baixa % precisa desfazer um cancelamento por devolução desta conta e desta devolução, pelo mesmo valor.', NEW.id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;
    END IF;
    -- Nunca baixar mais do que a parcela vale, nem desfazer mais do que foi baixado.
    IF NEW.tipo = 'PAGAMENTO' THEN
        pago := pago + NEW.valor;
    ELSE
        cancelado := cancelado + NEW.valor;
    END IF;
    IF pago < 0 OR cancelado < 0 OR pago + cancelado > c.valor THEN
        RAISE EXCEPTION 'A baixa % passa do valor da conta a receber.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- O gatilho adiado do recebimento (no COMMIT): o recebimento tem exatamente um lançamento de caixa do mesmo valor; o que vai
-- para as contas é baixado por inteiro; o estorno do que pagou o acerto vira conta do nível 2.
CREATE FUNCTION corona.conferir_recebimento_fechado() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    lancamentos integer;
    baixado numeric(14, 2);
    contas numeric(14, 2);
BEGIN
    SELECT count(*) INTO lancamentos FROM corona.lancamentos_caixa
     WHERE origem_tipo = 'RECEBIMENTO_VENDEDOR' AND origem_id = NEW.id AND valor = NEW.valor AND forma = NEW.forma;
    IF lancamentos <> 1 THEN
        RAISE EXCEPTION 'O recebimento % precisa ter exatamente um lançamento de caixa, do mesmo valor e da mesma forma.', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.aplicado_em = 'CONTAS' THEN
        SELECT coalesce(sum(valor), 0) INTO baixado FROM corona.baixas_receber WHERE recebimento_vendedor_id = NEW.id;
        IF baixado <> NEW.valor THEN
            RAISE EXCEPTION 'As baixas do recebimento % (%) precisam somar o valor dele (%).', NEW.id, baixado, NEW.valor
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.estorna_id IS NOT NULL THEN
        SELECT coalesce(sum(valor), 0) INTO contas FROM corona.contas_receber
         WHERE origem_tipo = 'ESTORNO_RECEBIMENTO' AND origem_id = NEW.id;
        IF contas <> -NEW.valor THEN
            RAISE EXCEPTION 'O estorno % de recebimento do acerto precisa gerar conta a receber do mesmo valor.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER recebimentos_vendedor_fechado_confere AFTER INSERT ON corona.recebimentos_vendedor
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION corona.conferir_recebimento_fechado();

-- ---------------------------------------------------------------------------------------------
-- 6. acertos: o pagamento ao vendedor e o crédito usado (zero enquanto o acerto não fecha, e nas linhas já gravadas).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.acertos
    ADD COLUMN pago_ao_vendedor numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT acertos_pago_ao_vendedor_nao_negativo CHECK (pago_ao_vendedor >= 0),
    ADD COLUMN credito_usado numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT acertos_credito_usado_nao_negativo CHECK (credito_usado >= 0),
    -- O acerto anterior cujo crédito foi abatido (único: cada crédito é usado uma vez só).
    ADD COLUMN credito_do_acerto_id uuid CONSTRAINT acertos_credito_do_acerto_unico UNIQUE REFERENCES corona.acertos (id);
COMMENT ON COLUMN corona.acertos.pago_ao_vendedor IS
    'Acerto a favor do vendedor pago na hora (saída de caixa). Zero nos outros.';
COMMENT ON COLUMN corona.acertos.credito_usado IS
    'O crédito de um acerto anterior abatido neste (ficou_devendo negativo do anterior). Zero quando não há.';
COMMENT ON COLUMN corona.acertos.ficou_devendo IS
    'Positivo: o vendedor deve (vira conta a receber do nível 2). Negativo: a loja deve ao vendedor (crédito para o próximo acerto). Zero: tudo pago.';

ALTER TABLE corona.acertos DROP CONSTRAINT acertos_fechamento_completo;
ALTER TABLE corona.acertos ADD CONSTRAINT acertos_fechamento_completo CHECK (
    (status = 'ACERTADO' AND fechamento_id IS NOT NULL AND conteudo_do_fechamento IS NOT NULL AND fechado_em IS NOT NULL
        AND fechado_por IS NOT NULL AND cotacao_id IS NOT NULL AND meta_guarani IS NOT NULL AND percentual_comissao IS NOT NULL
        AND vendido_para_meta_guarani IS NOT NULL AND vendido_bruto IS NOT NULL AND descontos IS NOT NULL AND devolvido IS NOT NULL
        AND vendido_liquido IS NOT NULL AND comissao IS NOT NULL AND faltas_cobradas IS NOT NULL AND liquido IS NOT NULL
        AND recebido_no_acerto IS NOT NULL AND ficou_devendo IS NOT NULL)
    OR (status <> 'ACERTADO' AND fechamento_id IS NULL AND conteudo_do_fechamento IS NULL AND fechado_em IS NULL
        AND fechado_por IS NULL AND cotacao_id IS NULL AND meta_guarani IS NULL AND percentual_comissao IS NULL
        AND vendido_para_meta_guarani IS NULL AND vendido_bruto IS NULL AND descontos IS NULL AND devolvido IS NULL
        AND vendido_liquido IS NULL AND comissao IS NULL AND faltas_cobradas IS NULL AND liquido IS NULL
        AND recebido_no_acerto IS NULL AND ficou_devendo IS NULL
        AND pago_ao_vendedor = 0 AND credito_usado = 0 AND credito_do_acerto_id IS NULL));
-- C2 (ADR-0012, item 17): o que o vendedor devia (líquido, menos o crédito que já tinha) foi pago, ficou devendo ou, a favor
-- dele, foi pago a ele. A forma antiga (sem crédito e sem pagamento ao vendedor) é o caso com zeros.
ALTER TABLE corona.acertos DROP CONSTRAINT acertos_liquido_e_recebimento_conferem;
ALTER TABLE corona.acertos ADD CONSTRAINT acertos_liquido_e_recebimento_conferem
    CHECK (liquido - credito_usado = recebido_no_acerto - pago_ao_vendedor + ficou_devendo);
ALTER TABLE corona.acertos ADD CONSTRAINT acertos_recebido_ou_pago_ao_vendedor
    CHECK (recebido_no_acerto IS NULL OR recebido_no_acerto = 0 OR pago_ao_vendedor = 0);
ALTER TABLE corona.acertos ADD CONSTRAINT acertos_credito_e_origem_do_credito
    CHECK ((credito_usado = 0) = (credito_do_acerto_id IS NULL));

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
        SELECT s.situacao INTO situacao FROM corona.semanas s WHERE s.id = NEW.semana_id;
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

-- O gatilho adiado do fechamento da V044, inteiro, com o dinheiro: recebido = soma dos recebimentos do acerto; o que ficou
-- devendo vira contas do nível 2 (e só então); o pago ao vendedor é a saída de caixa; o crédito usado é o do acerto ligado.
CREATE OR REPLACE FUNCTION corona.conferir_acerto_fechado() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    soma_eventos numeric;
    bruto numeric;
    descontos numeric;
    soma_comissoes numeric;
    soma_faltas numeric;
    soma_recebido numeric;
    soma_contas numeric;
    soma_pago numeric;
    anterior record;
BEGIN
    SELECT coalesce(sum(valor), 0) INTO soma_eventos FROM corona.acerto_eventos WHERE acerto_id = NEW.id;
    IF NEW.vendido_liquido <> soma_eventos THEN
        RAISE EXCEPTION 'O vendido líquido do acerto % (%) precisa ser a soma dos eventos (%).', NEW.id, NEW.vendido_liquido, soma_eventos
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT coalesce(sum(v.subtotal), 0), coalesce(sum(v.desconto), 0) INTO bruto, descontos
      FROM corona.acerto_eventos e JOIN corona.vendas v ON v.id = e.venda_id
     WHERE e.acerto_id = NEW.id AND e.origem_tipo = 'VENDA';
    IF NEW.vendido_bruto <> bruto OR NEW.descontos <> descontos THEN
        RAISE EXCEPTION 'O vendido bruto e os descontos do acerto % precisam ser a soma das vendas dos eventos.', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT coalesce(sum(valor), 0) INTO soma_comissoes FROM corona.comissoes WHERE acerto_id = NEW.id;
    IF NEW.comissao <> soma_comissoes THEN
        RAISE EXCEPTION 'A comissão do acerto % (%) precisa ser a soma das linhas de comissão (%).', NEW.id, NEW.comissao, soma_comissoes
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT coalesce(sum(valor), 0) INTO soma_faltas FROM corona.pendencias_acerto WHERE cobrada_no_acerto_id = NEW.id;
    IF NEW.faltas_cobradas <> soma_faltas THEN
        RAISE EXCEPTION 'As faltas cobradas do acerto % (%) precisam ser a soma das pendências cobradas nele (%).', NEW.id,
            NEW.faltas_cobradas, soma_faltas USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM corona.remessas r WHERE r.acerto_id = NEW.id AND r.status <> 'ACERTADA') THEN
        RAISE EXCEPTION 'Todas as maletas do acerto % precisam terminar acertadas.', NEW.id USING ERRCODE = 'check_violation';
    END IF;

    -- O dinheiro (tarefa 7.6).
    SELECT coalesce(sum(valor), 0) INTO soma_recebido FROM corona.recebimentos_vendedor
     WHERE acerto_id = NEW.id AND aplicado_em = 'ACERTO' AND estorna_id IS NULL;
    IF NEW.recebido_no_acerto <> soma_recebido THEN
        RAISE EXCEPTION 'O recebido no acerto % (%) precisa ser a soma dos recebimentos dele (%).', NEW.id, NEW.recebido_no_acerto,
            soma_recebido USING ERRCODE = 'check_violation';
    END IF;
    SELECT coalesce(sum(valor), 0) INTO soma_contas FROM corona.contas_receber
     WHERE devedor_tipo = 'VENDEDOR' AND origem_tipo = 'ACERTO' AND origem_id = NEW.id;
    IF soma_contas <> greatest(NEW.ficou_devendo, 0) THEN
        RAISE EXCEPTION 'As contas a receber do acerto % (%) precisam somar o que o vendedor ficou devendo (%).', NEW.id, soma_contas,
            greatest(NEW.ficou_devendo, 0) USING ERRCODE = 'check_violation';
    END IF;
    SELECT coalesce(-sum(valor), 0) INTO soma_pago FROM corona.lancamentos_caixa
     WHERE origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' AND origem_id = NEW.id;
    IF NEW.pago_ao_vendedor <> soma_pago THEN
        RAISE EXCEPTION 'O pago ao vendedor no acerto % (%) precisa ser a saída de caixa dele (%).', NEW.id, NEW.pago_ao_vendedor,
            soma_pago USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.credito_do_acerto_id IS NOT NULL THEN
        SELECT status, vendedor_id, ficou_devendo INTO anterior FROM corona.acertos WHERE id = NEW.credito_do_acerto_id;
        IF NOT FOUND OR anterior.status <> 'ACERTADO' OR anterior.vendedor_id <> NEW.vendedor_id OR anterior.ficou_devendo >= 0
                OR NEW.credito_usado <> -anterior.ficou_devendo THEN
            RAISE EXCEPTION 'O crédito usado no acerto % precisa ser todo o crédito de um acerto anterior do mesmo vendedor.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    -- O vendedor não fica com outro crédito sem uso: todo acerto anterior a favor dele já foi abatido por um acerto.
    IF EXISTS (SELECT 1 FROM corona.acertos x
                WHERE x.vendedor_id = NEW.vendedor_id AND x.id <> NEW.id AND x.status = 'ACERTADO' AND x.ficou_devendo < 0
                  AND x.fechado_em < NEW.fechado_em
                  AND NOT EXISTS (SELECT 1 FROM corona.acertos y WHERE y.credito_do_acerto_id = x.id)) THEN
        RAISE EXCEPTION 'O acerto % deixou de abater um crédito anterior do vendedor.', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON corona.categorias_caixa, corona.recebimentos_vendedor, corona.lancamentos_caixa FROM PUBLIC;
