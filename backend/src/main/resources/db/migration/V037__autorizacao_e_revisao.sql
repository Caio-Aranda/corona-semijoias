-- V037: autorização da dona, revisão da devolução e antifraude (Fase 6, tarefa 6.4; ADR-0014, itens 5, 6, 10 e 12;
-- ESPECIFICACAO §7.3 e §9; plano aprovado no PR #126, com o ajuste da dúvida P2: uma coluna booleana por motivo).
--
-- O vendedor pede autorização (autorizacoes_excecao + itens_autorizacao); a dona aprova ou nega; a aprovada vale até
-- valida_ate (dia de Brasília) e serve para UMA devolução (índice único em devolucoes.autorizacao_id). A devolução que o
-- servidor acha estranha nasce EM_REVISAO com os motivos marcados, e a dona decide MANTIDA ou NAO_ACEITA sem mexer em
-- movimento, pagamento ou valor. Dinheiro em NUMERIC(14,2), tudo em Real (ADR-0006); datas em UTC. Nenhuma migração
-- publicada é editada: as funções da V036 são recriadas aqui, inteiras.

-- ---------------------------------------------------------------------------------------------
-- autorizacoes_excecao: o pedido de autorização (sincronizável; a situação e a decisão mudam, o conteúdo nunca).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.autorizacoes_excecao (
    id uuid PRIMARY KEY,
    -- Os três iguais aos da venda (gatilho autorizacoes_da_venda).
    venda_id uuid NOT NULL REFERENCES corona.vendas (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    cliente_id uuid NOT NULL REFERENCES corona.clientes (id),
    motivo text NOT NULL CONSTRAINT autorizacoes_motivo_conhecido
        CHECK (motivo IN ('ARREPENDIMENTO', 'DEFEITO', 'TAMANHO', 'OUTRO')),
    observacao text CONSTRAINT autorizacoes_observacao_preenchida
        CHECK (observacao IS NULL OR (btrim(observacao) <> '' AND length(observacao) <= 400)),
    -- Decidido pelo servidor (o app nunca manda tipo nem valor).
    tipo text NOT NULL CONSTRAINT autorizacoes_tipo_conhecido CHECK (tipo IN ('FORA_DO_PRAZO', 'ACIMA_DO_VALOR')),
    situacao text NOT NULL CONSTRAINT autorizacoes_situacao_conhecida
        CHECK (situacao IN ('PEDIDA', 'APROVADA', 'NEGADA', 'USADA', 'VENCIDA')),
    -- O preço pago das peças pedidas, calculado pelo servidor na hora do pedido: é o que a dona aprova.
    valor_pedido numeric(14, 2) NOT NULL CONSTRAINT autorizacoes_valor_nao_negativo CHECK (valor_pedido >= 0),
    -- Cotação do dia do pedido, só para mostrar Dólar e Guarani (ADR-0006). Vazio se não havia cotação.
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    pedida_em timestamptz NOT NULL,
    hora_ajustada boolean NOT NULL DEFAULT false,
    pedida_por uuid NOT NULL REFERENCES corona.usuarios (id),
    -- A decisão da dona.
    decidida_em timestamptz,
    decidida_por uuid REFERENCES corona.usuarios (id),
    motivo_decisao text CONSTRAINT autorizacoes_motivo_decisao_preenchido
        CHECK (motivo_decisao IS NULL OR (btrim(motivo_decisao) <> '' AND length(motivo_decisao) <= 400)),
    -- Último dia (Brasília) em que a autorização aprovada vale: dia da aprovação + 7 (dúvida P7).
    valida_ate date,
    CONSTRAINT autorizacoes_outro_com_observacao CHECK (motivo <> 'OUTRO' OR observacao IS NOT NULL),
    -- Tudo ou nada: pedida sem decisão; negada com quem, quando e motivo, sem validade; aprovada em diante com validade.
    CONSTRAINT autorizacoes_decisao_completa CHECK (
        (situacao = 'PEDIDA' AND decidida_em IS NULL AND decidida_por IS NULL AND motivo_decisao IS NULL AND valida_ate IS NULL)
        OR (situacao = 'NEGADA' AND decidida_em IS NOT NULL AND decidida_por IS NOT NULL AND motivo_decisao IS NOT NULL
            AND valida_ate IS NULL)
        OR (situacao IN ('APROVADA', 'USADA', 'VENCIDA') AND decidida_em IS NOT NULL AND decidida_por IS NOT NULL
            AND valida_ate IS NOT NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.autorizacoes_excecao');
COMMENT ON TABLE corona.autorizacoes_excecao IS
    'Pedido de autorização da dona para uma devolução fora do prazo ou acima do valor (ADR-0014, item 6). Só a situação e a decisão mudam (gatilho).';
COMMENT ON COLUMN corona.autorizacoes_excecao.situacao IS
    'PEDIDA -> APROVADA | NEGADA (a dona); APROVADA -> USADA (a devolução que a usa) | VENCIDA (agenda diária).';
CREATE INDEX autorizacoes_por_vendedor ON corona.autorizacoes_excecao (vendedor_id, pedida_em DESC);
CREATE INDEX autorizacoes_por_venda ON corona.autorizacoes_excecao (venda_id);
CREATE INDEX autorizacoes_pedidas ON corona.autorizacoes_excecao (pedida_em) WHERE situacao = 'PEDIDA';

-- O pedido nasce de uma venda concluída, com o vendedor e o cliente dela, e nasce PEDIDA.
CREATE FUNCTION corona.conferir_autorizacao_da_venda() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v record;
BEGIN
    SELECT vendedor_id, cliente_id, status INTO v FROM corona.vendas WHERE id = NEW.venda_id;
    IF v.status IS DISTINCT FROM 'CONCLUIDA' THEN
        RAISE EXCEPTION 'A venda % não está concluída: só venda concluída recebe pedido de autorização.', NEW.venda_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.vendedor_id IS DISTINCT FROM v.vendedor_id OR NEW.cliente_id IS DISTINCT FROM v.cliente_id THEN
        RAISE EXCEPTION 'O pedido de autorização % precisa ter o vendedor e o cliente da venda %.', NEW.id, NEW.venda_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.situacao IS DISTINCT FROM 'PEDIDA' THEN
        RAISE EXCEPTION 'O pedido de autorização % nasce esperando a dona.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER autorizacoes_da_venda BEFORE INSERT ON corona.autorizacoes_excecao
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_autorizacao_da_venda();

-- Nada muda além da situação (nas transições permitidas) e dos dados da decisão (só junto com PEDIDA -> APROVADA |
-- NEGADA). APROVADA -> USADA só com a devolução que a usa já gravada (segunda trava do uso). deleted_at nunca.
CREATE FUNCTION corona.proteger_autorizacao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.venda_id IS DISTINCT FROM OLD.venda_id OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id
            OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id OR NEW.motivo IS DISTINCT FROM OLD.motivo
            OR NEW.observacao IS DISTINCT FROM OLD.observacao OR NEW.tipo IS DISTINCT FROM OLD.tipo
            OR NEW.valor_pedido IS DISTINCT FROM OLD.valor_pedido OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id
            OR NEW.pedida_em IS DISTINCT FROM OLD.pedida_em OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada
            OR NEW.pedida_por IS DISTINCT FROM OLD.pedida_por OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'O pedido de autorização % não pode ser alterado.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.situacao IS DISTINCT FROM OLD.situacao THEN
        IF NOT ((OLD.situacao = 'PEDIDA' AND NEW.situacao IN ('APROVADA', 'NEGADA'))
                OR (OLD.situacao = 'APROVADA' AND NEW.situacao IN ('USADA', 'VENCIDA'))) THEN
            RAISE EXCEPTION 'O pedido de autorização % não pode passar de % para %.', OLD.id, OLD.situacao, NEW.situacao
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.situacao = 'USADA'
           AND NOT EXISTS (SELECT 1 FROM corona.devolucoes d WHERE d.autorizacao_id = OLD.id) THEN
            RAISE EXCEPTION 'O pedido de autorização % só fica usado com a devolução que o usa.', OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    IF (NEW.decidida_em IS DISTINCT FROM OLD.decidida_em OR NEW.decidida_por IS DISTINCT FROM OLD.decidida_por
            OR NEW.motivo_decisao IS DISTINCT FROM OLD.motivo_decisao OR NEW.valida_ate IS DISTINCT FROM OLD.valida_ate)
       AND NOT (OLD.situacao = 'PEDIDA' AND NEW.situacao IN ('APROVADA', 'NEGADA')) THEN
        RAISE EXCEPTION 'A decisão do pedido de autorização % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER autorizacoes_conteudo_imutavel BEFORE UPDATE ON corona.autorizacoes_excecao
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_autorizacao();

-- ---------------------------------------------------------------------------------------------
-- itens_autorizacao: as peças pedidas, por linha da venda (sincronizável, imutável; dúvida P1).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.itens_autorizacao (
    id uuid PRIMARY KEY,
    autorizacao_id uuid NOT NULL REFERENCES corona.autorizacoes_excecao (id),
    item_venda_id uuid NOT NULL REFERENCES corona.itens_venda (id),
    quantidade integer NOT NULL CONSTRAINT itens_autorizacao_quantidade_positiva CHECK (quantidade > 0),
    CONSTRAINT itens_autorizacao_peca_unica UNIQUE (autorizacao_id, item_venda_id)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.itens_autorizacao', imutavel => true);
COMMENT ON TABLE corona.itens_autorizacao IS
    'Peças do pedido de autorização, imutáveis: a devolução que usa a autorização leva no máximo estas quantidades.';

-- A linha é da venda do pedido e a quantidade não passa da vendida.
CREATE FUNCTION corona.conferir_item_de_autorizacao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    venda_do_pedido uuid;
    iv record;
BEGIN
    SELECT venda_id INTO venda_do_pedido FROM corona.autorizacoes_excecao WHERE id = NEW.autorizacao_id;
    SELECT venda_id, quantidade INTO iv FROM corona.itens_venda WHERE id = NEW.item_venda_id;
    IF iv.venda_id IS DISTINCT FROM venda_do_pedido THEN
        RAISE EXCEPTION 'A peça % não é da venda do pedido de autorização %.', NEW.item_venda_id, NEW.autorizacao_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.quantidade > iv.quantidade THEN
        RAISE EXCEPTION 'O pedido de autorização % pede % peça(s) da linha %, mais que as % vendidas.', NEW.autorizacao_id,
            NEW.quantidade, NEW.item_venda_id, iv.quantidade USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER itens_autorizacao_da_venda BEFORE INSERT ON corona.itens_autorizacao
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_item_de_autorizacao();

-- ---------------------------------------------------------------------------------------------
-- devolucoes: a chave da autorização, uma devolução por autorização e a revisão da dona.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    decididas bigint;
    sem_autorizacao bigint;
BEGIN
    -- Nenhum caminho da 6.3 grava MANTIDA nem NAO_ACEITA, e nenhum grava autorizacao_id.
    SELECT count(*) INTO decididas FROM corona.devolucoes WHERE situacao IN ('MANTIDA', 'NAO_ACEITA');
    SELECT count(*) INTO sem_autorizacao FROM corona.devolucoes WHERE autorizacao_id IS NOT NULL;
    IF decididas > 0 OR sem_autorizacao > 0 THEN
        RAISE EXCEPTION 'Há % devolução(ões) já decidida(s) e % com autorização sem pedido; corrija antes de aplicar a V037.',
            decididas, sem_autorizacao;
    END IF;
END
$$;

ALTER TABLE corona.devolucoes
    ADD CONSTRAINT devolucoes_autorizacao_fk FOREIGN KEY (autorizacao_id) REFERENCES corona.autorizacoes_excecao (id),
    -- Por que a devolução está para revisão: uma coluna por motivo (ajuste da dúvida P2). A tarefa 6.5 acrescenta a do
    -- crédito por migração nova.
    ADD COLUMN revisao_fora_do_prazo boolean NOT NULL DEFAULT false,
    ADD COLUMN revisao_acima_do_limite_semanal boolean NOT NULL DEFAULT false,
    ADD COLUMN revisao_acima_do_valor_de_aprovacao boolean NOT NULL DEFAULT false,
    ADD COLUMN revisao_autorizacao_invalida boolean NOT NULL DEFAULT false,
    ADD COLUMN revisao_reembolso_maior_que_pago boolean NOT NULL DEFAULT false,
    -- A decisão da dona (Manter ou Não aceitar).
    ADD COLUMN revisada_em timestamptz,
    ADD COLUMN revisada_por uuid CONSTRAINT devolucoes_revisada_por_fk REFERENCES corona.usuarios (id),
    ADD COLUMN observacao_revisao text CONSTRAINT devolucoes_observacao_revisao_preenchida
        CHECK (observacao_revisao IS NULL OR (btrim(observacao_revisao) <> '' AND length(observacao_revisao) <= 400));
COMMENT ON COLUMN corona.devolucoes.revisao_fora_do_prazo IS 'Para revisão: fora do prazo da garantia, sem autorização válida.';
COMMENT ON COLUMN corona.devolucoes.revisao_acima_do_limite_semanal IS 'Para revisão: passou do limite de reembolso da semana do vendedor.';
COMMENT ON COLUMN corona.devolucoes.revisao_acima_do_valor_de_aprovacao IS 'Para revisão: passou do valor que a dona aprova antes, sem autorização.';
COMMENT ON COLUMN corona.devolucoes.revisao_autorizacao_invalida IS 'Para revisão: veio com uma autorização que não vale (não consumida).';
COMMENT ON COLUMN corona.devolucoes.revisao_reembolso_maior_que_pago IS 'Para revisão: os reembolsos da venda passam do que o cliente pagou.';

-- A 6.3 só criava revisão por prazo: as EM_REVISAO que já existem ganham esse motivo antes dos CHECKs e do gatilho novo.
UPDATE corona.devolucoes SET revisao_fora_do_prazo = true WHERE situacao = 'EM_REVISAO';

ALTER TABLE corona.devolucoes ADD CONSTRAINT devolucoes_motivos_da_revisao CHECK (
    (situacao <> 'ACEITA' OR NOT (revisao_fora_do_prazo OR revisao_acima_do_limite_semanal
        OR revisao_acima_do_valor_de_aprovacao OR revisao_autorizacao_invalida OR revisao_reembolso_maior_que_pago))
    AND (situacao NOT IN ('EM_REVISAO', 'MANTIDA', 'NAO_ACEITA') OR revisao_fora_do_prazo OR revisao_acima_do_limite_semanal
        OR revisao_acima_do_valor_de_aprovacao OR revisao_autorizacao_invalida OR revisao_reembolso_maior_que_pago));
ALTER TABLE corona.devolucoes ADD CONSTRAINT devolucoes_revisao_completa CHECK (
    (situacao IN ('MANTIDA', 'NAO_ACEITA')) = (revisada_em IS NOT NULL)
    AND (revisada_em IS NULL) = (revisada_por IS NULL)
    AND (situacao <> 'NAO_ACEITA' OR observacao_revisao IS NOT NULL)
    AND (observacao_revisao IS NULL OR revisada_em IS NOT NULL));
-- Uma devolução por autorização, para sempre (dúvida P11): cancelar a devolução não devolve a autorização para uso.
CREATE UNIQUE INDEX devolucoes_autorizacao_unica ON corona.devolucoes (autorizacao_id) WHERE autorizacao_id IS NOT NULL;
CREATE INDEX devolucoes_em_revisao ON corona.devolucoes (devolvida_em) WHERE situacao = 'EM_REVISAO';

-- O gatilho de inserção da V036, inteiro, mais a autorização: da mesma venda, do mesmo vendedor e do mesmo motivo,
-- aprovada e válida no dia (Brasília) da devolução. Trava a autorização (a mesma que o serviço trava depois da venda).
CREATE OR REPLACE FUNCTION corona.conferir_devolucao_da_venda() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v record;
    a record;
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
    IF NEW.autorizacao_id IS NOT NULL THEN
        SELECT venda_id, vendedor_id, motivo, situacao, valida_ate INTO a
          FROM corona.autorizacoes_excecao WHERE id = NEW.autorizacao_id FOR UPDATE;
        IF a.venda_id IS DISTINCT FROM NEW.venda_id OR a.vendedor_id IS DISTINCT FROM NEW.vendedor_id
                OR a.motivo IS DISTINCT FROM NEW.motivo THEN
            RAISE EXCEPTION 'A autorização % não é desta venda, deste vendedor ou deste motivo.', NEW.autorizacao_id
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF a.situacao IS DISTINCT FROM 'APROVADA'
                OR a.valida_ate < (NEW.devolvida_em AT TIME ZONE 'America/Sao_Paulo')::date THEN
            RAISE EXCEPTION 'A autorização % não está aprovada e válida no dia da devolução.', NEW.autorizacao_id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- O gatilho de alteração da V036, inteiro, mais: os motivos da revisão nunca mudam, e os dados da revisão só mudam
-- junto com EM_REVISAO -> MANTIDA | NAO_ACEITA.
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
-- A pertença de autorizacoes_excecao não muda (ADR-0013, item 5; RegrasDoVendedorTest): a função da V036, inteira,
-- com o ramo novo.
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
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.autorizacoes_excecao
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();

REVOKE ALL ON corona.autorizacoes_excecao, corona.itens_autorizacao FROM PUBLIC;
