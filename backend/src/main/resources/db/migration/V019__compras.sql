-- V019: compras, itens de compra e histórico de custo (Fase 2, tarefa 2.3; ESPECIFICACAO §9 e §13; ADR-0008).
--
-- Só do servidor: o vendedor nunca recebe compra nem custo. A compra é gravada de uma vez, com os itens, e é
-- imutável: o único campo que muda é a situação (REGISTRADA -> ESTORNADA), junto com os dados de quem desfez.
-- O estoque só muda pelo serviço de estoque (cada item guarda o movimento que ele gerou).
-- Dinheiro em NUMERIC(14,2). Datas em UTC.

CREATE TABLE corona.compras (
    id uuid PRIMARY KEY,
    fornecedor_id uuid NOT NULL REFERENCES corona.fornecedores (id),
    data_compra date NOT NULL,
    numero text CONSTRAINT compras_numero_preenchido CHECK (numero IS NULL OR btrim(numero) <> ''),
    observacoes text CONSTRAINT compras_observacoes_preenchidas CHECK (observacoes IS NULL OR btrim(observacoes) <> ''),
    -- Calculado pelo servidor: soma dos totais dos itens.
    total numeric(14, 2) NOT NULL CONSTRAINT compras_total_nao_negativo CHECK (total >= 0),
    situacao text NOT NULL DEFAULT 'REGISTRADA' CONSTRAINT compras_situacao_conhecida
        CHECK (situacao IN ('REGISTRADA', 'ESTORNADA')),
    usuario_id uuid NOT NULL REFERENCES corona.usuarios (id),
    -- Preenchidos só quando a compra é desfeita.
    estorno_id uuid,
    estorno_motivo text,
    estornada_em timestamptz,
    estornada_por uuid REFERENCES corona.usuarios (id),
    CONSTRAINT compras_estorno_completo CHECK (
        (situacao = 'REGISTRADA' AND estorno_id IS NULL AND estorno_motivo IS NULL AND estornada_em IS NULL AND estornada_por IS NULL)
        OR (situacao = 'ESTORNADA' AND estorno_id IS NOT NULL AND estorno_motivo IS NOT NULL
            AND estornada_em IS NOT NULL AND estornada_por IS NOT NULL))
);
COMMENT ON TABLE corona.compras IS
    'Só do servidor, NUNCA sincronizada: compra de mercadoria. Imutável; só a situação muda (estorno). Correção = desfazer e lançar de novo.';
SELECT corona.aplicar_convencoes_do_servidor('corona.compras', editavel => true);
CREATE INDEX compras_por_data ON corona.compras (data_compra DESC, id);
CREATE INDEX compras_por_fornecedor ON corona.compras (fornecedor_id, data_compra DESC);

-- Compra imutável: só a situação (e os dados do estorno) podem mudar, e só de REGISTRADA para ESTORNADA.
CREATE FUNCTION corona.proteger_compra() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.situacao <> 'REGISTRADA' THEN
        RAISE EXCEPTION 'A compra % já foi desfeita e não pode mais mudar.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.fornecedor_id IS DISTINCT FROM OLD.fornecedor_id OR NEW.data_compra IS DISTINCT FROM OLD.data_compra
        OR NEW.numero IS DISTINCT FROM OLD.numero OR NEW.observacoes IS DISTINCT FROM OLD.observacoes
        OR NEW.total IS DISTINCT FROM OLD.total OR NEW.usuario_id IS DISTINCT FROM OLD.usuario_id THEN
        RAISE EXCEPTION 'O conteúdo da compra % não pode ser alterado: desfaça a compra e lance de novo.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER compras_conteudo_imutavel BEFORE UPDATE ON corona.compras
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_compra();

CREATE TABLE corona.itens_compra (
    id uuid PRIMARY KEY,
    compra_id uuid NOT NULL REFERENCES corona.compras (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    quantidade integer NOT NULL CONSTRAINT itens_compra_quantidade_positiva CHECK (quantidade > 0),
    custo_unitario numeric(14, 2) NOT NULL CONSTRAINT itens_compra_custo_nao_negativo CHECK (custo_unitario >= 0),
    -- quantidade x custo_unitario, calculado pelo servidor (HALF_UP, 2 casas).
    total numeric(14, 2) NOT NULL CONSTRAINT itens_compra_total_nao_negativo CHECK (total >= 0),
    -- O movimento de entrada que este item gerou no estoque (para desfazer).
    movimento_id uuid NOT NULL REFERENCES corona.movimentacoes_estoque (id),
    CONSTRAINT itens_compra_produto_unico UNIQUE (compra_id, produto_id)
);
COMMENT ON TABLE corona.itens_compra IS
    'Só do servidor, histórico imutável: itens de uma compra. Cada produto aparece uma vez por compra.';
SELECT corona.aplicar_convencoes_do_servidor('corona.itens_compra', editavel => false);
CREATE INDEX itens_compra_por_compra ON corona.itens_compra (compra_id);
CREATE INDEX itens_compra_por_produto ON corona.itens_compra (produto_id);

-- Histórico do custo de cada produto: nunca apagado. "ordem" cresce na ordem em que os custos entraram
-- (o produto está travado nessa hora), então a maior ordem é a compra mais recente.
CREATE TABLE corona.historico_custo (
    id uuid PRIMARY KEY,
    ordem bigint GENERATED ALWAYS AS IDENTITY,
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    compra_id uuid NOT NULL REFERENCES corona.compras (id),
    custo numeric(14, 2) NOT NULL CONSTRAINT historico_custo_nao_negativo CHECK (custo >= 0),
    -- O custo que o produto tinha antes desta compra (vazio se ainda não tinha custo).
    custo_anterior numeric(14, 2) CONSTRAINT historico_custo_anterior_nao_negativo CHECK (custo_anterior IS NULL OR custo_anterior >= 0),
    data_compra date NOT NULL
);
COMMENT ON TABLE corona.historico_custo IS
    'Só do servidor, NUNCA sincronizada, imutável: cada custo que uma compra deu ao produto. Só a dona vê.';
SELECT corona.aplicar_convencoes_do_servidor('corona.historico_custo', editavel => false);
CREATE UNIQUE INDEX historico_custo_ordem_unica ON corona.historico_custo (ordem);
CREATE UNIQUE INDEX historico_custo_compra_produto ON corona.historico_custo (compra_id, produto_id);
CREATE INDEX historico_custo_por_produto ON corona.historico_custo (produto_id, ordem DESC);

REVOKE ALL ON corona.compras, corona.itens_compra, corona.historico_custo FROM PUBLIC;
