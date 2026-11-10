-- V010: cadastros básicos da dona (Fase 1, tarefa 1.2): fornecedores, categorias, praças, semanas,
-- restrições e clientes (ESPECIFICACAO §9).
--
-- Sincronizáveis (chegam ao Corona Vendas, §10): categorias, semanas e clientes.
-- Só do servidor (o vendedor nunca recebe: "nada de fornecedores", §10): fornecedores, praças e
-- restrições. Todas têm exclusão lógica (deleted_at). Unicidade e sem-sobreposição valem só para o
-- que não foi excluído (índices parciais), então um nome liberado pode ser usado de novo.
-- CPF e CNPJ são guardados só com os caracteres do número (sem pontos, traço e barra); a validação
-- do dígito é da API (a dona e o app podem estar em versões diferentes), aqui só o formato.

-- ---------------------------------------------------------------------------------------------
-- Fornecedores (só do servidor)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.fornecedores (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    razao_social text NOT NULL CONSTRAINT fornecedores_razao_social_preenchida CHECK (btrim(razao_social) <> ''),
    cnpj text CONSTRAINT fornecedores_cnpj_formato CHECK (cnpj ~ '^[0-9A-Z]{12}[0-9]{2}$'),
    contato_nome text,
    telefone text,
    email text,
    endereco_logradouro text,
    endereco_numero text,
    endereco_complemento text,
    endereco_bairro text,
    endereco_cidade text,
    endereco_uf text,
    endereco_cep text,
    deleted_at timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.fornecedores', editavel => true);
CREATE UNIQUE INDEX fornecedores_cnpj_unico ON corona.fornecedores (cnpj) WHERE cnpj IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX fornecedores_razao_social_idx ON corona.fornecedores (lower(razao_social)) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Praças (só do servidor)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.pracas (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    nome text NOT NULL CONSTRAINT pracas_nome_preenchido CHECK (btrim(nome) <> ''),
    deleted_at timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.pracas', editavel => true);
CREATE UNIQUE INDEX pracas_nome_unico ON corona.pracas (lower(nome)) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Restrições (só do servidor): bloqueia_remessa impede montar maleta (tarefa da maleta, Fase 3)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.restricoes (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    codigo text NOT NULL CONSTRAINT restricoes_codigo_preenchido CHECK (btrim(codigo) <> ''),
    descricao text NOT NULL CONSTRAINT restricoes_descricao_preenchida CHECK (btrim(descricao) <> ''),
    bloqueia_remessa boolean NOT NULL DEFAULT false,
    deleted_at timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.restricoes', editavel => true);
CREATE UNIQUE INDEX restricoes_codigo_unico ON corona.restricoes (lower(codigo)) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Categorias (sincronizável: o produto leva a categoria)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.categorias (
    id uuid PRIMARY KEY,
    nome text NOT NULL CONSTRAINT categorias_nome_preenchido CHECK (btrim(nome) <> '')
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.categorias');
CREATE UNIQUE INDEX categorias_nome_unico ON corona.categorias (lower(nome)) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Semanas (sincronizável): número único e datas que não se sobrepõem. As datas são dias (date),
-- sem hora: o fim conta como dia da semana (intervalo fechado nas duas pontas).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.semanas (
    id uuid PRIMARY KEY,
    numero integer NOT NULL CONSTRAINT semanas_numero_positivo CHECK (numero >= 1),
    inicio date NOT NULL,
    fim date NOT NULL,
    situacao text NOT NULL DEFAULT 'ABERTA' CONSTRAINT semanas_situacao_valida CHECK (situacao IN ('ABERTA', 'FECHADA')),
    CONSTRAINT semanas_fim_depois_do_inicio CHECK (fim >= inicio)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.semanas');
CREATE UNIQUE INDEX semanas_numero_unico ON corona.semanas (numero) WHERE deleted_at IS NULL;
ALTER TABLE corona.semanas
    ADD CONSTRAINT semanas_sem_sobreposicao
    EXCLUDE USING gist (daterange(inicio, fim, '[]') WITH &&) WHERE (deleted_at IS NULL);

-- ---------------------------------------------------------------------------------------------
-- Clientes (sincronizável). vendedor_responsavel_id ainda sem chave estrangeira: a tabela
-- vendedores nasce na tarefa 1.3, que acrescenta a FK. CPF não é único aqui: o vendedor cadastra
-- offline e o CPF repetido vai para revisão da dona (§10), não para rejeição.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.clientes (
    id uuid PRIMARY KEY,
    nome text NOT NULL CONSTRAINT clientes_nome_preenchido CHECK (btrim(nome) <> ''),
    cpf text CONSTRAINT clientes_cpf_formato CHECK (cpf ~ '^[0-9]{11}$'),
    telefone text,
    whatsapp text,
    endereco_logradouro text,
    endereco_numero text,
    endereco_complemento text,
    endereco_bairro text,
    endereco_cidade text,
    endereco_uf text,
    endereco_cep text,
    observacoes text,
    vendedor_responsavel_id uuid
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.clientes');
CREATE INDEX clientes_nome_idx ON corona.clientes (lower(nome)) WHERE deleted_at IS NULL;
CREATE INDEX clientes_cpf_idx ON corona.clientes (cpf) WHERE cpf IS NOT NULL AND deleted_at IS NULL;
