-- Usuários e perfis (tarefa 9; ADR-0004, itens 6 e 9). As duas tabelas são só do servidor (ADR-0002).

-- ---------------------------------------------------------------------------------------------
-- Perfis: o perfil é um dado, não um tipo fechado. Perfil novo = uma linha numa migração nova.
-- Só inserção (recusa UPDATE); a aplicação só lê.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.perfis (
    id uuid PRIMARY KEY,
    codigo text NOT NULL
        CONSTRAINT perfis_codigo_unico UNIQUE
        CONSTRAINT perfis_codigo_formato CHECK (codigo ~ '^[A-Z][A-Z_]*$')
);
COMMENT ON TABLE corona.perfis IS
    'Só do servidor: perfis de acesso (ADMIN, VENDEDOR). Perfil novo = uma linha numa migração nova. ADR-0004, item 9.';
SELECT corona.aplicar_convencoes_do_servidor('corona.perfis', editavel => false);

INSERT INTO corona.perfis (id, codigo) VALUES
    (uuidv7(), 'ADMIN'),
    (uuidv7(), 'VENDEDOR');

-- ---------------------------------------------------------------------------------------------
-- Usuários. Ninguém é apagado: quem sai fica com ativo = false.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.usuarios (
    id uuid PRIMARY KEY,
    nome text NOT NULL
        CONSTRAINT usuarios_nome_preenchido CHECK (btrim(nome) <> ''),
    login text NOT NULL
        CONSTRAINT usuarios_login_unico UNIQUE
        CONSTRAINT usuarios_login_normalizado CHECK (login <> '' AND login = lower(btrim(login))),
    senha_hash text NOT NULL
        CONSTRAINT usuarios_senha_bcrypt CHECK (senha_hash ~ '^\$2[aby]\$[0-9]{2}\$'),
    perfil text NOT NULL
        CONSTRAINT usuarios_perfil_existe REFERENCES corona.perfis (codigo),
    ativo boolean NOT NULL DEFAULT true,
    vendedor_id uuid,
    CONSTRAINT usuarios_admin_sem_vendedor CHECK (perfil <> 'ADMIN' OR vendedor_id IS NULL)
);
COMMENT ON TABLE corona.usuarios IS
    'Só do servidor: quem entra nos aplicativos. Login guardado normalizado (sem espaços nas pontas, minúsculas). Nunca apagado: desativar = ativo false.';
COMMENT ON COLUMN corona.usuarios.senha_hash IS
    'BCrypt (ADR-0004, item 6). A senha em si nunca é guardada.';
COMMENT ON COLUMN corona.usuarios.vendedor_id IS
    'Vendedor ligado ao usuário (só perfil VENDEDOR). Ainda SEM chave estrangeira e opcional: a Fase 1 cria a tabela vendedores e, numa migração nova, a chave estrangeira e o CHECK "VENDEDOR tem vendedor_id".';
SELECT corona.aplicar_convencoes_do_servidor('corona.usuarios', editavel => true);

-- ---------------------------------------------------------------------------------------------
-- Privilégios: a aplicação só lê os perfis (mesmo padrão da V002 para sync_estado).
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    papel text;
BEGIN
    FOR papel IN
        SELECT DISTINCT a.grantee::regrole::text
          FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
         WHERE c.oid = 'corona.perfis'::regclass AND a.grantee <> c.relowner AND a.grantee <> 0
    LOOP
        EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON corona.perfis FROM %s', papel);
    END LOOP;
END;
$$;
REVOKE ALL ON corona.perfis FROM PUBLIC;
REVOKE ALL ON corona.usuarios FROM PUBLIC;
