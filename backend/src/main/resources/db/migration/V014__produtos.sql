-- V014: produtos, custos e códigos (Fase 1, tarefa 1.5; ESPECIFICACAO §7.6, §8, §9 e ADR-0007).
--
-- produtos e produto_codigos são sincronizáveis (o vendedor recebe o produto e TODOS os códigos, sem custo).
-- produtos_custos é só do servidor: o preço de custo nunca está numa tabela sincronizada nem sai em
-- resposta que um vendedor possa ver. O custo é gravado na mesma transação do produto (1 para 1).
-- Dinheiro em NUMERIC(14,2). Exclusão lógica; unicidade só entre os não excluídos (índices parciais).

-- ---------------------------------------------------------------------------------------------
-- Produtos (sincronizável, SEM custo)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.produtos (
    id uuid PRIMARY KEY,
    nome text NOT NULL CONSTRAINT produtos_nome_preenchido CHECK (btrim(nome) <> ''),
    referencia text,
    categoria_id uuid NOT NULL REFERENCES corona.categorias (id),
    fornecedor_id uuid REFERENCES corona.fornecedores (id),
    preco_venda numeric(14, 2) NOT NULL CONSTRAINT produtos_preco_venda_nao_negativo CHECK (preco_venda >= 0),
    estoque_minimo integer NOT NULL DEFAULT 0 CONSTRAINT produtos_estoque_minimo_nao_negativo CHECK (estoque_minimo >= 0),
    -- Só as movimentações de estoque (Fase 2) mexem aqui; a API de produtos nunca o aceita de entrada.
    estoque_atual integer NOT NULL DEFAULT 0,
    ativo boolean NOT NULL DEFAULT true,
    -- Reservado para a tarefa 1.6 (fotos, §20): hash do arquivo da foto principal. Nunca o binário.
    foto_principal_hash text
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.produtos');
COMMENT ON COLUMN corona.produtos.estoque_atual IS
    'Somente leitura para a API de produtos: calculado pelas movimentações de estoque (Fase 2). Começa em 0.';
COMMENT ON COLUMN corona.produtos.foto_principal_hash IS
    'Reservado para a tarefa 1.6: hash da foto principal (a foto é um arquivo, nunca binário no banco).';
CREATE INDEX produtos_nome_idx ON corona.produtos (lower(nome)) WHERE deleted_at IS NULL;
CREATE INDEX produtos_referencia_idx ON corona.produtos (lower(referencia)) WHERE deleted_at IS NULL AND referencia IS NOT NULL;
CREATE INDEX produtos_categoria_idx ON corona.produtos (categoria_id) WHERE deleted_at IS NULL;
CREATE INDEX produtos_fornecedor_idx ON corona.produtos (fornecedor_id) WHERE deleted_at IS NULL AND fornecedor_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Custos (só do servidor, nunca sincronizada)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.produtos_custos (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    produto_id uuid NOT NULL UNIQUE REFERENCES corona.produtos (id),
    preco_custo numeric(14, 2) NOT NULL CONSTRAINT produtos_custos_nao_negativo CHECK (preco_custo >= 0)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.produtos_custos', editavel => true);
COMMENT ON TABLE corona.produtos_custos IS
    'Só do servidor, NUNCA sincronizada: o preço de custo não sai para o Corona Vendas. 1 para 1 com produtos.';

-- ---------------------------------------------------------------------------------------------
-- Códigos (sincronizável): ADR-0007. Texto livre, sem espaços nas pontas, único sem diferenciar
-- maiúsculas entre os não excluídos; um principal por produto.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.produto_codigos (
    id uuid PRIMARY KEY,
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    codigo text NOT NULL
        CONSTRAINT produto_codigos_codigo_preenchido CHECK (btrim(codigo) <> '' AND codigo = btrim(codigo)),
    tipo text NOT NULL CONSTRAINT produto_codigos_tipo_conhecido CHECK (tipo IN ('LOJA', 'FORNECEDOR', 'EAN13')),
    fornecedor_id uuid REFERENCES corona.fornecedores (id),
    principal boolean NOT NULL DEFAULT false,
    ativo boolean NOT NULL DEFAULT true
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.produto_codigos');
CREATE UNIQUE INDEX produto_codigos_codigo_unico ON corona.produto_codigos (lower(codigo)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX produto_codigos_principal_unico ON corona.produto_codigos (produto_id)
    WHERE principal AND deleted_at IS NULL;
CREATE INDEX produto_codigos_produto_idx ON corona.produto_codigos (produto_id) WHERE deleted_at IS NULL;

-- Código novo da loja (peça sem etiqueta): sequencial do servidor; pode haver buracos.
CREATE SEQUENCE corona.codigo_loja_seq AS bigint MINVALUE 1 START 1;
