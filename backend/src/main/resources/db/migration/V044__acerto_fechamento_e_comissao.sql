-- V044: acerto, parte 2: fechamento, comissão por meta e pendências (Fase 7, tarefa 7.5; ADR-0012, itens 3.6, 5, 6, 8, 13, 14, 15
-- e 16; ESPECIFICACAO §7.4, §9, §10, §11 e §22; plano aprovado no PR #166).
--
-- O fechamento é uma transação só: itens do acerto (esperado x contado), eventos ligados, comissões, pendências, os totais e o
-- status ACERTADO das maletas e do acerto. Tudo em Real; o único valor convertido gravado é a soma em Guarani que decidiu o
-- percentual da comissão (ADR-0012, item 2). Aqui o banco é a segunda trava de cada regra: o serviço pode errar, o banco não
-- deixa gravar errado. O dinheiro recebido no acerto e os recebimentos são da 7.6 (por migração nova): nesta tarefa
-- recebido_no_acerto = 0 e ficou_devendo = liquido.
-- Nenhuma migração publicada (V001 a V043) é editada: as funções da V043 são recriadas inteiras (CREATE OR REPLACE).

-- ---------------------------------------------------------------------------------------------
-- 1. configuracoes: a meta e os dois percentuais da comissão (ADR-0012, item 5; iguais para todos os vendedores).
-- ---------------------------------------------------------------------------------------------
INSERT INTO corona.configuracoes (id, chave, tipo, valor_inteiro, valor_dinheiro) VALUES
    (uuidv7(), 'comissao_meta_guarani', 'INTEIRO', 700000, NULL),
    (uuidv7(), 'comissao_percentual_abaixo_da_meta', 'INTEIRO', 30, NULL),
    (uuidv7(), 'comissao_percentual_na_meta', 'INTEIRO', 40, NULL);

ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_comissao_meta_faixa
    CHECK (chave <> 'comissao_meta_guarani' OR valor_inteiro BETWEEN 1 AND 1000000000);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_comissao_abaixo_faixa
    CHECK (chave <> 'comissao_percentual_abaixo_da_meta' OR valor_inteiro BETWEEN 0 AND 100);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_comissao_na_meta_faixa
    CHECK (chave <> 'comissao_percentual_na_meta' OR valor_inteiro BETWEEN 0 AND 100);

-- Segunda trava entre as duas chaves, adiada para o COMMIT (a dona muda as duas de uma vez, em qualquer ordem): o percentual
-- abaixo da meta nunca passa do percentual na meta.
CREATE FUNCTION corona.conferir_percentuais_da_comissao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    abaixo integer;
    na_meta integer;
BEGIN
    SELECT valor_inteiro INTO abaixo FROM corona.configuracoes WHERE chave = 'comissao_percentual_abaixo_da_meta';
    SELECT valor_inteiro INTO na_meta FROM corona.configuracoes WHERE chave = 'comissao_percentual_na_meta';
    IF abaixo > na_meta THEN
        RAISE EXCEPTION 'O percentual abaixo da meta (%) não pode passar do percentual na meta (%).', abaixo, na_meta
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER configuracoes_comissao_em_ordem AFTER INSERT OR UPDATE ON corona.configuracoes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (NEW.chave IN ('comissao_percentual_abaixo_da_meta', 'comissao_percentual_na_meta'))
    EXECUTE FUNCTION corona.conferir_percentuais_da_comissao();

-- ---------------------------------------------------------------------------------------------
-- 2. acertos: as colunas do fechamento (vazias enquanto EM_ACERTO ou DESISTIDO) e os CHECKs das contas do item 8.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.acertos
    -- O UUID do comando "fechar" (idempotência) e o resumo das decisões da dona (SHA-256), para o 409 de "outras decisões".
    ADD COLUMN fechamento_id uuid CONSTRAINT acertos_fechamento_unico UNIQUE,
    ADD COLUMN conteudo_do_fechamento text,
    ADD COLUMN fechado_em timestamptz,
    ADD COLUMN fechado_por uuid REFERENCES corona.usuarios (id),
    -- A cotação mais recente no instante do fechamento (ADR-0006, item 11): só para mostrar Dólar e Guarani.
    ADD COLUMN cotacao_id uuid REFERENCES corona.cotacoes (id),
    -- O que decidiu o percentual: a meta usada, a soma em Guarani (a única conversão gravada) e o percentual decidido.
    ADD COLUMN meta_guarani integer,
    ADD COLUMN percentual_comissao numeric(5, 2),
    ADD COLUMN vendido_para_meta_guarani bigint,
    -- Os totais do item 8, em Real.
    ADD COLUMN vendido_bruto numeric(14, 2),
    ADD COLUMN descontos numeric(14, 2),
    ADD COLUMN devolvido numeric(14, 2),
    ADD COLUMN vendido_liquido numeric(14, 2),
    ADD COLUMN comissao numeric(14, 2),
    ADD COLUMN faltas_cobradas numeric(14, 2),
    ADD COLUMN liquido numeric(14, 2),
    ADD COLUMN recebido_no_acerto numeric(14, 2),
    ADD COLUMN ficou_devendo numeric(14, 2);

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
        AND recebido_no_acerto IS NULL AND ficou_devendo IS NULL));
