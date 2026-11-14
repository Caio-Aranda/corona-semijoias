-- V043: acerto, parte 1: iniciar, travas, contagem e desistir (Fase 7, tarefa 7.4; ADR-0012, itens 2, 3, 4, 13, 14, 15 e 16;
-- ESPECIFICACAO §7.4, §9 e §10; plano aprovado no PR #165).
--
-- O acerto liga as maletas abertas do vendedor (ENVIADA ou CONFERIDA) e as passa a EM_ACERTO, guardando o status de antes
-- para o "desistir" voltar exatamente ao que era. A contagem da dona e do vendedor são leituras imutáveis; a soma por
-- (acerto, peça, estado da peça) nunca fica negativa. Nesta tarefa NÃO há dinheiro: totais, comissão, pendências e
-- cotação nascem no fechamento (7.5), por migração nova (ALTER TABLE ... ADD COLUMN), e a 7.5 recria duas funções daqui
-- (proteger_acerto e conferir_acerto_da_remessa) para aceitar EM_ACERTO -> ACERTADO e EM_ACERTO -> ACERTADA.
-- Nenhuma migração publicada (V001 a V042) é editada: a função de pertença da V041 é recriada inteira, com os ramos novos.

-- ---------------------------------------------------------------------------------------------
-- acertos: um por vendedor e semana (sincronizável; só o status muda, e só de EM_ACERTO para DESISTIDO nesta tarefa).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.acertos (
    -- Do cliente (UUID do comando "iniciar"): reenviar o mesmo id devolve o mesmo acerto.
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    semana_id uuid NOT NULL REFERENCES corona.semanas (id),
    status text NOT NULL DEFAULT 'EM_ACERTO' CONSTRAINT acertos_status_conhecido
        CHECK (status IN ('EM_ACERTO', 'ACERTADO', 'DESISTIDO')),
    pedido_pelo_vendedor boolean NOT NULL DEFAULT false,
    iniciado_em timestamptz NOT NULL DEFAULT now(),
    iniciado_por uuid NOT NULL REFERENCES corona.usuarios (id),
    -- O computador que pediu o acerto (só quando foi o vendedor).
    dispositivo_id uuid REFERENCES corona.dispositivos (id),
    CONSTRAINT acertos_pedido_tem_computador CHECK (NOT pedido_pelo_vendedor OR dispositivo_id IS NOT NULL),
    -- O UUID do comando "desistir" (idempotência) e os dados da desistência: todos juntos, só no DESISTIDO.
    desistencia_id uuid UNIQUE,
    desistido_em timestamptz,
    desistido_por uuid REFERENCES corona.usuarios (id),
    motivo_desistencia text CONSTRAINT acertos_motivo_curto CHECK (length(motivo_desistencia) <= 500),
    CONSTRAINT acertos_desistencia_completa CHECK (
        (status = 'DESISTIDO' AND desistencia_id IS NOT NULL AND desistido_em IS NOT NULL AND desistido_por IS NOT NULL
            AND motivo_desistencia IS NOT NULL AND btrim(motivo_desistencia) <> '')
        OR (status <> 'DESISTIDO' AND desistencia_id IS NULL AND desistido_em IS NULL AND desistido_por IS NULL
            AND motivo_desistencia IS NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.acertos');
COMMENT ON TABLE corona.acertos IS
    'Acerto do vendedor com a loja, um por vendedor e semana (ADR-0012). Nesta tarefa só iniciar, contar e desistir; sem dinheiro (o fechamento chega na 7.5).';
COMMENT ON COLUMN corona.acertos.status IS
    'EM_ACERTO (em andamento) | ACERTADO (fechado, a partir da 7.5) | DESISTIDO (a dona desistiu; tudo voltou como estava).';
COMMENT ON COLUMN corona.acertos.pedido_pelo_vendedor IS 'Verdadeiro quando foi o vendedor que pediu o acerto, pelo computador dele.';
COMMENT ON COLUMN corona.acertos.iniciado_em IS 'Hora do servidor: a regra de corte das vendas feitas sem internet (ADR-0012, item 4).';
COMMENT ON COLUMN corona.acertos.desistencia_id IS 'UUID do comando "desistir", do aplicativo: reenviar o mesmo id não repete a desistência.';
-- Um acerto em andamento por vendedor; um acerto por semana (um DESISTIDO libera a semana).
CREATE UNIQUE INDEX acertos_um_em_andamento_por_vendedor ON corona.acertos (vendedor_id) WHERE status = 'EM_ACERTO';
CREATE UNIQUE INDEX acertos_um_por_vendedor_e_semana ON corona.acertos (vendedor_id, semana_id) WHERE status <> 'DESISTIDO';
CREATE INDEX acertos_por_vendedor ON corona.acertos (vendedor_id, iniciado_em);

CREATE FUNCTION corona.proteger_acerto() RETURNS trigger
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
        -- A 7.5 recria esta função para aceitar também EM_ACERTO -> ACERTADO.
        IF NEW.status NOT IN ('EM_ACERTO', 'DESISTIDO') THEN
            RAISE EXCEPTION 'O acerto % só passa de em andamento para desistido.', OLD.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF NEW.status IS DISTINCT FROM OLD.status OR NEW.desistencia_id IS DISTINCT FROM OLD.desistencia_id
            OR NEW.desistido_em IS DISTINCT FROM OLD.desistido_em OR NEW.desistido_por IS DISTINCT FROM OLD.desistido_por
            OR NEW.motivo_desistencia IS DISTINCT FROM OLD.motivo_desistencia THEN
        RAISE EXCEPTION 'O acerto % já terminou e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER acertos_protegido BEFORE INSERT OR UPDATE ON corona.acertos
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_acerto();

-- ---------------------------------------------------------------------------------------------
-- leituras_acerto: a contagem, uma leitura por registro (sincronizável e imutável; desfazer é leitura negativa).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.leituras_acerto (
    -- Do cliente (UUID da leitura): reenviar o mesmo id não conta de novo.
    id uuid PRIMARY KEY,
    acerto_id uuid NOT NULL REFERENCES corona.acertos (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    quantidade integer NOT NULL CONSTRAINT leituras_acerto_quantidade_valida CHECK (quantidade <> 0 AND abs(quantidade) <= 10000),
    -- Os mesmos valores de itens_devolucao.
    estado_peca text NOT NULL CONSTRAINT leituras_acerto_estado_conhecido CHECK (estado_peca IN ('BOA', 'DEFEITO')),
    lida_em timestamptz NOT NULL DEFAULT now(),
    lida_por uuid NOT NULL REFERENCES corona.usuarios (id),
    pelo_vendedor boolean NOT NULL DEFAULT false,
    dispositivo_id uuid REFERENCES corona.dispositivos (id),
    CONSTRAINT leituras_acerto_vendedor_tem_computador CHECK (NOT pelo_vendedor OR dispositivo_id IS NOT NULL)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.leituras_acerto', imutavel => true);
COMMENT ON TABLE corona.leituras_acerto IS
    'Contagem do acerto, uma leitura por registro (ADR-0012, item 15). Imutável; desfazer é leitura negativa. A contagem é a soma por (acerto, peça, estado), nunca negativa.';
CREATE INDEX leituras_acerto_por_peca ON corona.leituras_acerto (acerto_id, produto_id, estado_peca);

-- Segunda trava (a primeira é o serviço, que trava o acerto): a leitura só entra em acerto em andamento (FOR SHARE espera uma
-- desistência em andamento) e a soma da peça e do estado, com a leitura nova, nunca fica negativa.
CREATE FUNCTION corona.conferir_leitura_de_acerto() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    situacao text;
    soma bigint;
BEGIN
    SELECT a.status INTO situacao FROM corona.acertos a WHERE a.id = NEW.acerto_id FOR SHARE;
    IF situacao IS DISTINCT FROM 'EM_ACERTO' THEN
        RAISE EXCEPTION 'A leitura % só entra em acerto em andamento.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    SELECT coalesce(sum(l.quantidade), 0) INTO soma FROM corona.leituras_acerto l
     WHERE l.acerto_id = NEW.acerto_id AND l.produto_id = NEW.produto_id AND l.estado_peca = NEW.estado_peca;
    IF soma + NEW.quantidade < 0 THEN
        RAISE EXCEPTION 'A leitura % deixaria a contagem negativa.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER leituras_acerto_conferida BEFORE INSERT ON corona.leituras_acerto
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_leitura_de_acerto();

-- ---------------------------------------------------------------------------------------------
-- remessas: a ligação da maleta ao acerto e o status de antes (para o desistir voltar exatamente a ele).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.remessas ADD COLUMN acerto_id uuid REFERENCES corona.acertos (id);
ALTER TABLE corona.remessas ADD COLUMN status_antes_do_acerto text
    CONSTRAINT remessas_status_antes_do_acerto_conhecido CHECK (status_antes_do_acerto IN ('ENVIADA', 'CONFERIDA'));
ALTER TABLE corona.remessas ADD CONSTRAINT remessas_acerto_e_status_antes_juntos
    CHECK ((acerto_id IS NULL) = (status_antes_do_acerto IS NULL));
ALTER TABLE corona.remessas ADD CONSTRAINT remessas_em_acerto_tem_acerto
    CHECK (status <> 'EM_ACERTO' OR acerto_id IS NOT NULL);
CREATE INDEX remessas_do_acerto ON corona.remessas (acerto_id) WHERE acerto_id IS NOT NULL;
COMMENT ON COLUMN corona.remessas.acerto_id IS 'O acerto que ligou esta maleta (só o ServicoDeAcertos liga e desliga). Não vai para o aplicativo do vendedor.';
COMMENT ON COLUMN corona.remessas.status_antes_do_acerto IS 'ENVIADA ou CONFERIDA: o status que a maleta tinha ao entrar no acerto; o desistir volta a ele.';

-- Ligar só de ENVIADA ou CONFERIDA a EM_ACERTO, num acerto em andamento do mesmo vendedor; desligar só voltando ao status de
-- antes, com o acerto ainda em andamento. A 7.5 recria esta função para aceitar EM_ACERTO -> ACERTADA (mantendo o acerto_id).
CREATE FUNCTION corona.conferir_acerto_da_remessa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a record;
BEGIN
    IF OLD.status <> 'EM_ACERTO' AND NEW.status = 'EM_ACERTO' THEN
        SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = NEW.acerto_id;
        IF OLD.status NOT IN ('ENVIADA', 'CONFERIDA') OR OLD.acerto_id IS NOT NULL
                OR NEW.status_antes_do_acerto IS DISTINCT FROM OLD.status
                OR NOT FOUND OR a.status <> 'EM_ACERTO' OR a.vendedor_id <> NEW.vendedor_id THEN
            RAISE EXCEPTION 'A maleta % só entra em acerto vinda de enviada ou conferida, num acerto em andamento do mesmo vendedor.', OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF OLD.status = 'EM_ACERTO' AND NEW.status = 'EM_ACERTO' THEN
        IF NEW.acerto_id IS DISTINCT FROM OLD.acerto_id OR NEW.status_antes_do_acerto IS DISTINCT FROM OLD.status_antes_do_acerto THEN
            RAISE EXCEPTION 'A maleta % não troca de acerto.', OLD.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF OLD.status = 'EM_ACERTO' THEN
        SELECT status INTO a FROM corona.acertos WHERE id = OLD.acerto_id;
        IF NEW.status IS DISTINCT FROM OLD.status_antes_do_acerto OR NEW.acerto_id IS NOT NULL
                OR NEW.status_antes_do_acerto IS NOT NULL OR a.status IS DISTINCT FROM 'EM_ACERTO' THEN
            RAISE EXCEPTION 'A maleta % só sai do acerto voltando ao que era, com o acerto ainda em andamento.', OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF NEW.acerto_id IS DISTINCT FROM OLD.acerto_id OR NEW.status_antes_do_acerto IS DISTINCT FROM OLD.status_antes_do_acerto THEN
        RAISE EXCEPTION 'A ligação da maleta % com o acerto só muda ao entrar ou sair do acerto.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER remessas_acerto_conferido BEFORE UPDATE ON corona.remessas
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_acerto_da_remessa();

-- ---------------------------------------------------------------------------------------------
-- vendas: segunda trava da regra de corte (ADR-0012, item 4). Numa maleta em acerto só entra venda de hora anterior ao início
-- (a feita sem internet antes do acerto, que o serviço confere). A primeira trava é o serviço, que trava a maleta.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION corona.conferir_corte_da_venda() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    inicio timestamptz;
BEGIN
    SELECT a.iniciado_em INTO inicio FROM corona.remessas r JOIN corona.acertos a ON a.id = r.acerto_id
     WHERE r.id = NEW.remessa_id AND r.status = 'EM_ACERTO';
    IF FOUND AND NEW.vendida_em >= inicio THEN
        RAISE EXCEPTION 'A venda % é de depois do início do acerto: a maleta está em acerto.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vendas_corte_do_acerto BEFORE INSERT ON corona.vendas
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_corte_da_venda();

-- ---------------------------------------------------------------------------------------------
-- A pertença não muda (ADR-0013, item 5): a função da V041, inteira, com os ramos novos.
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
    ELSIF TG_TABLE_NAME = 'acertos' THEN
        IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id THEN
            RAISE EXCEPTION 'O vendedor do acerto não muda (ADR-0013, item 5).' USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'leituras_acerto' THEN
        IF NEW.acerto_id IS DISTINCT FROM OLD.acerto_id THEN
            RAISE EXCEPTION 'O acerto da leitura não muda (ADR-0013, item 5).' USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.acertos
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.leituras_acerto
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();

REVOKE ALL ON corona.acertos, corona.leituras_acerto FROM PUBLIC;
