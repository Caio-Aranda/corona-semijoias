-- V029: clientes são da LOJA e o CPF é único entre os clientes não excluídos (decisão do responsável de 11/11/2026;
-- ADR-0011, item 13; ADR-0013, item 8). Antes (V010) o banco aceitava CPF repetido, para a dona revisar na sincronização.
--
-- Cadastros que já existem com o mesmo CPF: fica o mais antigo; nos outros o CPF sai (não se perde nada: o número vai
-- para as observações, com o id do cadastro que ficou), e a dona junta os cadastros na revisão. Só depois vem o índice.

WITH repetidos AS (
    SELECT id, cpf,
           first_value(id) OVER (PARTITION BY cpf ORDER BY created_at, id) AS id_que_fica,
           row_number() OVER (PARTITION BY cpf ORDER BY created_at, id) AS posicao
      FROM corona.clientes
     WHERE cpf IS NOT NULL AND deleted_at IS NULL
)
UPDATE corona.clientes c
   SET cpf = NULL,
       observacoes = btrim(coalesce(c.observacoes, '') || E'\n[CPF ' || r.cpf || ' repetido, retirado na migração V029; o cadastro que ficou com ele é '
                           || r.id_que_fica || '. Junte os dois cadastros.]')
  FROM repetidos r
 WHERE c.id = r.id AND r.posicao > 1;

CREATE UNIQUE INDEX clientes_cpf_unico ON corona.clientes (cpf) WHERE cpf IS NOT NULL AND deleted_at IS NULL;

COMMENT ON INDEX corona.clientes_cpf_unico IS
    'CPF único entre os clientes não excluídos (V029). O servidor confere antes e responde 409 com o cliente existente; o índice é a segunda trava.';