-- As contas do item 8 (a C2 do ADR-0012, item 17). devolvido, comissao, liquido e ficou_devendo aceitam qualquer sinal: o acerto
-- a favor do vendedor nunca esconde o sinal.
ALTER TABLE corona.acertos ADD CONSTRAINT acertos_vendido_liquido_confere
    CHECK (vendido_liquido = vendido_bruto - descontos - devolvido);
ALTER TABLE corona.acertos ADD CONSTRAINT acertos_liquido_confere
    CHECK (liquido = vendido_liquido - comissao + faltas_cobradas);
ALTER TABLE corona.acertos ADD CONSTRAINT acertos_liquido_e_recebimento_conferem
    CHECK (liquido = recebido_no_acerto + ficou_devendo);
ALTER TABLE corona.acertos ADD CONSTRAINT acertos_valores_nao_negativos
    CHECK (vendido_bruto >= 0 AND descontos >= 0 AND faltas_cobradas >= 0 AND recebido_no_acerto >= 0
        AND meta_guarani > 0 AND percentual_comissao BETWEEN 0 AND 100 AND vendido_para_meta_guarani >= 0);
COMMENT ON COLUMN corona.acertos.conteudo_do_fechamento IS 'Resumo (SHA-256) das decisões da dona: o mesmo id com outras decisões é 409. Não vai para o aplicativo.';
COMMENT ON COLUMN corona.acertos.vendido_para_meta_guarani IS
    'Soma em Guarani que decidiu o percentual (cada venda pela cotação dela, arredondada sozinha). A única conversão gravada (ADR-0012, item 2).';
