-- V027: uma foto só por peça (decisão do responsável de 11/11/2026; ESPECIFICACAO §20).
--
-- A foto serve só para reconhecer a peça. Enviar uma foto nova substitui a anterior (exclusão lógica da antiga; o
-- arquivo sai do disco depois, por limpeza). As colunas principal e ordem da V015 ficam (migração publicada não se
-- edita), sem uso: a foto ativa é sempre a principal.
--
-- Peças que já tinham mais de uma foto ficam com uma: a principal (ou, na falta, a primeira da ordem). As outras
-- ganham exclusão lógica agora, e a limpeza diária tira os arquivos delas do disco depois de 7 dias.

WITH ordenadas AS (
    SELECT id, row_number() OVER (PARTITION BY produto_id ORDER BY principal DESC, ordem, created_at, id) AS posicao
      FROM corona.produtos_fotos WHERE deleted_at IS NULL
)
UPDATE corona.produtos_fotos f SET deleted_at = now(), principal = false
  FROM ordenadas o WHERE f.id = o.id AND o.posicao > 1;

UPDATE corona.produtos_fotos SET principal = true, ordem = 0
 WHERE deleted_at IS NULL AND (NOT principal OR ordem <> 0);

UPDATE corona.produtos p SET foto_principal_hash = f.arquivo_hash
  FROM corona.produtos_fotos f
 WHERE f.produto_id = p.id AND f.deleted_at IS NULL AND p.foto_principal_hash IS DISTINCT FROM f.arquivo_hash;

-- O servidor garante uma só foto ativa por produto.
CREATE UNIQUE INDEX produtos_fotos_uma_ativa ON corona.produtos_fotos (produto_id) WHERE deleted_at IS NULL;

-- Só do servidor: quando a limpeza tirou os arquivos desta foto (já excluída) do disco.
ALTER TABLE corona.produtos_fotos ADD COLUMN arquivos_removidos_em timestamptz;
COMMENT ON COLUMN corona.produtos_fotos.arquivos_removidos_em IS
    'Quando a limpeza diária apagou do disco os arquivos desta foto já excluída (V027). Nunca vai no sync.';
