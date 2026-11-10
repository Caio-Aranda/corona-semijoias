-- V015: fotos de produto (Fase 1, tarefa 1.6; ESPECIFICACAO §20).
--
-- A foto é sempre um ARQUIVO (nunca binário no PostgreSQL). Cada foto tem três tamanhos (miniatura,
-- média e grande), todos em JPEG, guardados pelo ArmazenamentoDeArquivos; aqui ficam só o hash do
-- conteúdo e as chaves dos três arquivos. Só o que o aplicativo do vendedor precisa para baixar a
-- imagem (produto, hash, principal e ordem) vai no sync; as chaves e as medidas ficam só no servidor.
--
-- A foto principal também fica copiada em produtos.foto_principal_hash (campo reservado pela V014), que
-- o vendedor já recebe junto com o produto: se o hash mudar, ele baixa a imagem nova.

CREATE TABLE corona.produtos_fotos (
    id uuid PRIMARY KEY,
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    -- SHA-256 (64 caracteres hexadecimais) do tamanho grande já recodificado: identifica o conteúdo.
    arquivo_hash text NOT NULL CONSTRAINT produtos_fotos_hash_valido CHECK (arquivo_hash ~ '^[0-9a-f]{64}$'),
    principal boolean NOT NULL DEFAULT false,
    ordem integer NOT NULL DEFAULT 0 CONSTRAINT produtos_fotos_ordem_nao_negativa CHECK (ordem >= 0),
    largura integer NOT NULL CONSTRAINT produtos_fotos_largura_positiva CHECK (largura > 0),
    altura integer NOT NULL CONSTRAINT produtos_fotos_altura_positiva CHECK (altura > 0),
    -- Soma dos três arquivos.
    tamanho_bytes integer NOT NULL CONSTRAINT produtos_fotos_tamanho_positivo CHECK (tamanho_bytes > 0),
    -- Só do servidor: onde estão os três arquivos.
    chave_miniatura text NOT NULL,
    chave_media text NOT NULL,
    chave_grande text NOT NULL
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.produtos_fotos');
COMMENT ON TABLE corona.produtos_fotos IS
    'Fotos do produto (até 5). Arquivos fora do banco; aqui o hash, a ordem e as chaves dos três tamanhos.';

-- Uma foto principal por produto, e a mesma foto não entra duas vezes no mesmo produto.
CREATE UNIQUE INDEX produtos_fotos_principal_unica ON corona.produtos_fotos (produto_id)
    WHERE principal AND deleted_at IS NULL;
CREATE UNIQUE INDEX produtos_fotos_hash_por_produto ON corona.produtos_fotos (produto_id, arquivo_hash)
    WHERE deleted_at IS NULL;
CREATE INDEX produtos_fotos_produto_idx ON corona.produtos_fotos (produto_id, ordem) WHERE deleted_at IS NULL;
CREATE INDEX produtos_fotos_hash_idx ON corona.produtos_fotos (arquivo_hash);

COMMENT ON COLUMN corona.produtos.foto_principal_hash IS
    'Hash da foto principal (cópia de produtos_fotos, mantida pela API). A foto é um arquivo, nunca binário no banco.';