COMMENT ON COLUMN corona.acertos.liquido IS 'vendido_liquido - comissao + faltas_cobradas: o que o vendedor deve à loja neste acerto (negativo = a favor dele).';

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
              NEW.faltas_cobradas, NEW.liquido, NEW.recebido_no_acerto, NEW.ficou_devendo)
          IS DISTINCT FROM
          ROW(OLD.status, OLD.desistencia_id, OLD.desistido_em, OLD.desistido_por, OLD.motivo_desistencia, OLD.fechamento_id,
              OLD.conteudo_do_fechamento, OLD.fechado_em, OLD.fechado_por, OLD.cotacao_id, OLD.meta_guarani, OLD.percentual_comissao,
              OLD.vendido_para_meta_guarani, OLD.vendido_bruto, OLD.descontos, OLD.devolvido, OLD.vendido_liquido, OLD.comissao,
              OLD.faltas_cobradas, OLD.liquido, OLD.recebido_no_acerto, OLD.ficou_devendo) THEN
        RAISE EXCEPTION 'O acerto % já terminou e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- 3. acerto_eventos: cada evento do vendedor entra em um acerto só (sincronizável e imutável).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.acerto_eventos (
    id uuid PRIMARY KEY,
    acerto_id uuid NOT NULL REFERENCES corona.acertos (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    origem_tipo text NOT NULL CONSTRAINT acerto_eventos_origem_conhecida CHECK (origem_tipo IN ('VENDA', 'ESTORNO_VENDA', 'DEVOLUCAO')),
    -- A venda (VENDA, ESTORNO_VENDA) ou a devolução (DEVOLUCAO). A troca não tem tipo: a devolução e a venda nova já são eventos.
    origem_id uuid NOT NULL,
    venda_id uuid NOT NULL REFERENCES corona.vendas (id),
    -- Com sinal: VENDA positivo (o total depois do desconto); ESTORNO_VENDA e DEVOLUCAO negativos.
    valor numeric(14, 2) NOT NULL,
    CONSTRAINT acerto_eventos_sinal CHECK ((origem_tipo = 'VENDA' AND valor >= 0) OR (origem_tipo <> 'VENDA' AND valor <= 0)),
    CONSTRAINT acerto_eventos_origem_unica UNIQUE (origem_tipo, origem_id)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.acerto_eventos', imutavel => true);
COMMENT ON TABLE corona.acerto_eventos IS
    'Eventos do vendedor ligados a um acerto fechado (ADR-0012, itens 3.6 e 8): venda, estorno de venda e devolução. Cada evento entra em um acerto só; vendido_liquido = soma dos valores. Imutável.';
CREATE INDEX acerto_eventos_por_acerto ON corona.acerto_eventos (acerto_id);
CREATE INDEX acerto_eventos_por_vendedor ON corona.acerto_eventos (vendedor_id);
CREATE INDEX acerto_eventos_por_venda ON corona.acerto_eventos (venda_id);

CREATE FUNCTION corona.conferir_evento_de_acerto() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a record;
    v record;
    d record;
BEGIN
    SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = NEW.acerto_id FOR SHARE;
    IF a.status IS DISTINCT FROM 'EM_ACERTO' OR a.vendedor_id IS DISTINCT FROM NEW.vendedor_id THEN
        RAISE EXCEPTION 'O evento % só entra em acerto em andamento do mesmo vendedor.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.origem_tipo IN ('VENDA', 'ESTORNO_VENDA') THEN
        SELECT vendedor_id, total, status INTO v FROM corona.vendas WHERE id = NEW.origem_id;
        IF NOT FOUND OR v.vendedor_id <> NEW.vendedor_id OR NEW.venda_id <> NEW.origem_id THEN
            RAISE EXCEPTION 'O evento % precisa ser de uma venda do vendedor.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.origem_tipo = 'VENDA' AND NEW.valor <> v.total THEN
            RAISE EXCEPTION 'O valor do evento % precisa ser o total da venda (%).', NEW.id, v.total USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.origem_tipo = 'ESTORNO_VENDA' AND (v.status <> 'ESTORNADA' OR NEW.valor <> -v.total) THEN
            RAISE EXCEPTION 'O estorno % precisa ser de venda estornada, com o valor do total negativo.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSE
        SELECT vendedor_id, venda_id, situacao, valor_devolvido INTO d FROM corona.devolucoes WHERE id = NEW.origem_id;
        IF NOT FOUND OR d.vendedor_id <> NEW.vendedor_id OR d.venda_id <> NEW.venda_id THEN
            RAISE EXCEPTION 'O evento % precisa ser de uma devolução do vendedor.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
        IF d.situacao IN ('CANCELADA', 'EM_REVISAO') OR NEW.valor <> -d.valor_devolvido THEN
            RAISE EXCEPTION 'A devolução % só entra no acerto com o valor devolvido negativo, fora de cancelada e em revisão.', NEW.origem_id
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER acerto_eventos_conferido BEFORE INSERT ON corona.acerto_eventos
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_evento_de_acerto();

-- A devolução ligada a um acerto não pode mais ser cancelada (o valor e a comissão já estão no acerto).
CREATE FUNCTION corona.recusar_cancelar_devolucao_de_acerto() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM corona.acerto_eventos e WHERE e.origem_tipo = 'DEVOLUCAO' AND e.origem_id = OLD.id) THEN
        RAISE EXCEPTION 'A devolução % já está ligada a um acerto e não pode ser cancelada.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER devolucoes_de_acerto_nao_cancela BEFORE UPDATE ON corona.devolucoes
    FOR EACH ROW WHEN (NEW.situacao = 'CANCELADA' AND OLD.situacao IS DISTINCT FROM 'CANCELADA')
    EXECUTE FUNCTION corona.recusar_cancelar_devolucao_de_acerto();

