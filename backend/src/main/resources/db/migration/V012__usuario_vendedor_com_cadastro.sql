-- V012: todo usuário VENDEDOR passa a ter um cadastro de vendedor (Fase 1, tarefa 1.3).
--
-- A V004 deixou usuarios.vendedor_id opcional e sem chave estrangeira, à espera da tabela vendedores
-- (V011). Aqui: (1) usuário VENDEDOR sem cadastro ganha um (nome do usuário, limite 0 e comissão 0,
-- que a dona completa na tela); (2) vendedor_id passa a apontar para vendedores; (3) perfil VENDEDOR
-- exige vendedor_id.

-- Usuário VENDEDOR sem vendedor_id: cria o cadastro e liga, num comando só (a CTE roda uma vez).
WITH novos AS (
    SELECT u.id AS usuario_id, uuidv7() AS vendedor_id, u.nome
      FROM corona.usuarios u
     WHERE u.perfil = 'VENDEDOR' AND u.vendedor_id IS NULL
),
criados AS (
    INSERT INTO corona.vendedores (id, nome)
        SELECT vendedor_id, nome FROM novos
        RETURNING id
)
UPDATE corona.usuarios u SET vendedor_id = n.vendedor_id
  FROM novos n WHERE n.usuario_id = u.id;

-- Usuário VENDEDOR que guarda um vendedor_id sem cadastro (a coluna não tinha chave estrangeira):
-- cria o cadastro com esse mesmo id, para o vínculo ser mantido.
INSERT INTO corona.vendedores (id, nome)
    SELECT DISTINCT ON (u.vendedor_id) u.vendedor_id, u.nome
      FROM corona.usuarios u
     WHERE u.vendedor_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM corona.vendedores v WHERE v.id = u.vendedor_id)
     ORDER BY u.vendedor_id, u.created_at;

ALTER TABLE corona.usuarios
    ADD CONSTRAINT usuarios_vendedor_fk FOREIGN KEY (vendedor_id) REFERENCES corona.vendedores (id),
    ADD CONSTRAINT usuarios_vendedor_tem_cadastro CHECK (perfil <> 'VENDEDOR' OR vendedor_id IS NOT NULL);

COMMENT ON COLUMN corona.usuarios.vendedor_id IS
    'Cadastro do vendedor ligado ao usuário. Obrigatório no perfil VENDEDOR e proibido no ADMIN (V004).';
