-- V042: devolução e troca de venda a prazo (Fase 6, tarefa 6.6; ADR-0014, item 10; ADR-0012, item 7; ESPECIFICACAO §7.3, §9
-- e §10; plano aprovado no PR #164).
--
-- A devolução de venda a prazo primeiro ABATE o que o cliente ainda deve (cancela as parcelas em aberto, da última para a
-- primeira, por baixa CANCELAMENTO com motivo DEVOLUCAO); o que passar disso volta como crédito (o que foi pago em crédito)
-- e depois em dinheiro. devolucoes.valor_abatido guarda o abatido, e dinheiro + crédito + abatido = valor devolvido, sempre.
-- "Cancelar devolução" desfaz o abatimento por estorno (baixa negativa), nunca por UPDATE. Nenhuma migração publicada é
-- editada: os CHECK e as funções são recriados inteiros, a partir da última definição de cada um (V038 e V041).

-- ---------------------------------------------------------------------------------------------
-- devolucoes: o que foi abatido das parcelas.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.devolucoes
    ADD COLUMN valor_abatido numeric(14, 2) NOT NULL DEFAULT 0
        CONSTRAINT devolucoes_valor_abatido_nao_negativo CHECK (valor_abatido >= 0);
COMMENT ON COLUMN corona.devolucoes.valor_abatido IS
    'O que a devolução abateu do que o cliente ainda devia nas parcelas (baixa CANCELAMENTO/DEVOLUCAO). Venda à vista e linha antiga: zero.';

-- A divisão é sempre exata: o serviço divide o valor devolvido inteiro (dinheiro, crédito e abatido).
ALTER TABLE corona.devolucoes DROP CONSTRAINT devolucoes_dinheiro_e_credito_cabem;
ALTER TABLE corona.devolucoes ADD CONSTRAINT devolucoes_dinheiro_e_credito_cabem
    CHECK (valor_reembolsado + valor_em_credito + valor_abatido = valor_devolvido);
ALTER TABLE corona.devolucoes DROP CONSTRAINT devolucoes_troca_em_credito;
ALTER TABLE corona.devolucoes ADD CONSTRAINT devolucoes_troca_em_credito
    CHECK (destino <> 'TROCA' OR (valor_reembolsado = 0 AND valor_em_credito + valor_abatido = valor_devolvido));
COMMENT ON COLUMN corona.devolucoes.valor_em_credito IS
    'O que voltou como crédito do cliente: na troca, o que passou do que A ainda devia; na devolução de venda paga com crédito, o que foi pago em crédito (depois do abatido).';

-- O gatilho de alteração da V038, inteiro, mais: o valor abatido nunca muda.
CREATE OR REPLACE FUNCTION corona.proteger_devolucao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.venda_id IS DISTINCT FROM OLD.venda_id OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
            OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.remessa_id IS DISTINCT FROM OLD.remessa_id
            OR NEW.motivo IS DISTINCT FROM OLD.motivo OR NEW.observacao IS DISTINCT FROM OLD.observacao
            OR NEW.destino IS DISTINCT FROM OLD.destino OR NEW.dentro_garantia IS DISTINCT FROM OLD.dentro_garantia
            OR NEW.autorizacao_id IS DISTINCT FROM OLD.autorizacao_id
            OR NEW.valor_devolvido IS DISTINCT FROM OLD.valor_devolvido
            OR NEW.valor_reembolsado IS DISTINCT FROM OLD.valor_reembolsado
            OR NEW.valor_em_credito IS DISTINCT FROM OLD.valor_em_credito
            OR NEW.valor_abatido IS DISTINCT FROM OLD.valor_abatido
            OR NEW.forma_reembolso IS DISTINCT FROM OLD.forma_reembolso OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id
            OR NEW.devolvida_em IS DISTINCT FROM OLD.devolvida_em OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada
            OR NEW.registrada_por IS DISTINCT FROM OLD.registrada_por OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
            OR NEW.revisao_fora_do_prazo IS DISTINCT FROM OLD.revisao_fora_do_prazo
            OR NEW.revisao_acima_do_limite_semanal IS DISTINCT FROM OLD.revisao_acima_do_limite_semanal
            OR NEW.revisao_acima_do_valor_de_aprovacao IS DISTINCT FROM OLD.revisao_acima_do_valor_de_aprovacao
            OR NEW.revisao_autorizacao_invalida IS DISTINCT FROM OLD.revisao_autorizacao_invalida
            OR NEW.revisao_reembolso_maior_que_pago IS DISTINCT FROM OLD.revisao_reembolso_maior_que_pago THEN
        RAISE EXCEPTION 'A devolução % não pode ser alterada: a correção é cancelar a devolução.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.situacao IS DISTINCT FROM OLD.situacao THEN
        IF NOT ((OLD.situacao = 'ACEITA' AND NEW.situacao = 'CANCELADA')
                OR (OLD.situacao = 'EM_REVISAO' AND NEW.situacao IN ('CANCELADA', 'MANTIDA', 'NAO_ACEITA'))) THEN
            RAISE EXCEPTION 'A devolução % não pode passar de % para %.', OLD.id, OLD.situacao, NEW.situacao
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.situacao = 'CANCELADA' AND OLD.destino = 'TROCA'
           AND NOT EXISTS (SELECT 1 FROM corona.trocas t WHERE t.devolucao_id = OLD.id AND t.situacao = 'CANCELADA') THEN
            RAISE EXCEPTION 'A devolução % é de uma troca: ela só se desfaz pelo "Cancelar troca".', OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    IF (NEW.cancelamento_id IS DISTINCT FROM OLD.cancelamento_id OR NEW.cancelada_em IS DISTINCT FROM OLD.cancelada_em
            OR NEW.cancelada_por IS DISTINCT FROM OLD.cancelada_por
            OR NEW.motivo_cancelamento IS DISTINCT FROM OLD.motivo_cancelamento)
       AND NOT (NEW.situacao = 'CANCELADA' AND OLD.situacao IS DISTINCT FROM 'CANCELADA') THEN
        RAISE EXCEPTION 'O cancelamento da devolução % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.revisada_em IS DISTINCT FROM OLD.revisada_em OR NEW.revisada_por IS DISTINCT FROM OLD.revisada_por
            OR NEW.observacao_revisao IS DISTINCT FROM OLD.observacao_revisao)
       AND NOT (OLD.situacao = 'EM_REVISAO' AND NEW.situacao IN ('MANTIDA', 'NAO_ACEITA')) THEN
        RAISE EXCEPTION 'A revisão da devolução % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- baixas_receber: o cancelamento por devolução e o estorno dele.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.baixas_receber ADD COLUMN devolucao_id uuid REFERENCES corona.devolucoes (id);