-- ---------------------------------------------------------------------------------------------
-- 4. itens_acerto: esperado x contado, por peça e maleta (sincronizável e imutável).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.itens_acerto (
    id uuid PRIMARY KEY,
    acerto_id uuid NOT NULL REFERENCES corona.acertos (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    -- Vazia na linha "peças para a loja" (as devoluções que o vendedor trouxe e não estão em nenhuma maleta).
    remessa_id uuid REFERENCES corona.remessas (id),
    -- O preço de venda congelado na maleta (nunca o custo). Vazio na linha da loja.
    preco_remessa numeric(14, 2) CONSTRAINT itens_acerto_preco_nao_negativo CHECK (preco_remessa >= 0),
    esperado integer NOT NULL CONSTRAINT itens_acerto_esperado_nao_negativo CHECK (esperado >= 0),
    contado_boa integer NOT NULL CONSTRAINT itens_acerto_contado_boa_nao_negativo CHECK (contado_boa >= 0),
    contado_defeito integer NOT NULL CONSTRAINT itens_acerto_contado_defeito_nao_negativo CHECK (contado_defeito >= 0),
    falta integer NOT NULL CONSTRAINT itens_acerto_falta_nao_negativa CHECK (falta >= 0),
    -- Dos que vieram com defeito, quantos a dona aprovou (os outros voltam como boas).
    defeito_aprovado integer NOT NULL CONSTRAINT itens_acerto_defeito_aprovado_nao_negativo CHECK (defeito_aprovado >= 0),
    CONSTRAINT itens_acerto_confere CHECK (esperado = contado_boa + contado_defeito + falta),
    CONSTRAINT itens_acerto_aprovado_cabe CHECK (defeito_aprovado <= contado_defeito),
    CONSTRAINT itens_acerto_linha_unica UNIQUE NULLS NOT DISTINCT (acerto_id, produto_id, remessa_id)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.itens_acerto', imutavel => true);
COMMENT ON TABLE corona.itens_acerto IS
    'Esperado x contado do fechamento, uma linha por peça e maleta (ADR-0012, item 3.6). Sem maleta = peças para a loja. Imutável.';
CREATE INDEX itens_acerto_por_acerto ON corona.itens_acerto (acerto_id);

CREATE FUNCTION corona.conferir_item_de_acerto() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    situacao text;
BEGIN
    SELECT status INTO situacao FROM corona.acertos WHERE id = NEW.acerto_id FOR SHARE;
    IF situacao IS DISTINCT FROM 'EM_ACERTO' THEN
        RAISE EXCEPTION 'O item % só entra em acerto em andamento.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER itens_acerto_conferido BEFORE INSERT ON corona.itens_acerto
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_item_de_acerto();

-- ---------------------------------------------------------------------------------------------
-- 5. comissoes: uma linha COMISSAO por venda e uma linha ESTORNO por estorno ou devolução (sincronizável e imutável).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.comissoes (
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    acerto_id uuid NOT NULL REFERENCES corona.acertos (id),
    venda_id uuid NOT NULL REFERENCES corona.vendas (id),
    -- O evento do acerto que gerou a linha (a venda, para COMISSAO; o estorno ou a devolução, para ESTORNO).
    evento_id uuid NOT NULL REFERENCES corona.acerto_eventos (id),
    tipo text NOT NULL CONSTRAINT comissoes_tipo_conhecido CHECK (tipo IN ('COMISSAO', 'ESTORNO')),
    -- COMISSAO: o total da venda. ESTORNO: o valor devolvido (positivo).
    base numeric(14, 2) NOT NULL CONSTRAINT comissoes_base_nao_negativa CHECK (base >= 0),
    -- O percentual decidido do acerto (COMISSAO) ou o da linha original (ESTORNO).
    percentual numeric(5, 2) NOT NULL CONSTRAINT comissoes_percentual_valido CHECK (percentual BETWEEN 0 AND 100),
    valor numeric(14, 2) NOT NULL,
    -- ESTORNO: a linha COMISSAO da venda (uma venda pode ter várias devoluções; o único é por evento).
    estorna_id uuid REFERENCES corona.comissoes (id),
    CONSTRAINT comissoes_sinal_e_origem CHECK (
        (tipo = 'COMISSAO' AND valor >= 0 AND estorna_id IS NULL) OR (tipo = 'ESTORNO' AND valor < 0 AND estorna_id IS NOT NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.comissoes', imutavel => true);
COMMENT ON TABLE corona.comissoes IS
    'Comissão por venda e estorno proporcional pelo percentual da linha original (ADR-0012, item 5). Toda linha pertence a um acerto ACERTADO e conta como paga. Imutável.';
CREATE UNIQUE INDEX comissoes_uma_por_venda ON corona.comissoes (venda_id) WHERE tipo = 'COMISSAO';
CREATE UNIQUE INDEX comissoes_um_estorno_por_evento ON corona.comissoes (evento_id) WHERE tipo = 'ESTORNO';
CREATE INDEX comissoes_por_acerto ON corona.comissoes (acerto_id);
CREATE INDEX comissoes_por_vendedor ON corona.comissoes (vendedor_id);
CREATE INDEX comissoes_por_venda ON corona.comissoes (venda_id);

CREATE FUNCTION corona.conferir_comissao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a record;
    e record;
    v record;
    original record;
    estornado numeric;
BEGIN
    SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = NEW.acerto_id FOR SHARE;
    IF a.status IS DISTINCT FROM 'EM_ACERTO' OR a.vendedor_id IS DISTINCT FROM NEW.vendedor_id THEN
        RAISE EXCEPTION 'A comissão % só entra em acerto em andamento do mesmo vendedor.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    SELECT acerto_id, origem_tipo, venda_id, valor INTO e FROM corona.acerto_eventos WHERE id = NEW.evento_id;
    IF NOT FOUND OR e.acerto_id <> NEW.acerto_id OR e.venda_id <> NEW.venda_id THEN
        RAISE EXCEPTION 'A comissão % precisa ser de um evento deste acerto e da mesma venda.', NEW.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.tipo = 'COMISSAO' THEN
        SELECT total INTO v FROM corona.vendas WHERE id = NEW.venda_id;
        IF e.origem_tipo <> 'VENDA' OR NEW.base <> v.total THEN
            RAISE EXCEPTION 'A comissão % é da venda, sobre o total dela.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSE
        IF e.origem_tipo NOT IN ('ESTORNO_VENDA', 'DEVOLUCAO') OR NEW.base <> -e.valor THEN
            RAISE EXCEPTION 'O estorno de comissão % é de um estorno ou de uma devolução, sobre o valor devolvido.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
        SELECT tipo, venda_id, percentual, valor INTO original FROM corona.comissoes WHERE id = NEW.estorna_id;
        IF NOT FOUND OR original.tipo <> 'COMISSAO' OR original.venda_id <> NEW.venda_id THEN
            RAISE EXCEPTION 'O estorno de comissão % precisa apontar para a comissão da mesma venda.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.percentual <> original.percentual THEN
            RAISE EXCEPTION 'O estorno de comissão % usa o percentual da linha original (%).', NEW.id, original.percentual
                USING ERRCODE = 'restrict_violation';
        END IF;
        SELECT coalesce(sum(valor), 0) INTO estornado FROM corona.comissoes WHERE venda_id = NEW.venda_id AND tipo = 'ESTORNO';
        IF estornado + NEW.valor < -original.valor THEN
            RAISE EXCEPTION 'Os estornos da venda % passariam da comissão dela (%).', NEW.venda_id, original.valor USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER comissoes_conferida BEFORE INSERT ON corona.comissoes
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_comissao();

-- ---------------------------------------------------------------------------------------------
-- 6. pendencias_acerto: falta, defeito e o que a dona não aceitou (sincronizável; só o status muda, por gatilho).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.pendencias_acerto (
    id uuid PRIMARY KEY,
    -- O acerto onde a pendência nasceu.
    acerto_id uuid NOT NULL REFERENCES corona.acertos (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    tipo text NOT NULL CONSTRAINT pendencias_acerto_tipo_conhecido
        CHECK (tipo IN ('FALTA', 'DEFEITO', 'DEVOLUCAO_NAO_ACEITA', 'CREDITO_SEM_SALDO_NAO_ACEITO')),
    produto_id uuid REFERENCES corona.produtos (id),
    remessa_id uuid REFERENCES corona.remessas (id),
    quantidade integer CONSTRAINT pendencias_acerto_quantidade_positiva CHECK (quantidade > 0),
    valor numeric(14, 2) NOT NULL CONSTRAINT pendencias_acerto_valor_nao_negativo CHECK (valor >= 0),
    -- A devolução ou o crédito sem saldo que a dona não aceitou (ADR-0014, itens 5 e 9); único.
    origem_tipo text CONSTRAINT pendencias_acerto_origem_conhecida CHECK (origem_tipo IN ('DEVOLUCAO', 'CREDITO_SEM_SALDO')),
    origem_id uuid,
    status text NOT NULL CONSTRAINT pendencias_acerto_status_conhecido
        CHECK (status IN ('ABERTA', 'COBRADA', 'ASSUMIDA_PELA_LOJA', 'ENCONTRADA', 'APROVADA', 'RECUSADA')),
    cobrada_no_acerto_id uuid REFERENCES corona.acertos (id),
    -- O UUID do comando "resolver" (idempotência).
    resolucao_id uuid CONSTRAINT pendencias_acerto_resolucao_unica UNIQUE,
    resolvida_em timestamptz,
    resolvida_por uuid REFERENCES corona.usuarios (id),
    motivo text CONSTRAINT pendencias_acerto_motivo_curto CHECK (length(motivo) <= 500),
    CONSTRAINT pendencias_acerto_do_tipo CHECK (
        (tipo IN ('FALTA', 'DEFEITO') AND produto_id IS NOT NULL AND quantidade IS NOT NULL AND origem_id IS NULL)
        OR (tipo = 'DEVOLUCAO_NAO_ACEITA' AND origem_tipo = 'DEVOLUCAO' AND origem_id IS NOT NULL)
        OR (tipo = 'CREDITO_SEM_SALDO_NAO_ACEITO' AND origem_tipo = 'CREDITO_SEM_SALDO' AND origem_id IS NOT NULL)),
    CONSTRAINT pendencias_acerto_status_do_tipo CHECK (
        (tipo = 'DEFEITO' AND status IN ('APROVADA', 'RECUSADA'))
        OR (tipo <> 'DEFEITO' AND status IN ('ABERTA', 'COBRADA', 'ASSUMIDA_PELA_LOJA'))
        OR (tipo = 'FALTA' AND status = 'ENCONTRADA')),
    CONSTRAINT pendencias_acerto_cobranca CHECK ((status = 'COBRADA') = (cobrada_no_acerto_id IS NOT NULL)),
    CONSTRAINT pendencias_acerto_resolucao_completa CHECK (
        (resolvida_em IS NULL) = (resolvida_por IS NULL)
        AND (status NOT IN ('ASSUMIDA_PELA_LOJA', 'ENCONTRADA')
             OR (motivo IS NOT NULL AND btrim(motivo) <> '' AND resolvida_em IS NOT NULL)))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.pendencias_acerto');
COMMENT ON TABLE corona.pendencias_acerto IS
    'Pendências do acerto (ADR-0012, item 6): falta, defeito e o que a dona não aceitou. Nasce decidida no fechamento; depois só ABERTA vira COBRADA, ASSUMIDA_PELA_LOJA ou ENCONTRADA (falta). SOBRA nunca é gravada.';
CREATE UNIQUE INDEX pendencias_acerto_origem_unica ON corona.pendencias_acerto (origem_tipo, origem_id) WHERE origem_id IS NOT NULL;
CREATE INDEX pendencias_acerto_por_acerto ON corona.pendencias_acerto (acerto_id);
CREATE INDEX pendencias_acerto_por_vendedor ON corona.pendencias_acerto (vendedor_id, status);
CREATE INDEX pendencias_acerto_cobradas ON corona.pendencias_acerto (cobrada_no_acerto_id) WHERE cobrada_no_acerto_id IS NOT NULL;

CREATE FUNCTION corona.conferir_pendencia_de_acerto() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a record;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = NEW.acerto_id FOR SHARE;
        IF a.status IS DISTINCT FROM 'EM_ACERTO' OR a.vendedor_id IS DISTINCT FROM NEW.vendedor_id THEN
            RAISE EXCEPTION 'A pendência % só nasce em acerto em andamento do mesmo vendedor.', NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.status = 'ENCONTRADA' OR (NEW.status = 'COBRADA' AND NEW.cobrada_no_acerto_id IS DISTINCT FROM NEW.acerto_id) THEN
            RAISE EXCEPTION 'A pendência % nasce decidida no fechamento: cobrada neste acerto, aberta, assumida pela loja, aprovada ou recusada.', NEW.id
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: o conteúdo nunca muda.
    IF NEW.acerto_id IS DISTINCT FROM OLD.acerto_id OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.tipo IS DISTINCT FROM OLD.tipo
            OR NEW.produto_id IS DISTINCT FROM OLD.produto_id OR NEW.remessa_id IS DISTINCT FROM OLD.remessa_id
            OR NEW.quantidade IS DISTINCT FROM OLD.quantidade OR NEW.valor IS DISTINCT FROM OLD.valor
            OR NEW.origem_tipo IS DISTINCT FROM OLD.origem_tipo OR NEW.origem_id IS DISTINCT FROM OLD.origem_id
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'O conteúdo da pendência % não pode ser alterado.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        IF NEW.cobrada_no_acerto_id IS DISTINCT FROM OLD.cobrada_no_acerto_id OR NEW.resolucao_id IS DISTINCT FROM OLD.resolucao_id
                OR NEW.resolvida_em IS DISTINCT FROM OLD.resolvida_em OR NEW.resolvida_por IS DISTINCT FROM OLD.resolvida_por
                OR NEW.motivo IS DISTINCT FROM OLD.motivo THEN
            RAISE EXCEPTION 'A decisão da pendência % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'ABERTA' THEN
        RAISE EXCEPTION 'A pendência % já foi decidida e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status = 'COBRADA' THEN
        SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = NEW.cobrada_no_acerto_id FOR SHARE;
        IF NOT FOUND OR a.status <> 'EM_ACERTO' OR a.vendedor_id <> OLD.vendedor_id THEN
            RAISE EXCEPTION 'A pendência % só é cobrada num acerto em andamento do mesmo vendedor.', OLD.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF NEW.status NOT IN ('ASSUMIDA_PELA_LOJA', 'ENCONTRADA') THEN
        RAISE EXCEPTION 'A pendência % aberta só passa a cobrada, assumida pela loja ou encontrada.', OLD.id USING ERRCODE = 'restrict_violation';
    ELSIF NEW.status = 'ENCONTRADA' AND OLD.tipo <> 'FALTA' THEN
        RAISE EXCEPTION 'Só a falta pode ser encontrada (pendência %).', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER pendencias_acerto_conferida BEFORE INSERT OR UPDATE ON corona.pendencias_acerto
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_pendencia_de_acerto();

-- ---------------------------------------------------------------------------------------------
-- 7. remessas: a maleta ACERTADA (a função da V043, inteira, com o ramo novo).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION corona.conferir_acerto_da_remessa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a record;
BEGIN
    IF OLD.status = 'ACERTADA' THEN
        IF NEW.status IS DISTINCT FROM OLD.status OR NEW.acerto_id IS DISTINCT FROM OLD.acerto_id
                OR NEW.status_antes_do_acerto IS DISTINCT FROM OLD.status_antes_do_acerto THEN
            RAISE EXCEPTION 'A maleta % já foi acertada e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF NEW.status = 'ACERTADA' THEN
        -- Só do acerto em andamento, mantendo a ligação, com saldo zero em todas as peças.
        SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = OLD.acerto_id;
        IF OLD.status <> 'EM_ACERTO' OR NEW.acerto_id IS DISTINCT FROM OLD.acerto_id
                OR NEW.status_antes_do_acerto IS DISTINCT FROM OLD.status_antes_do_acerto
                OR NOT FOUND OR a.status <> 'EM_ACERTO' OR a.vendedor_id <> NEW.vendedor_id THEN
            RAISE EXCEPTION 'A maleta % só passa a acertada vinda do acerto em andamento do mesmo vendedor.', OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF EXISTS (SELECT 1 FROM (
                       SELECT t.produto_id, sum(t.q) AS saldo FROM (
                           SELECT i.produto_id, i.qtd_enviada AS q FROM corona.itens_remessa i
                            WHERE i.remessa_id = OLD.id AND i.deleted_at IS NULL
                           UNION ALL
                           SELECT m.produto_id, m.quantidade FROM corona.movimentos_remessa m WHERE m.remessa_id = OLD.id) t
                        GROUP BY t.produto_id) s
                    WHERE s.saldo <> 0) THEN
            RAISE EXCEPTION 'A maleta % só fica acertada com saldo zero em todas as peças.', OLD.id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF OLD.status <> 'EM_ACERTO' AND NEW.status = 'EM_ACERTO' THEN
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

-- Maleta acertada não recebe movimento novo (a primeira trava é o serviço; hoje nenhum caminho faz isso).
CREATE FUNCTION corona.recusar_movimento_em_maleta_acertada() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    situacao text;
BEGIN
    SELECT status INTO situacao FROM corona.remessas WHERE id = NEW.remessa_id;
    IF situacao = 'ACERTADA' THEN
        RAISE EXCEPTION 'A maleta % já foi acertada e não recebe movimento novo.', NEW.remessa_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER movimentos_remessa_sem_maleta_acertada BEFORE INSERT ON corona.movimentos_remessa
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_movimento_em_maleta_acertada();

-- ---------------------------------------------------------------------------------------------
-- 8. O gatilho adiado do fechamento: no COMMIT, os totais batem com os eventos, as comissões e as pendências (a C2 e a C3 como
-- segunda trava) e nenhuma maleta do acerto ficou fora de ACERTADA.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION corona.conferir_acerto_fechado() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    soma_eventos numeric;
    bruto numeric;
    descontos numeric;
    soma_comissoes numeric;
    soma_faltas numeric;
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
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER acertos_fechado_confere AFTER UPDATE ON corona.acertos
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (OLD.status = 'EM_ACERTO' AND NEW.status = 'ACERTADO')
    EXECUTE FUNCTION corona.conferir_acerto_fechado();

-- ---------------------------------------------------------------------------------------------
-- 9. O estoque central aceita a origem ACERTO (peças "para a loja", peça encontrada e a avaria do defeito aprovado).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE corona.movimentacoes_estoque DROP CONSTRAINT movimentacoes_estoque_origem_conhecida;
ALTER TABLE corona.movimentacoes_estoque ADD CONSTRAINT movimentacoes_estoque_origem_conhecida
    CHECK (origem_tipo IN ('MANUAL', 'AJUSTE_MANUAL', 'COMPRA', 'INVENTARIO', 'MALETA', 'ESTORNO', 'ACERTO'));

-- ---------------------------------------------------------------------------------------------
-- 10. A pertença não muda (ADR-0013, item 5): a função da V043, inteira, com o ramo da pendência.
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
    ELSIF TG_TABLE_NAME = 'pendencias_acerto' THEN
        IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.acerto_id IS DISTINCT FROM OLD.acerto_id THEN
            RAISE EXCEPTION 'O vendedor e o acerto da pendência não mudam (ADR-0013, item 5).' USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.pendencias_acerto
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_pertenca();

REVOKE ALL ON corona.acerto_eventos, corona.itens_acerto, corona.comissoes, corona.pendencias_acerto FROM PUBLIC;
