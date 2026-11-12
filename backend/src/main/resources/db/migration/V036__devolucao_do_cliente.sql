-- V036: devolução do cliente (Fase 6, tarefa 6.3; ADR-0014, itens 1 a 7 e 11; ESPECIFICACAO §7.3 e §9; plano aprovado
-- no PR #125).
--
-- A devolução é um registro novo: a venda não muda (continua CONCLUIDA) e o quanto já voltou é a soma das devoluções
-- não canceladas dela. A peça boa de maleta aberta volta ao saldo (movimento DEVOLUCAO_CLIENTE na maleta original); a
-- boa de maleta acertada e toda peça com defeito ficam como "peça para a loja", sem movimento. O dinheiro volta como
-- pagamentos_cliente REEMBOLSO negativo ligado à devolução. "Cancelar devolução" é registro novo de sinal oposto
-- (estorno do movimento e ESTORNO_REEMBOLSO), e só a situação da devolução muda. Dinheiro em NUMERIC(14,2), tudo em
-- Real (ADR-0006); datas em UTC. Nenhuma migração publicada é editada: as funções e CHECKs antigos são recriados aqui.

-- ---------------------------------------------------------------------------------------------
-- devolucoes: a devolução (sincronizável; a situação muda, o conteúdo nunca).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.devolucoes (
    id uuid PRIMARY KEY,
    -- Os quatro iguais aos da venda (gatilho devolucoes_da_venda).
    venda_id uuid NOT NULL REFERENCES corona.vendas (id),
    cliente_id uuid NOT NULL REFERENCES corona.clientes (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    motivo text NOT NULL CONSTRAINT devolucoes_motivo_conhecido
        CHECK (motivo IN ('ARREPENDIMENTO', 'DEFEITO', 'TAMANHO', 'OUTRO')),
    observacao text CONSTRAINT devolucoes_observacao_preenchida
        CHECK (observacao IS NULL OR (btrim(observacao) <> '' AND length(observacao) <= 400)),
    -- Destino do valor: nesta tarefa só REEMBOLSO; TROCA chega com a tarefa 6.5.
    destino text NOT NULL CONSTRAINT devolucoes_destino_conhecido CHECK (destino IN ('REEMBOLSO', 'TROCA')),
    dentro_garantia boolean NOT NULL,
    situacao text NOT NULL CONSTRAINT devolucoes_situacao_conhecida
        CHECK (situacao IN ('ACEITA', 'EM_REVISAO', 'MANTIDA', 'NAO_ACEITA', 'CANCELADA')),
    -- Pedido de autorização usado (ADR-0014, item 6). Sem chave estrangeira: a tabela e a chave chegam com a tarefa 6.4.
    autorizacao_id uuid,
    -- Calculados pelo servidor (o app nunca manda valor).
    valor_devolvido numeric(14, 2) NOT NULL CONSTRAINT devolucoes_valor_devolvido_nao_negativo CHECK (valor_devolvido >= 0),
    valor_reembolsado numeric(14, 2) NOT NULL CONSTRAINT devolucoes_valor_reembolsado_nao_negativo CHECK (valor_reembolsado >= 0),
    forma_reembolso text CONSTRAINT devolucoes_forma_conhecida CHECK (forma_reembolso IN ('DINHEIRO', 'PIX', 'CARTAO', 'CHEQUE')),
    -- Cotação do dia da devolução, só para mostrar Dólar e Guarani (ADR-0006). Vazio se não havia cotação.
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    devolvida_em timestamptz NOT NULL,
    -- A hora foi corrigida pelo servidor em mais de 5 minutos (relógio do computador errado; ADR-0013, item 11).
    hora_ajustada boolean NOT NULL DEFAULT false,
    registrada_por uuid NOT NULL REFERENCES corona.usuarios (id),
    -- Cancelar devolução (ADR-0014, item 11): o id vem do aplicativo (idempotência).
    cancelamento_id uuid,
    cancelada_em timestamptz,
    cancelada_por uuid REFERENCES corona.usuarios (id),
    motivo_cancelamento text CONSTRAINT devolucoes_motivo_cancelamento_preenchido
        CHECK (motivo_cancelamento IS NULL OR (btrim(motivo_cancelamento) <> '' AND length(motivo_cancelamento) <= 400)),
    CONSTRAINT devolucoes_outro_com_observacao CHECK (motivo <> 'OUTRO' OR observacao IS NOT NULL),
    CONSTRAINT devolucoes_reembolso_cabe CHECK (valor_reembolsado <= valor_devolvido),
    CONSTRAINT devolucoes_forma_do_reembolso CHECK (valor_reembolsado = 0 OR forma_reembolso IS NOT NULL),
    CONSTRAINT devolucoes_cancelamento_completo CHECK (
        (situacao = 'CANCELADA') = (cancelamento_id IS NOT NULL)
        AND (cancelamento_id IS NULL) = (cancelada_em IS NULL)
        AND (cancelamento_id IS NULL) = (cancelada_por IS NULL)
        AND (cancelamento_id IS NULL) = (motivo_cancelamento IS NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.devolucoes');
COMMENT ON TABLE corona.devolucoes IS
    'Devolução do cliente (ADR-0014). A venda não muda; só a situação da devolução muda (gatilho). Corrigir = cancelar a devolução (registro de sinal oposto).';
COMMENT ON COLUMN corona.devolucoes.situacao IS
    'ACEITA ou EM_REVISAO ao nascer; ACEITA -> CANCELADA; EM_REVISAO -> CANCELADA, MANTIDA ou NAO_ACEITA (a dona decide, tarefa 6.4).';
CREATE INDEX devolucoes_por_venda ON corona.devolucoes (venda_id);
CREATE INDEX devolucoes_por_vendedor ON corona.devolucoes (vendedor_id, devolvida_em DESC);
CREATE UNIQUE INDEX devolucoes_cancelamento_unico ON corona.devolucoes (cancelamento_id) WHERE cancelamento_id IS NOT NULL;

-- A devolução nasce de uma venda concluída e copia dela o cliente, o vendedor e a maleta (segunda trava do "venda
-- estornada não recebe devolução"). Trava a linha da venda: a mesma que o serviço trava depois da maleta.
CREATE FUNCTION corona.conferir_devolucao_da_venda() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v record;
BEGIN
    SELECT vendedor_id, cliente_id, remessa_id, status INTO v FROM corona.vendas WHERE id = NEW.venda_id FOR UPDATE;
    IF v.status IS DISTINCT FROM 'CONCLUIDA' THEN
        RAISE EXCEPTION 'A venda % não está concluída: só venda concluída recebe devolução.', NEW.venda_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.vendedor_id IS DISTINCT FROM v.vendedor_id OR NEW.cliente_id IS DISTINCT FROM v.cliente_id
            OR NEW.remessa_id IS DISTINCT FROM v.remessa_id THEN
        RAISE EXCEPTION 'A devolução % precisa ter o vendedor, o cliente e a maleta da venda %.', NEW.id, NEW.venda_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.situacao NOT IN ('ACEITA', 'EM_REVISAO') THEN
        RAISE EXCEPTION 'A devolução % nasce aceita ou para revisão.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER devolucoes_da_venda BEFORE INSERT ON corona.devolucoes
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_devolucao_da_venda();

-- Nada muda além da situação (nas transições permitidas) e dos dados do cancelamento. deleted_at nunca.
CREATE FUNCTION corona.proteger_devolucao() RETURNS trigger
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
            OR NEW.forma_reembolso IS DISTINCT FROM OLD.forma_reembolso OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id
            OR NEW.devolvida_em IS DISTINCT FROM OLD.devolvida_em OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada
            OR NEW.registrada_por IS DISTINCT FROM OLD.registrada_por OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'A devolução % não pode ser alterada: a correção é cancelar a devolução.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.situacao IS DISTINCT FROM OLD.situacao THEN
        IF NOT ((OLD.situacao = 'ACEITA' AND NEW.situacao = 'CANCELADA')
                OR (OLD.situacao = 'EM_REVISAO' AND NEW.situacao IN ('CANCELADA', 'MANTIDA', 'NAO_ACEITA'))) THEN
            RAISE EXCEPTION 'A devolução % não pode passar de % para %.', OLD.id, OLD.situacao, NEW.situacao
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF NEW.cancelamento_id IS DISTINCT FROM OLD.cancelamento_id OR NEW.cancelada_em IS DISTINCT FROM OLD.cancelada_em
            OR NEW.cancelada_por IS DISTINCT FROM OLD.cancelada_por
            OR NEW.motivo_cancelamento IS DISTINCT FROM OLD.motivo_cancelamento THEN
        RAISE EXCEPTION 'O cancelamento da devolução % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER devolucoes_conteudo_imutavel BEFORE UPDATE ON corona.devolucoes
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_devolucao();

-- ---------------------------------------------------------------------------------------------
-- itens_devolucao: uma linha por peça e estado (sincronizável, imutável).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.itens_devolucao (
    id uuid PRIMARY KEY,
    devolucao_id uuid NOT NULL REFERENCES corona.devolucoes (id),
    item_venda_id uuid NOT NULL REFERENCES corona.itens_venda (id),
    quantidade integer NOT NULL CONSTRAINT itens_devolucao_quantidade_positiva CHECK (quantidade > 0),
    -- O preço pago pelas unidades devolvidas (ADR-0014, item 3), calculado pelo servidor.
    valor numeric(14, 2) NOT NULL CONSTRAINT itens_devolucao_valor_nao_negativo CHECK (valor >= 0),
    estado_peca text NOT NULL CONSTRAINT itens_devolucao_estado_conhecido CHECK (estado_peca IN ('BOA', 'DEFEITO')),
    destino_peca text NOT NULL CONSTRAINT itens_devolucao_destino_conhecido CHECK (destino_peca IN ('MALETA', 'LOJA')),
    -- Peça com defeito nunca volta ao saldo da maleta (ADR-0014, item 7).
    CONSTRAINT itens_devolucao_defeito_vai_para_a_loja CHECK (estado_peca = 'BOA' OR destino_peca = 'LOJA')
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.itens_devolucao', imutavel => true);
COMMENT ON TABLE corona.itens_devolucao IS
    'Peças da devolução, imutáveis. destino_peca LOJA = "peça para a loja": não volta à maleta e é esperada no próximo acerto.';
CREATE UNIQUE INDEX itens_devolucao_peca_unica ON corona.itens_devolucao (devolucao_id, item_venda_id, estado_peca);
CREATE INDEX itens_devolucao_por_item_da_venda ON corona.itens_devolucao (item_venda_id);

-- Segunda trava do "nunca mais que o vendido" (como a V024 no saldo da maleta): por linha da venda, a soma das
-- devoluções não canceladas não passa da quantidade vendida nem do total pago pela linha. Trava a linha da venda
-- antes de somar (a mesma que o serviço trava): duas gravações por fora do serviço somam uma depois da outra.
CREATE FUNCTION corona.conferir_item_de_devolucao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    venda_da_devolucao uuid;
    iv record;
    quantidade_devolvida bigint;
    valor_devolvido numeric;
BEGIN
    SELECT venda_id INTO venda_da_devolucao FROM corona.devolucoes WHERE id = NEW.devolucao_id;
    PERFORM 1 FROM corona.vendas WHERE id = venda_da_devolucao FOR UPDATE;
    SELECT venda_id, quantidade, preco_unitario * quantidade - desconto AS total INTO iv
      FROM corona.itens_venda WHERE id = NEW.item_venda_id;
    IF iv.venda_id IS DISTINCT FROM venda_da_devolucao THEN
        RAISE EXCEPTION 'A peça % não é da venda da devolução %.', NEW.item_venda_id, NEW.devolucao_id
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT coalesce(sum(i.quantidade), 0), coalesce(sum(i.valor), 0) INTO quantidade_devolvida, valor_devolvido
      FROM corona.itens_devolucao i JOIN corona.devolucoes d ON d.id = i.devolucao_id
     WHERE i.item_venda_id = NEW.item_venda_id AND d.situacao <> 'CANCELADA';
    IF quantidade_devolvida > iv.quantidade OR valor_devolvido > iv.total THEN
        RAISE EXCEPTION 'A linha % da venda ficaria com % peça(s) e R$ % devolvidos, mais que o vendido (% peça(s), R$ %).',
            NEW.item_venda_id, quantidade_devolvida, valor_devolvido, iv.quantidade, iv.total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER itens_devolucao_nunca_acima_do_vendido AFTER INSERT ON corona.itens_devolucao
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_item_de_devolucao();

-- ---------------------------------------------------------------------------------------------
-- A pertença de devolucoes não muda (ADR-0013, item 5; RegrasDoVendedorTest): a função da V032 ganha o ramo novo.
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
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.devolucoes
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();

-- ---------------------------------------------------------------------------------------------
-- pagamentos_cliente: o reembolso da devolução e o estorno dele (ESTORNO_REEMBOLSO, positivo, só por "Cancelar devolução").
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.pagamentos_cliente
    ADD COLUMN devolucao_id uuid CONSTRAINT pagamentos_cliente_devolucao_fk REFERENCES corona.devolucoes (id),
    ADD COLUMN estorna_id uuid CONSTRAINT pagamentos_cliente_estorna_fk REFERENCES corona.pagamentos_cliente (id);
COMMENT ON COLUMN corona.pagamentos_cliente.devolucao_id IS
    'A devolução do reembolso (ou do estorno dele). Vazio no pagamento e no reembolso do estorno da venda inteira.';
COMMENT ON COLUMN corona.pagamentos_cliente.estorna_id IS
    'O reembolso que este ESTORNO_REEMBOLSO desfaz (Cancelar devolução). Um estorno por reembolso.';

ALTER TABLE corona.pagamentos_cliente DROP CONSTRAINT pagamentos_cliente_tipo_conhecido;
ALTER TABLE corona.pagamentos_cliente ADD CONSTRAINT pagamentos_cliente_tipo_conhecido
    CHECK (tipo IN ('PAGAMENTO', 'REEMBOLSO', 'ESTORNO_REEMBOLSO'));
ALTER TABLE corona.pagamentos_cliente DROP CONSTRAINT pagamentos_cliente_sinal;
ALTER TABLE corona.pagamentos_cliente ADD CONSTRAINT pagamentos_cliente_sinal CHECK (
    (tipo = 'PAGAMENTO' AND valor > 0 AND estorna_id IS NULL)
    OR (tipo = 'REEMBOLSO' AND valor < 0 AND estorna_id IS NULL)
    OR (tipo = 'ESTORNO_REEMBOLSO' AND valor > 0 AND estorna_id IS NOT NULL AND devolucao_id IS NOT NULL));
ALTER TABLE corona.pagamentos_cliente ADD CONSTRAINT pagamentos_cliente_devolucao_so_no_reembolso
    CHECK (devolucao_id IS NULL OR tipo IN ('REEMBOLSO', 'ESTORNO_REEMBOLSO'));
CREATE UNIQUE INDEX pagamentos_cliente_estorno_unico ON corona.pagamentos_cliente (estorna_id) WHERE estorna_id IS NOT NULL;
CREATE INDEX pagamentos_cliente_por_devolucao ON corona.pagamentos_cliente (devolucao_id) WHERE devolucao_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- movimentos_remessa: a peça boa que volta à maleta aponta para a devolução (e continua com o venda_id da venda).
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    sem_devolucao bigint;
BEGIN
    SELECT count(*) INTO sem_devolucao FROM corona.movimentos_remessa WHERE tipo = 'DEVOLUCAO_CLIENTE';
    IF sem_devolucao > 0 THEN
        RAISE EXCEPTION 'Há % movimento(s) DEVOLUCAO_CLIENTE sem devolução; corrija antes de aplicar a V036.', sem_devolucao;
    END IF;
END
$$;

ALTER TABLE corona.movimentos_remessa
    ADD COLUMN devolucao_id uuid CONSTRAINT movimentos_remessa_devolucao_fk REFERENCES corona.devolucoes (id);
ALTER TABLE corona.movimentos_remessa ADD CONSTRAINT movimentos_remessa_devolucao_do_tipo
    CHECK ((tipo = 'DEVOLUCAO_CLIENTE') = (devolucao_id IS NOT NULL));
COMMENT ON COLUMN corona.movimentos_remessa.devolucao_id IS
    'A devolução do cliente deste movimento DEVOLUCAO_CLIENTE (e do estorno dele, quando a devolução é cancelada).';
CREATE INDEX movimentos_remessa_por_devolucao ON corona.movimentos_remessa (devolucao_id) WHERE devolucao_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- vendas: segunda trava da dúvida R6. Venda com devolução não cancelada não é estornada.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION corona.recusar_estorno_com_devolucao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CONCLUIDA' AND NEW.status = 'ESTORNADA'
       AND EXISTS (SELECT 1 FROM corona.devolucoes d WHERE d.venda_id = OLD.id AND d.situacao <> 'CANCELADA') THEN
        RAISE EXCEPTION 'A venda % tem devolução: cancele a devolução antes de estornar.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vendas_estorno_sem_devolucao BEFORE UPDATE ON corona.vendas
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_estorno_com_devolucao();

REVOKE ALL ON corona.devolucoes, corona.itens_devolucao FROM PUBLIC;
