-- V020: inventário / contagem do estoque (Fase 2, tarefa 2.4; ESPECIFICACAO §7.6 e §13; ADR-0008, item 8).
--
-- Só do servidor, NUNCA sincronizada. Ao iniciar, o servidor congela o "esperado" de cada produto do escopo.
-- A contagem grava "contado" por leitura. Ao fechar, um AJUSTE por produto (contado - esperado congelado) é
-- lançado pelo serviço de estoque; entradas e saídas feitas durante a contagem não são desfeitas.
-- Inventário fechado ou cancelado é imutável (gatilhos).

CREATE TABLE corona.inventarios (
    id uuid PRIMARY KEY,
    escopo text NOT NULL CONSTRAINT inventarios_escopo_conhecido CHECK (escopo IN ('TODOS', 'CATEGORIA', 'FORNECEDOR')),
    escopo_id uuid,
    situacao text NOT NULL DEFAULT 'EM_ANDAMENTO' CONSTRAINT inventarios_situacao_conhecida
        CHECK (situacao IN ('EM_ANDAMENTO', 'FECHADO', 'CANCELADO')),
    iniciado_em timestamptz NOT NULL,
    iniciado_por uuid NOT NULL REFERENCES corona.usuarios (id),
    fechado_em timestamptz,
    fechado_por uuid REFERENCES corona.usuarios (id),
    -- Se a dona confirmou que a peça não lida vale zero no fechamento.
    zerou_nao_lidos boolean,
    cancelado_em timestamptz,
    cancelado_por uuid REFERENCES corona.usuarios (id),
    motivo_cancelamento text CONSTRAINT inventarios_motivo_cancelamento_preenchido
        CHECK (motivo_cancelamento IS NULL OR btrim(motivo_cancelamento) <> ''),
    observacoes text CONSTRAINT inventarios_observacoes_preenchidas CHECK (observacoes IS NULL OR btrim(observacoes) <> ''),
    CONSTRAINT inventarios_escopo_com_id CHECK ((escopo = 'TODOS') = (escopo_id IS NULL)),
    CONSTRAINT inventarios_situacao_completa CHECK (
        (situacao = 'EM_ANDAMENTO' AND fechado_em IS NULL AND fechado_por IS NULL AND zerou_nao_lidos IS NULL
            AND cancelado_em IS NULL AND cancelado_por IS NULL)
        OR (situacao = 'FECHADO' AND fechado_em IS NOT NULL AND fechado_por IS NOT NULL AND zerou_nao_lidos IS NOT NULL
            AND cancelado_em IS NULL AND cancelado_por IS NULL)
        OR (situacao = 'CANCELADO' AND cancelado_em IS NOT NULL AND cancelado_por IS NOT NULL
            AND fechado_em IS NULL AND fechado_por IS NULL AND zerou_nao_lidos IS NULL))
);
COMMENT ON TABLE corona.inventarios IS
    'Só do servidor, NUNCA sincronizada: contagem do estoque. Só a situação muda (EM_ANDAMENTO -> FECHADO ou CANCELADO); depois disso é imutável.';
SELECT corona.aplicar_convencoes_do_servidor('corona.inventarios', editavel => true);
CREATE INDEX inventarios_por_situacao ON corona.inventarios (situacao, iniciado_em DESC, id);

CREATE FUNCTION corona.proteger_inventario() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.situacao <> 'EM_ANDAMENTO' THEN
        RAISE EXCEPTION 'O inventário % já foi encerrado e não pode mais mudar.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.escopo IS DISTINCT FROM OLD.escopo OR NEW.escopo_id IS DISTINCT FROM OLD.escopo_id
        OR NEW.iniciado_em IS DISTINCT FROM OLD.iniciado_em OR NEW.iniciado_por IS DISTINCT FROM OLD.iniciado_por THEN
        RAISE EXCEPTION 'O escopo do inventário % não pode ser alterado.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inventarios_encerrado_imutavel BEFORE UPDATE ON corona.inventarios
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_inventario();

CREATE TABLE corona.itens_inventario (
    id uuid PRIMARY KEY,
    inventario_id uuid NOT NULL REFERENCES corona.inventarios (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    -- Congelado ao iniciar; nunca muda.
    esperado integer NOT NULL CONSTRAINT itens_inventario_esperado_nao_negativo CHECK (esperado >= 0),
    -- Vazio = ainda não foi lido.
    contado integer CONSTRAINT itens_inventario_contado_nao_negativo CHECK (contado IS NULL OR contado >= 0),
    -- O ajuste que o fechamento gerou para este produto (vazio se não houve diferença ou ainda não fechou).
    movimento_id uuid REFERENCES corona.movimentacoes_estoque (id),
    CONSTRAINT itens_inventario_produto_unico UNIQUE (inventario_id, produto_id)
);
COMMENT ON TABLE corona.itens_inventario IS
    'Só do servidor, NUNCA sincronizada: um produto dentro de um inventário, com o esperado congelado e o contado.';
SELECT corona.aplicar_convencoes_do_servidor('corona.itens_inventario', editavel => true);
CREATE INDEX itens_inventario_por_produto ON corona.itens_inventario (produto_id);

CREATE FUNCTION corona.proteger_item_de_inventario() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.inventario_id IS DISTINCT FROM OLD.inventario_id OR NEW.produto_id IS DISTINCT FROM OLD.produto_id
        OR NEW.esperado IS DISTINCT FROM OLD.esperado THEN
        RAISE EXCEPTION 'O esperado congelado do inventário não pode ser alterado.' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (SELECT situacao FROM corona.inventarios WHERE id = OLD.inventario_id) <> 'EM_ANDAMENTO' THEN
        RAISE EXCEPTION 'O inventário % já foi encerrado e não pode mais mudar.', OLD.inventario_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER itens_inventario_encerrado_imutavel BEFORE UPDATE ON corona.itens_inventario
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_item_de_inventario();

-- Cada leitura (bipe ou valor definido) é um registro imutável; o id vem do cliente e torna o reenvio seguro.
CREATE TABLE corona.leituras_inventario (
    id uuid PRIMARY KEY,
    inventario_id uuid NOT NULL REFERENCES corona.inventarios (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    modo text NOT NULL CONSTRAINT leituras_inventario_modo_conhecido CHECK (modo IN ('SOMAR', 'DEFINIR')),
    quantidade integer NOT NULL CONSTRAINT leituras_inventario_quantidade_valida CHECK (
        (modo = 'SOMAR' AND quantidade > 0) OR (modo = 'DEFINIR' AND quantidade >= 0)),
    usuario_id uuid NOT NULL REFERENCES corona.usuarios (id),
    lida_em timestamptz NOT NULL
);
COMMENT ON TABLE corona.leituras_inventario IS
    'Só do servidor, NUNCA sincronizada, imutável: cada leitura da contagem (SOMAR uma quantidade ou DEFINIR o contado).';
SELECT corona.aplicar_convencoes_do_servidor('corona.leituras_inventario', editavel => false);
CREATE INDEX leituras_inventario_por_inventario ON corona.leituras_inventario (inventario_id, lida_em, id);

REVOKE ALL ON corona.inventarios, corona.itens_inventario, corona.leituras_inventario FROM PUBLIC;