COMMENT ON COLUMN corona.baixas_receber.devolucao_id IS
    'A devolução que abateu a parcela (cancelamento com motivo DEVOLUCAO e o estorno dele). Nas outras baixas, vazio.';
CREATE INDEX baixas_receber_por_devolucao ON corona.baixas_receber (devolucao_id) WHERE devolucao_id IS NOT NULL;

ALTER TABLE corona.baixas_receber DROP CONSTRAINT baixas_receber_motivo_conhecido;
ALTER TABLE corona.baixas_receber ADD CONSTRAINT baixas_receber_motivo_conhecido
    CHECK (motivo_cancelamento IN ('ESTORNO_VENDA', 'DEVOLUCAO'));
ALTER TABLE corona.baixas_receber DROP CONSTRAINT baixas_receber_forma;
ALTER TABLE corona.baixas_receber ADD CONSTRAINT baixas_receber_forma CHECK (
    (tipo = 'PAGAMENTO' AND pagamento_cliente_id IS NOT NULL AND motivo_cancelamento IS NULL AND devolucao_id IS NULL
        AND ((estorna_id IS NULL AND valor > 0) OR (estorna_id IS NOT NULL AND valor < 0)))
    OR (tipo = 'CANCELAMENTO' AND pagamento_cliente_id IS NULL AND motivo_cancelamento IS NOT NULL AND motivo_cancelamento = 'ESTORNO_VENDA'
        AND devolucao_id IS NULL AND estorna_id IS NULL AND valor > 0)
    OR (tipo = 'CANCELAMENTO' AND pagamento_cliente_id IS NULL AND motivo_cancelamento IS NOT NULL AND motivo_cancelamento = 'DEVOLUCAO'
        AND devolucao_id IS NOT NULL AND ((estorna_id IS NULL AND valor > 0) OR (estorna_id IS NOT NULL AND valor < 0))));

-- A função da V041, inteira, mais: o cancelamento por devolução é de devolução da MESMA venda da parcela e não passa do
-- abatido gravado nela; o estorno dele desfaz um cancelamento por devolução original, da mesma conta e da mesma
-- devolução, pelo mesmo valor; e o cancelado também nunca fica negativo.
CREATE OR REPLACE FUNCTION corona.conferir_baixa_receber() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c record;
    p record;
    o record;
    d record;
    pago numeric(14, 2);
    cancelado numeric(14, 2);
    distribuido numeric(14, 2);
    abatido numeric(14, 2);
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
    SELECT coalesce(sum(valor) FILTER (WHERE tipo = 'PAGAMENTO'), 0), coalesce(sum(valor) FILTER (WHERE tipo = 'CANCELAMENTO'), 0)
      INTO pago, cancelado FROM corona.baixas_receber WHERE conta_id = NEW.conta_id;
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

-- ---------------------------------------------------------------------------------------------
-- contas_receber: PARCIAL também vale para a parcela abatida (em parte) por devolução.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.contas_receber DROP CONSTRAINT contas_receber_status_confere;
ALTER TABLE corona.contas_receber ADD CONSTRAINT contas_receber_status_confere CHECK (
    (status = 'ABERTA' AND valor_pago = 0 AND valor_cancelado = 0)
    OR (status = 'PARCIAL' AND valor_pago + valor_cancelado > 0 AND valor_pago + valor_cancelado < valor)
    OR (status = 'PAGA' AND valor_pago = valor AND valor_cancelado = 0)
    OR (status = 'CANCELADA' AND valor_cancelado > 0 AND valor_pago + valor_cancelado = valor));
COMMENT ON COLUMN corona.contas_receber.status IS
    'ABERTA | PARCIAL (parte paga, parte abatida por devolução, ou as duas) | PAGA | CANCELADA. "Vencida" é calculada pela data de hoje em Brasília, nunca gravada.';

REVOKE ALL ON corona.baixas_receber FROM PUBLIC;
