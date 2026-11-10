-- V011: cadastro completo do vendedor (Fase 1, tarefa 1.3; ESPECIFICACAO §9).
--
-- Tudo aqui é SÓ DO SERVIDOR: o vendedor nunca recebe dados de outros vendedores nem o limite de
-- consignação e a comissão (§10, §11). Quando o Corona Vendas precisar de algo do próprio vendedor
-- (a Fase 5 decide), uma migração nova cria a tabela sincronizável. Nada é apagado (gatilho da V002):
-- o que pode sair da lista tem deleted_at, e cada aba de uma linha só é regravada no lugar.
--
-- Documentos: o banco guarda só os dados do arquivo (nome, tipo, tamanho, hash e a chave do
-- armazenamento). O arquivo em si fica fora do PostgreSQL (CLAUDE.md).

CREATE SEQUENCE corona.vendedores_codigo_seq AS integer MINVALUE 1 START 1;

CREATE TABLE corona.vendedores (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    -- Código que a dona vê e usa na conversa ("vendedor 12"). Sequencial; pode haver buracos.
    codigo integer NOT NULL DEFAULT nextval('corona.vendedores_codigo_seq')
        CONSTRAINT vendedores_codigo_unico UNIQUE
        CONSTRAINT vendedores_codigo_positivo CHECK (codigo >= 1),
    nome text NOT NULL CONSTRAINT vendedores_nome_preenchido CHECK (btrim(nome) <> ''),
    data_cadastro date NOT NULL DEFAULT ((now() AT TIME ZONE 'UTC')::date),
    data_nascimento date,
    sexo text CONSTRAINT vendedores_sexo_valido CHECK (sexo IN ('FEMININO', 'MASCULINO', 'OUTRO')),
    estrangeiro boolean NOT NULL DEFAULT false,
    rg_ie text,
    rg_uf text,
    -- Só os caracteres do número: CPF (11 dígitos) ou CNPJ (14, com letras nas 12 primeiras). Opcional,
    -- porque vendedor estrangeiro pode não ter. A validação do dígito é da API.
    cpf_cnpj text CONSTRAINT vendedores_cpf_cnpj_formato
        CHECK (cpf_cnpj ~ '^[0-9]{11}$' OR cpf_cnpj ~ '^[0-9A-Z]{12}[0-9]{2}$'),
    naturalidade text,
    escolaridade text,
    telefone1 text,
    telefone2 text,
    whatsapp text,
    email text,
    facebook text,
    outras_midias text,
    limite_consignacao numeric(14,2) NOT NULL DEFAULT 0
        CONSTRAINT vendedores_limite_nao_negativo CHECK (limite_consignacao >= 0),
    restricao_id uuid CONSTRAINT vendedores_restricao_fk REFERENCES corona.restricoes (id),
    comissao_percentual numeric(5,2) NOT NULL DEFAULT 0
        CONSTRAINT vendedores_comissao_entre_0_e_100 CHECK (comissao_percentual BETWEEN 0 AND 100),
    praca_id uuid CONSTRAINT vendedores_praca_fk REFERENCES corona.pracas (id),
    ativo boolean NOT NULL DEFAULT true,
    -- Quem cadastrou (a dona). Vazio só nos vendedores que a V012 criou a partir de usuários antigos.
    cadastrado_por uuid CONSTRAINT vendedores_cadastrado_por_fk REFERENCES corona.usuarios (id),
    deleted_at timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores', editavel => true);
CREATE UNIQUE INDEX vendedores_cpf_cnpj_unico ON corona.vendedores (cpf_cnpj)
    WHERE cpf_cnpj IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX vendedores_nome_idx ON corona.vendedores (lower(nome)) WHERE deleted_at IS NULL;
CREATE INDEX vendedores_praca_idx ON corona.vendedores (praca_id) WHERE deleted_at IS NULL;
CREATE INDEX vendedores_restricao_idx ON corona.vendedores (restricao_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Abas de uma linha por vendedor (abas da referência). A aba nasce na primeira gravação e depois
-- é regravada no lugar, com a versão que a tela leu.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.vendedores_enderecos (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    vendedor_id uuid NOT NULL CONSTRAINT vendedores_enderecos_vendedor_unico UNIQUE
        REFERENCES corona.vendedores (id),
    logradouro text,
    numero text,
    complemento text,
    bairro text,
    cep text,
    cidade text,
    uf text,
    local_referencia text,
    -- Como é a moradia (própria, alugada...) e o valor dela (aluguel ou parcela).
    residencia text,
    valor_residencia numeric(14,2) CONSTRAINT vendedores_enderecos_valor_nao_negativo CHECK (valor_residencia >= 0)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores_enderecos', editavel => true);

CREATE TABLE corona.vendedores_contatos (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    vendedor_id uuid NOT NULL CONSTRAINT vendedores_contatos_vendedor_unico UNIQUE
        REFERENCES corona.vendedores (id),
    nome_pai text,
    nome_mae text,
    contato_nome text,
    contato_parentesco text,
    contato_telefone text
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores_contatos', editavel => true);

CREATE TABLE corona.vendedores_trabalhos (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    vendedor_id uuid NOT NULL CONSTRAINT vendedores_trabalhos_vendedor_unico UNIQUE
        REFERENCES corona.vendedores (id),
    empresa text,
    funcao text,
    telefone text,
    endereco text,
    data_admissao date,
    renda numeric(14,2) CONSTRAINT vendedores_trabalhos_renda_nao_negativa CHECK (renda >= 0)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores_trabalhos', editavel => true);

CREATE TABLE corona.vendedores_conjuges (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    vendedor_id uuid NOT NULL CONSTRAINT vendedores_conjuges_vendedor_unico UNIQUE
        REFERENCES corona.vendedores (id),
    nome text,
    cpf text CONSTRAINT vendedores_conjuges_cpf_formato CHECK (cpf ~ '^[0-9]{11}$'),
    rg text,
    data_nascimento date,
    telefone text,
    profissao text,
    empresa text,
    renda numeric(14,2) CONSTRAINT vendedores_conjuges_renda_nao_negativa CHECK (renda >= 0)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores_conjuges', editavel => true);

CREATE TABLE corona.vendedores_mei (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    vendedor_id uuid NOT NULL CONSTRAINT vendedores_mei_vendedor_unico UNIQUE
        REFERENCES corona.vendedores (id),
    cnpj text CONSTRAINT vendedores_mei_cnpj_formato CHECK (cnpj ~ '^[0-9A-Z]{12}[0-9]{2}$'),
    razao_social text,
    nome_fantasia text,
    data_abertura date,
    atividade text
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores_mei', editavel => true);

CREATE TABLE corona.vendedores_observacoes (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    vendedor_id uuid NOT NULL CONSTRAINT vendedores_observacoes_vendedor_unico UNIQUE
        REFERENCES corona.vendedores (id),
    texto text
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores_observacoes', editavel => true);

-- ---------------------------------------------------------------------------------------------
-- Várias linhas por vendedor, com exclusão lógica.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.vendedores_referencias (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    vendedor_id uuid NOT NULL CONSTRAINT vendedores_referencias_vendedor_fk REFERENCES corona.vendedores (id),
    nome text NOT NULL CONSTRAINT vendedores_referencias_nome_preenchido CHECK (btrim(nome) <> ''),
    parentesco text,
    telefone text,
    observacao text,
    deleted_at timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores_referencias', editavel => true);
CREATE INDEX vendedores_referencias_vendedor_idx ON corona.vendedores_referencias (vendedor_id) WHERE deleted_at IS NULL;

CREATE TABLE corona.vendedores_documentos (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    vendedor_id uuid NOT NULL CONSTRAINT vendedores_documentos_vendedor_fk REFERENCES corona.vendedores (id),
    -- O que o documento é (RG, comprovante de endereço...), como a dona escreveu.
    descricao text NOT NULL CONSTRAINT vendedores_documentos_descricao_preenchida CHECK (btrim(descricao) <> ''),
    nome_original text NOT NULL,
    tipo_conteudo text NOT NULL
        CONSTRAINT vendedores_documentos_tipo_valido
        CHECK (tipo_conteudo IN ('application/pdf', 'image/jpeg', 'image/png', 'image/webp')),
    tamanho_bytes bigint NOT NULL CONSTRAINT vendedores_documentos_tamanho_positivo CHECK (tamanho_bytes > 0),
    hash_sha256 text NOT NULL CONSTRAINT vendedores_documentos_hash_formato CHECK (hash_sha256 ~ '^[0-9a-f]{64}$'),
    -- Chave do arquivo no armazenamento (nunca um caminho digitado por alguém).
    chave_arquivo text NOT NULL CONSTRAINT vendedores_documentos_chave_unica UNIQUE,
    enviado_por uuid CONSTRAINT vendedores_documentos_enviado_por_fk REFERENCES corona.usuarios (id),
    deleted_at timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.vendedores_documentos', editavel => true);
CREATE INDEX vendedores_documentos_vendedor_idx ON corona.vendedores_documentos (vendedor_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Cliente passa a ter vendedor responsável de verdade (a V010 deixou sem chave estrangeira). Nenhum
-- vendedor existia antes desta migração, então quem tinha um id guardado perde só esse vínculo
-- (era um id sem cadastro); o cliente continua.
-- ---------------------------------------------------------------------------------------------
UPDATE corona.clientes SET vendedor_responsavel_id = NULL WHERE vendedor_responsavel_id IS NOT NULL;
ALTER TABLE corona.clientes
    ADD CONSTRAINT clientes_vendedor_responsavel_fk FOREIGN KEY (vendedor_responsavel_id) REFERENCES corona.vendedores (id);
