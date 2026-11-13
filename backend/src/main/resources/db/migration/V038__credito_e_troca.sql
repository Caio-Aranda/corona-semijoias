-- V038: crédito do cliente e troca (Fase 6, tarefa 6.5; ADR-0014, itens 8, 9, 11 e 12, e a linha datada de 13/11/2026;
-- ESPECIFICACAO §7.3 e §9; plano aprovado no PR #129, dúvidas T1 a T14 pelo padrão).
--
-- Um caminho só para o dinheiro da troca: a devolução de A (destino TROCA) grava um REEMBOLSO na forma CREDITO na venda
-- A e faz nascer o crédito do cliente; a venda nova B recebe esse crédito como PAGAMENTO na forma CREDITO (o uso fica em
-- usos_credito). O crédito usado não é dinheiro: devolucoes.valor_reembolsado continua sendo só o dinheiro e
-- valor_em_credito guarda o que voltou em crédito. Tudo imutável, correção por registro de sinal oposto; só a situação
-- (e, no crédito, o atalho valor_utilizado, sempre igual à soma dos usos) muda. Dinheiro em NUMERIC(14,2), tudo em Real
-- (ADR-0006); datas em UTC. Nenhuma migração publicada é editada: as funções da V022, da V036 e da V037 são recriadas
-- aqui, inteiras.

-- ---------------------------------------------------------------------------------------------
-- trocas: a troca (sincronizável; só a situação e o cancelamento mudam).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.trocas (
    id uuid PRIMARY KEY,
    venda_origem_id uuid NOT NULL REFERENCES corona.vendas (id),
    devolucao_id uuid NOT NULL REFERENCES corona.devolucoes (id),
    venda_nova_id uuid NOT NULL REFERENCES corona.vendas (id),
    cliente_id uuid NOT NULL REFERENCES corona.clientes (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    -- Total da venda nova menos o valor devolvido de A, calculado pelo servidor (qualquer sinal).
    diferenca numeric(14, 2) NOT NULL,
    -- Cotação do dia da troca, só para mostrar Dólar e Guarani (ADR-0006). Vazio se não havia cotação.
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    trocada_em timestamptz NOT NULL,
    hora_ajustada boolean NOT NULL DEFAULT false,
    registrada_por uuid NOT NULL REFERENCES corona.usuarios (id),
    situacao text NOT NULL DEFAULT 'ATIVA' CONSTRAINT trocas_situacao_conhecida CHECK (situacao IN ('ATIVA', 'CANCELADA')),
    -- Cancelar troca (ADR-0014, item 11): o id vem do aplicativo (idempotência).
    cancelamento_id uuid,
    cancelada_em timestamptz,
    cancelada_por uuid REFERENCES corona.usuarios (id),
    motivo_cancelamento text CONSTRAINT trocas_motivo_cancelamento_preenchido
        CHECK (motivo_cancelamento IS NULL OR (btrim(motivo_cancelamento) <> '' AND length(motivo_cancelamento) <= 400)),
    CONSTRAINT trocas_duas_vendas CHECK (venda_origem_id <> venda_nova_id),
    CONSTRAINT trocas_cancelamento_completo CHECK (
        (situacao = 'CANCELADA') = (cancelamento_id IS NOT NULL)
        AND (cancelamento_id IS NULL) = (cancelada_em IS NULL)
        AND (cancelamento_id IS NULL) = (cancelada_por IS NULL)
        AND (cancelamento_id IS NULL) = (motivo_cancelamento IS NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.trocas');
COMMENT ON TABLE corona.trocas IS
    'Troca (ADR-0014, item 8): a devolução de A (destino TROCA) e a venda nova B, ligadas, numa operação só. Sem tabela de itens: as peças estão na devolução e na venda nova.';
COMMENT ON COLUMN corona.trocas.diferenca IS
    'Total da venda nova menos o valor devolvido de A. Positiva: o cliente pagou (ou deve) o resto. Negativa: a sobra ficou de crédito.';
CREATE UNIQUE INDEX trocas_devolucao_unica ON corona.trocas (devolucao_id);
CREATE UNIQUE INDEX trocas_venda_nova_unica ON corona.trocas (venda_nova_id);
CREATE UNIQUE INDEX trocas_cancelamento_unico ON corona.trocas (cancelamento_id) WHERE cancelamento_id IS NOT NULL;
CREATE INDEX trocas_por_vendedor ON corona.trocas (vendedor_id, trocada_em DESC);
CREATE INDEX trocas_por_venda_origem ON corona.trocas (venda_origem_id);

-- A troca nasce ativa.
CREATE FUNCTION corona.conferir_troca_nova() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.situacao IS DISTINCT FROM 'ATIVA' THEN
        RAISE EXCEPTION 'A troca % nasce feita (ATIVA).', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trocas_nasce_ativa BEFORE INSERT ON corona.trocas
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_troca_nova();

-- Conferência no fim da transação (a venda nova e o crédito são gravados antes da linha da troca): a devolução é de
-- troca e da venda de origem, a venda nova aponta para esta troca, as três partes são do mesmo cliente e do mesmo
-- vendedor, e a diferença bate com os valores gravados. Lê a linha de novo (o retrato do fim da transação).
CREATE FUNCTION corona.conferir_troca() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    t record;
    d record;
    vo record;
    vn record;
BEGIN
    SELECT * INTO t FROM corona.trocas WHERE id = NEW.id;
    SELECT venda_id, cliente_id, vendedor_id, destino, valor_devolvido INTO d FROM corona.devolucoes WHERE id = t.devolucao_id;
    SELECT cliente_id, vendedor_id INTO vo FROM corona.vendas WHERE id = t.venda_origem_id;
    SELECT cliente_id, vendedor_id, troca_id, total INTO vn FROM corona.vendas WHERE id = t.venda_nova_id;
    IF d.destino IS DISTINCT FROM 'TROCA' OR d.venda_id IS DISTINCT FROM t.venda_origem_id THEN
        RAISE EXCEPTION 'A troca % precisa de uma devolução de troca da venda de origem.', t.id USING ERRCODE = 'check_violation';
    END IF;
    IF vn.troca_id IS DISTINCT FROM t.id THEN
        RAISE EXCEPTION 'A venda nova da troca % não aponta para ela.', t.id USING ERRCODE = 'check_violation';
    END IF;
    IF t.cliente_id IS DISTINCT FROM vo.cliente_id OR t.cliente_id IS DISTINCT FROM vn.cliente_id
            OR t.cliente_id IS DISTINCT FROM d.cliente_id OR t.vendedor_id IS DISTINCT FROM vo.vendedor_id
            OR t.vendedor_id IS DISTINCT FROM vn.vendedor_id OR t.vendedor_id IS DISTINCT FROM d.vendedor_id THEN
        RAISE EXCEPTION 'A troca % precisa ter o mesmo cliente e o mesmo vendedor nas duas vendas e na devolução.', t.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF t.diferenca IS DISTINCT FROM vn.total - d.valor_devolvido THEN
        RAISE EXCEPTION 'A diferença da troca % (R$ %) não confere com a venda nova (R$ %) e a devolução (R$ %).', t.id,
            t.diferenca, vn.total, d.valor_devolvido USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trocas_coerente AFTER INSERT ON corona.trocas
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_troca();

-- Nada muda além da situação (só ATIVA -> CANCELADA) e dos dados do cancelamento. deleted_at nunca.
CREATE FUNCTION corona.proteger_troca() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.venda_origem_id IS DISTINCT FROM OLD.venda_origem_id OR NEW.devolucao_id IS DISTINCT FROM OLD.devolucao_id
            OR NEW.venda_nova_id IS DISTINCT FROM OLD.venda_nova_id OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
            OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.diferenca IS DISTINCT FROM OLD.diferenca
            OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id OR NEW.trocada_em IS DISTINCT FROM OLD.trocada_em
            OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada OR NEW.registrada_por IS DISTINCT FROM OLD.registrada_por
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'A troca % não pode ser alterada: a correção é cancelar a troca.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.situacao IS DISTINCT FROM OLD.situacao
       AND NOT (OLD.situacao = 'ATIVA' AND NEW.situacao = 'CANCELADA') THEN
        RAISE EXCEPTION 'A troca % não pode passar de % para %.', OLD.id, OLD.situacao, NEW.situacao
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.cancelamento_id IS DISTINCT FROM OLD.cancelamento_id OR NEW.cancelada_em IS DISTINCT FROM OLD.cancelada_em
            OR NEW.cancelada_por IS DISTINCT FROM OLD.cancelada_por
            OR NEW.motivo_cancelamento IS DISTINCT FROM OLD.motivo_cancelamento)
       AND NOT (OLD.situacao = 'ATIVA' AND NEW.situacao = 'CANCELADA') THEN
        RAISE EXCEPTION 'O cancelamento da troca % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trocas_conteudo_imutavel BEFORE UPDATE ON corona.trocas
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_troca();

-- ---------------------------------------------------------------------------------------------
-- vendas: a venda nova da troca aponta para ela (dúvida T11).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.vendas
    ADD COLUMN troca_id uuid CONSTRAINT vendas_troca_fk REFERENCES corona.trocas (id) DEFERRABLE INITIALLY DEFERRED;
COMMENT ON COLUMN corona.vendas.troca_id IS
    'A troca da qual esta venda é a venda nova (B). Só se desfaz pelo "Cancelar troca" (ADR-0014, item 11).';
CREATE UNIQUE INDEX vendas_troca_unica ON corona.vendas (troca_id) WHERE troca_id IS NOT NULL;

-- A venda com troca_id precisa da troca que a tem como venda nova (no fim da transação).
CREATE FUNCTION corona.conferir_venda_da_troca() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM corona.trocas t WHERE t.id = NEW.troca_id AND t.venda_nova_id = NEW.id) THEN
        RAISE EXCEPTION 'A venda % aponta para a troca %, que não a tem como venda nova.', NEW.id, NEW.troca_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER vendas_da_troca_conferida AFTER INSERT ON corona.vendas
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.troca_id IS NOT NULL) EXECUTE FUNCTION corona.conferir_venda_da_troca();

-- O gatilho de alteração da V022, inteiro, mais a troca_id, que nunca muda.
CREATE OR REPLACE FUNCTION corona.proteger_venda() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
            OR NEW.remessa_id IS DISTINCT FROM OLD.remessa_id OR NEW.subtotal IS DISTINCT FROM OLD.subtotal
            OR NEW.desconto IS DISTINCT FROM OLD.desconto OR NEW.total IS DISTINCT FROM OLD.total
            OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id OR NEW.vendida_em IS DISTINCT FROM OLD.vendida_em
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at OR NEW.troca_id IS DISTINCT FROM OLD.troca_id THEN
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

-- O gatilho da V036, inteiro, mais: a venda nova de uma troca só vira ESTORNADA com a troca já cancelada (o "Cancelar
-- troca" grava a troca primeiro; dúvida T8).
CREATE OR REPLACE FUNCTION corona.recusar_estorno_com_devolucao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CONCLUIDA' AND NEW.status = 'ESTORNADA'
       AND EXISTS (SELECT 1 FROM corona.devolucoes d WHERE d.venda_id = OLD.id AND d.situacao <> 'CANCELADA') THEN
        RAISE EXCEPTION 'A venda % tem devolução: cancele a devolução antes de estornar.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'CONCLUIDA' AND NEW.status = 'ESTORNADA' AND OLD.troca_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM corona.trocas t WHERE t.id = OLD.troca_id AND t.situacao = 'CANCELADA') THEN
        RAISE EXCEPTION 'A venda % é de uma troca: ela só se desfaz pelo "Cancelar troca".', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- devolucoes: o que voltou em crédito, separado do dinheiro (dúvida T1).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.devolucoes
    ADD COLUMN valor_em_credito numeric(14, 2) NOT NULL DEFAULT 0
        CONSTRAINT devolucoes_valor_em_credito_nao_negativo CHECK (valor_em_credito >= 0);
ALTER TABLE corona.devolucoes ADD CONSTRAINT devolucoes_dinheiro_e_credito_cabem
    CHECK (valor_reembolsado + valor_em_credito <= valor_devolvido);
ALTER TABLE corona.devolucoes ADD CONSTRAINT devolucoes_troca_em_credito
    CHECK (destino <> 'TROCA' OR (valor_reembolsado = 0 AND valor_em_credito = valor_devolvido));
COMMENT ON COLUMN corona.devolucoes.valor_reembolsado IS 'O que voltou em dinheiro (na forma do reembolso). Na troca, zero.';
COMMENT ON COLUMN corona.devolucoes.valor_em_credito IS
    'O que voltou como crédito do cliente: na troca, o valor devolvido inteiro; na devolução de venda paga com crédito, o que foi pago em crédito (primeiro).';

-- A devolução de troca precisa da troca (no fim da transação: a troca é gravada depois dela).
CREATE FUNCTION corona.conferir_devolucao_da_troca() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM corona.trocas t WHERE t.devolucao_id = NEW.id) THEN
        RAISE EXCEPTION 'A devolução de troca % não tem a troca.', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER devolucoes_da_troca_conferida AFTER INSERT ON corona.devolucoes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.destino = 'TROCA') EXECUTE FUNCTION corona.conferir_devolucao_da_troca();

-- O gatilho de alteração da V037, inteiro, mais: o valor em crédito nunca muda, e a devolução de troca só vira
-- CANCELADA com a troca já cancelada (dúvida T8).
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
-- creditos_cliente: o crédito do cliente (sincronizável; só valor_utilizado, a situação e o cancelamento mudam).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.creditos_cliente (
    id uuid PRIMARY KEY,
    cliente_id uuid NOT NULL REFERENCES corona.clientes (id),
    -- Quem usa o crédito: o vendedor que o gerou, ou para quem a dona o transferiu (dúvidas T6 e T7).
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    origem text NOT NULL CONSTRAINT creditos_cliente_origem_conhecida CHECK (origem IN ('TROCA', 'DEVOLUCAO', 'TRANSFERENCIA')),
    troca_id uuid CONSTRAINT creditos_cliente_troca_fk REFERENCES corona.trocas (id) DEFERRABLE INITIALLY DEFERRED,
    devolucao_id uuid REFERENCES corona.devolucoes (id),
    credito_origem_id uuid REFERENCES corona.creditos_cliente (id),
    valor numeric(14, 2) NOT NULL CONSTRAINT creditos_cliente_valor_positivo CHECK (valor > 0),
    -- Atalho: sempre igual à soma dos usos (gatilho). Nunca passa do valor.
    valor_utilizado numeric(14, 2) NOT NULL DEFAULT 0
        CONSTRAINT creditos_cliente_utilizado_cabe CHECK (valor_utilizado >= 0 AND valor_utilizado <= valor),
    situacao text NOT NULL DEFAULT 'ABERTO'
        CONSTRAINT creditos_cliente_situacao_conhecida CHECK (situacao IN ('ABERTO', 'UTILIZADO', 'CANCELADO')),
    -- Cotação do dia em que o crédito nasceu, só para mostrar Dólar e Guarani (ADR-0006).
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    gerado_em timestamptz NOT NULL,
    hora_ajustada boolean NOT NULL DEFAULT false,
    registrado_por uuid NOT NULL REFERENCES corona.usuarios (id),
    cancelado_em timestamptz,
    cancelado_por uuid REFERENCES corona.usuarios (id),
    CONSTRAINT creditos_cliente_ligacoes_da_origem CHECK (
        (origem = 'TROCA' AND troca_id IS NOT NULL AND devolucao_id IS NOT NULL AND credito_origem_id IS NULL)
        OR (origem = 'DEVOLUCAO' AND troca_id IS NULL AND devolucao_id IS NOT NULL AND credito_origem_id IS NULL)
        OR (origem = 'TRANSFERENCIA' AND troca_id IS NULL AND devolucao_id IS NULL AND credito_origem_id IS NOT NULL)),
    CONSTRAINT creditos_cliente_usado_e_zerado CHECK ((situacao = 'UTILIZADO') = (valor_utilizado = valor)),
    CONSTRAINT creditos_cliente_cancelado_sem_uso CHECK (situacao <> 'CANCELADO' OR valor_utilizado = 0),
    CONSTRAINT creditos_cliente_cancelamento_completo CHECK (
        (situacao = 'CANCELADO') = (cancelado_em IS NOT NULL) AND (cancelado_em IS NULL) = (cancelado_por IS NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.creditos_cliente');
COMMENT ON TABLE corona.creditos_cliente IS
    'Crédito do cliente (ADR-0014, item 9): nasce da troca, da devolução de venda paga com crédito ou da transferência pela dona. Usado como forma de pagamento CREDITO, só pelo vendedor dono e na venda do mesmo cliente.';
COMMENT ON COLUMN corona.creditos_cliente.situacao IS
    'ABERTO (tem saldo) <-> UTILIZADO (saldo zero), pelos usos; ABERTO -> CANCELADO (a troca ou a devolução que o gerou foi cancelada), sem volta.';
CREATE UNIQUE INDEX creditos_cliente_troca_unica ON corona.creditos_cliente (troca_id) WHERE troca_id IS NOT NULL;
CREATE UNIQUE INDEX creditos_cliente_devolucao_unica ON corona.creditos_cliente (devolucao_id) WHERE devolucao_id IS NOT NULL;
CREATE INDEX creditos_cliente_por_vendedor ON corona.creditos_cliente (vendedor_id, cliente_id);
CREATE INDEX creditos_cliente_por_cliente ON corona.creditos_cliente (cliente_id);
CREATE INDEX creditos_cliente_por_origem ON corona.creditos_cliente (credito_origem_id) WHERE credito_origem_id IS NOT NULL;

-- O crédito nasce em aberto, sem uso, com o cliente e o vendedor da devolução (troca ou devolução) e, na devolução de
-- troca ou na devolução comum, com o valor que ela devolveu em crédito. Na transferência: o mesmo cliente do crédito
-- de origem e outro vendedor.
CREATE FUNCTION corona.conferir_credito_novo() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    d record;
    o record;
BEGIN
    IF NEW.situacao IS DISTINCT FROM 'ABERTO' OR NEW.valor_utilizado <> 0 THEN
        RAISE EXCEPTION 'O crédito % nasce em aberto e sem uso.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.origem IN ('TROCA', 'DEVOLUCAO') THEN
        SELECT cliente_id, vendedor_id, destino, valor_em_credito INTO d FROM corona.devolucoes WHERE id = NEW.devolucao_id;
        IF d.cliente_id IS DISTINCT FROM NEW.cliente_id OR d.vendedor_id IS DISTINCT FROM NEW.vendedor_id THEN
            RAISE EXCEPTION 'O crédito % precisa ter o cliente e o vendedor da devolução %.', NEW.id, NEW.devolucao_id
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF (NEW.origem = 'TROCA') IS DISTINCT FROM (d.destino = 'TROCA') OR NEW.valor IS DISTINCT FROM d.valor_em_credito THEN
            RAISE EXCEPTION 'O crédito % precisa ter a origem e o valor em crédito da devolução %.', NEW.id, NEW.devolucao_id
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSE
        SELECT cliente_id, vendedor_id INTO o FROM corona.creditos_cliente WHERE id = NEW.credito_origem_id;
        IF o.cliente_id IS DISTINCT FROM NEW.cliente_id OR o.vendedor_id = NEW.vendedor_id THEN
            RAISE EXCEPTION 'O crédito transferido % precisa ser do mesmo cliente e de outro vendedor.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER creditos_cliente_nasce_conferido BEFORE INSERT ON corona.creditos_cliente
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_credito_novo();

-- Segunda trava do saldo: nada muda além de valor_utilizado (sempre igual à soma dos usos), da situação e do
-- cancelamento; cancelado não volta. deleted_at nunca.
CREATE FUNCTION corona.proteger_credito() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    usado numeric;
BEGIN
    IF NEW.cliente_id IS DISTINCT FROM OLD.cliente_id OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id
            OR NEW.origem IS DISTINCT FROM OLD.origem OR NEW.troca_id IS DISTINCT FROM OLD.troca_id
            OR NEW.devolucao_id IS DISTINCT FROM OLD.devolucao_id OR NEW.credito_origem_id IS DISTINCT FROM OLD.credito_origem_id
            OR NEW.valor IS DISTINCT FROM OLD.valor OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id
            OR NEW.gerado_em IS DISTINCT FROM OLD.gerado_em OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada
            OR NEW.registrado_por IS DISTINCT FROM OLD.registrado_por OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'O crédito % não pode ser alterado: a correção é por registro novo.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.situacao = 'CANCELADO' AND (NEW.situacao IS DISTINCT FROM OLD.situacao
            OR NEW.valor_utilizado IS DISTINCT FROM OLD.valor_utilizado OR NEW.cancelado_em IS DISTINCT FROM OLD.cancelado_em
            OR NEW.cancelado_por IS DISTINCT FROM OLD.cancelado_por) THEN
        RAISE EXCEPTION 'O crédito % foi cancelado e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.cancelado_em IS DISTINCT FROM OLD.cancelado_em OR NEW.cancelado_por IS DISTINCT FROM OLD.cancelado_por)
       AND NOT (NEW.situacao = 'CANCELADO' AND OLD.situacao IS DISTINCT FROM 'CANCELADO') THEN
        RAISE EXCEPTION 'O cancelamento do crédito % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    SELECT coalesce(sum(valor), 0) INTO usado FROM corona.usos_credito WHERE credito_id = OLD.id;
    IF NEW.valor_utilizado IS DISTINCT FROM usado THEN
        RAISE EXCEPTION 'O valor usado do crédito % (R$ %) precisa ser a soma dos usos (R$ %).', OLD.id, NEW.valor_utilizado, usado
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER creditos_cliente_conteudo_imutavel BEFORE UPDATE ON corona.creditos_cliente
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_credito();

-- ---------------------------------------------------------------------------------------------
-- usos_credito: cada uso, estorno de uso e transferência (sincronizável, imutável).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.usos_credito (
    id uuid PRIMARY KEY,
    credito_id uuid NOT NULL REFERENCES corona.creditos_cliente (id),
    tipo text NOT NULL CONSTRAINT usos_credito_tipo_conhecido CHECK (tipo IN ('USO', 'ESTORNO', 'TRANSFERENCIA')),
    -- No uso e no estorno: a venda e o pagamento em crédito dela (PAGAMENTO no uso, REEMBOLSO no estorno).
    venda_id uuid REFERENCES corona.vendas (id),
    pagamento_id uuid REFERENCES corona.pagamentos_cliente (id),
    -- Na transferência: o crédito novo do outro vendedor.
    credito_destino_id uuid REFERENCES corona.creditos_cliente (id),
    valor numeric(14, 2) NOT NULL,
    estorna_id uuid REFERENCES corona.usos_credito (id),
    usado_em timestamptz NOT NULL,
    CONSTRAINT usos_credito_forma_do_tipo CHECK (
        (tipo = 'USO' AND valor > 0 AND venda_id IS NOT NULL AND pagamento_id IS NOT NULL AND credito_destino_id IS NULL
            AND estorna_id IS NULL)
        OR (tipo = 'ESTORNO' AND valor < 0 AND venda_id IS NOT NULL AND pagamento_id IS NOT NULL AND credito_destino_id IS NULL
            AND estorna_id IS NOT NULL)
        OR (tipo = 'TRANSFERENCIA' AND valor > 0 AND venda_id IS NULL AND pagamento_id IS NULL AND credito_destino_id IS NOT NULL
            AND estorna_id IS NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.usos_credito', imutavel => true);
COMMENT ON TABLE corona.usos_credito IS
    'Usos do crédito do cliente, imutáveis: USO (pagamento CREDITO de uma venda), ESTORNO (negativo, o estorno da venda devolve o crédito) e TRANSFERENCIA (o saldo inteiro passado pela dona a outro vendedor).';
CREATE UNIQUE INDEX usos_credito_pagamento_unico ON corona.usos_credito (pagamento_id) WHERE pagamento_id IS NOT NULL;
CREATE UNIQUE INDEX usos_credito_estorno_unico ON corona.usos_credito (estorna_id) WHERE estorna_id IS NOT NULL;
CREATE UNIQUE INDEX usos_credito_destino_unico ON corona.usos_credito (credito_destino_id) WHERE credito_destino_id IS NOT NULL;
CREATE INDEX usos_credito_por_credito ON corona.usos_credito (credito_id);
CREATE INDEX usos_credito_por_venda ON corona.usos_credito (venda_id) WHERE venda_id IS NOT NULL;

-- Segunda trava do "nunca acima do saldo" (como a V024 na maleta): trava o crédito (a mesma linha que o serviço
-- trava), recusa crédito cancelado e soma dos usos fora de 0..valor. No uso e no estorno, a venda é do cliente e do
-- vendedor do crédito e o pagamento é em crédito deste crédito, com o mesmo valor; o estorno desfaz um uso deste
-- crédito com o valor oposto; a transferência aponta para o crédito novo nascido dela, com o mesmo valor.
CREATE FUNCTION corona.conferir_uso_de_credito() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c record;
    v record;
    p record;
    u record;
    destino record;
    usado numeric;
BEGIN
    SELECT cliente_id, vendedor_id, valor, situacao INTO c FROM corona.creditos_cliente WHERE id = NEW.credito_id FOR UPDATE;
    IF c.situacao = 'CANCELADO' THEN
        RAISE EXCEPTION 'O crédito % foi cancelado e não pode ser usado.', NEW.credito_id USING ERRCODE = 'check_violation';
    END IF;
    SELECT coalesce(sum(valor), 0) INTO usado FROM corona.usos_credito WHERE credito_id = NEW.credito_id;
    IF usado < 0 OR usado > c.valor THEN
        RAISE EXCEPTION 'O crédito % ficaria com R$ % usados, fora do saldo (de zero a R$ %).', NEW.credito_id, usado, c.valor
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.tipo IN ('USO', 'ESTORNO') THEN
        SELECT cliente_id, vendedor_id INTO v FROM corona.vendas WHERE id = NEW.venda_id;
        IF v.cliente_id IS DISTINCT FROM c.cliente_id OR v.vendedor_id IS DISTINCT FROM c.vendedor_id THEN
            RAISE EXCEPTION 'A venda % não é do cliente e do vendedor do crédito %.', NEW.venda_id, NEW.credito_id
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT venda_id, tipo, forma, valor, credito_id INTO p FROM corona.pagamentos_cliente WHERE id = NEW.pagamento_id;
        IF p.venda_id IS DISTINCT FROM NEW.venda_id OR p.forma IS DISTINCT FROM 'CREDITO'
                OR p.credito_id IS DISTINCT FROM NEW.credito_id OR p.valor IS DISTINCT FROM NEW.valor
                OR p.tipo IS DISTINCT FROM (CASE WHEN NEW.tipo = 'USO' THEN 'PAGAMENTO' ELSE 'REEMBOLSO' END) THEN
            RAISE EXCEPTION 'O pagamento % não é o pagamento em crédito deste uso do crédito %.', NEW.pagamento_id,
                NEW.credito_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.tipo = 'ESTORNO' THEN
        SELECT credito_id, tipo, valor, venda_id INTO u FROM corona.usos_credito WHERE id = NEW.estorna_id;
        IF u.credito_id IS DISTINCT FROM NEW.credito_id OR u.tipo IS DISTINCT FROM 'USO' OR u.valor IS DISTINCT FROM -NEW.valor
                OR u.venda_id IS DISTINCT FROM NEW.venda_id THEN
            RAISE EXCEPTION 'O estorno % precisa desfazer um uso do crédito % na mesma venda, com o valor oposto.', NEW.id,
                NEW.credito_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.tipo = 'TRANSFERENCIA' THEN
        SELECT origem, credito_origem_id, valor INTO destino FROM corona.creditos_cliente WHERE id = NEW.credito_destino_id;
        IF destino.origem IS DISTINCT FROM 'TRANSFERENCIA' OR destino.credito_origem_id IS DISTINCT FROM NEW.credito_id
                OR destino.valor IS DISTINCT FROM NEW.valor THEN
            RAISE EXCEPTION 'A transferência % precisa apontar para o crédito novo dela, com o mesmo valor.', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER usos_credito_dentro_do_saldo AFTER INSERT ON corona.usos_credito
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_uso_de_credito();

-- No fim da transação, o atalho do crédito confere com a soma dos usos (o serviço grava o uso e depois o crédito).
CREATE FUNCTION corona.conferir_atalho_do_credito() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    atalho numeric;
    usado numeric;
BEGIN
    SELECT valor_utilizado INTO atalho FROM corona.creditos_cliente WHERE id = NEW.credito_id;
    SELECT coalesce(sum(valor), 0) INTO usado FROM corona.usos_credito WHERE credito_id = NEW.credito_id;
    IF atalho IS DISTINCT FROM usado THEN
        RAISE EXCEPTION 'O valor usado do crédito % (R$ %) não confere com a soma dos usos (R$ %).', NEW.credito_id, atalho, usado
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER usos_credito_conferem_o_credito AFTER INSERT ON corona.usos_credito
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_atalho_do_credito();

-- O crédito transferido precisa do uso TRANSFERENCIA que o originou (no fim da transação).
CREATE FUNCTION corona.conferir_credito_transferido() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM corona.usos_credito u
                    WHERE u.credito_destino_id = NEW.id AND u.credito_id = NEW.credito_origem_id AND u.tipo = 'TRANSFERENCIA') THEN
        RAISE EXCEPTION 'O crédito transferido % não tem o uso de transferência no crédito de origem.', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER creditos_cliente_transferencia_conferida AFTER INSERT ON corona.creditos_cliente
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.origem = 'TRANSFERENCIA') EXECUTE FUNCTION corona.conferir_credito_transferido();

-- ---------------------------------------------------------------------------------------------
-- pagamentos_cliente: a forma CREDITO, sempre com o crédito.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.pagamentos_cliente
    ADD COLUMN credito_id uuid CONSTRAINT pagamentos_cliente_credito_fk REFERENCES corona.creditos_cliente (id);
COMMENT ON COLUMN corona.pagamentos_cliente.credito_id IS
    'O crédito do cliente da forma CREDITO: o uso (PAGAMENTO), o que voltou em crédito (REEMBOLSO) e o estorno disso.';
ALTER TABLE corona.pagamentos_cliente DROP CONSTRAINT pagamentos_cliente_forma_conhecida;
ALTER TABLE corona.pagamentos_cliente ADD CONSTRAINT pagamentos_cliente_forma_conhecida
    CHECK (forma IN ('DINHEIRO', 'PIX', 'CARTAO', 'CHEQUE', 'CREDITO'));
ALTER TABLE corona.pagamentos_cliente ADD CONSTRAINT pagamentos_cliente_credito_da_forma
    CHECK ((forma = 'CREDITO') = (credito_id IS NOT NULL));
CREATE INDEX pagamentos_cliente_por_credito ON corona.pagamentos_cliente (credito_id) WHERE credito_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- movimentos_remessa: TROCA_ENTRADA aponta para a devolução da troca; TROCA_SAIDA, para a venda nova.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    de_troca bigint;
BEGIN
    -- Nenhum caminho anterior grava TROCA_ENTRADA nem TROCA_SAIDA.
    SELECT count(*) INTO de_troca FROM corona.movimentos_remessa WHERE tipo IN ('TROCA_ENTRADA', 'TROCA_SAIDA');
    IF de_troca > 0 THEN
        RAISE EXCEPTION 'Há % movimento(s) de troca sem troca; corrija antes de aplicar a V038.', de_troca;
    END IF;
END
$$;
ALTER TABLE corona.movimentos_remessa DROP CONSTRAINT movimentos_remessa_devolucao_do_tipo;
ALTER TABLE corona.movimentos_remessa ADD CONSTRAINT movimentos_remessa_devolucao_do_tipo
    CHECK ((tipo IN ('DEVOLUCAO_CLIENTE', 'TROCA_ENTRADA')) = (devolucao_id IS NOT NULL));
ALTER TABLE corona.movimentos_remessa ADD CONSTRAINT movimentos_remessa_troca_saida_da_venda
    CHECK (tipo <> 'TROCA_SAIDA' OR venda_id IS NOT NULL);
COMMENT ON COLUMN corona.movimentos_remessa.devolucao_id IS
    'A devolução do cliente deste movimento DEVOLUCAO_CLIENTE ou TROCA_ENTRADA (e do estorno dele, quando a devolução ou a troca é cancelada).';

-- ---------------------------------------------------------------------------------------------
-- A pertença de trocas e de creditos_cliente não muda (ADR-0013, item 5; RegrasDoVendedorTest): a função da V037,
-- inteira, com os dois ramos novos.
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
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.trocas
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.creditos_cliente
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();

REVOKE ALL ON corona.trocas, corona.creditos_cliente, corona.usos_credito FROM PUBLIC;
