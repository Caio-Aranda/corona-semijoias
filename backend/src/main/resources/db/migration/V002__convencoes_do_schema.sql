-- V002: convenções do schema (ADR-0002, itens 3 a 5). Roda com o usuário das migrações,
-- dono do schema "corona". Nenhuma tabela de negócio: só as peças que toda tabela usa.
--
-- Como criar uma tabela nova (detalhes em backend/README.md, "Como criar uma tabela"):
--   CREATE TABLE corona.clientes (id uuid PRIMARY KEY, nome text NOT NULL, ...);
--   SELECT corona.aplicar_convencoes_sincronizavel('corona.clientes');
--   SELECT corona.aplicar_convencoes_do_servidor('corona.usuarios', editavel => true);
-- e registrar a tabela em RegistroDeTabelas (o teste de convenções reprova o que faltar).

-- Funções novas não ficam abertas a todos por padrão (vale para as próximas migrações também).
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- Sequência global do sync_seq: uma só para todas as tabelas sincronizáveis, então o cursor do
-- pull é um só. Ela numera as gravações dentro de uma transação; quem garante o pull seguro é o
-- sync_xid (ADR-0002, item 5).
-- ---------------------------------------------------------------------------------------------
CREATE SEQUENCE corona.sync_seq_global AS bigint;
COMMENT ON SEQUENCE corona.sync_seq_global IS
    'sync_seq de todas as tabelas sincronizáveis (ADR-0002). Preenchida só pelo gatilho corona.controle_sincronizavel.';

-- ---------------------------------------------------------------------------------------------
-- Gatilho de controle das tabelas sincronizáveis. Único que escreve os campos de controle; o
-- que o cliente mandar neles é ignorado (sobrescrito).
--
-- origem_dispositivo vem da sessão autenticada, nunca do corpo: a API define, dentro da
-- transação, set_config('corona.origem_dispositivo', '<uuid do aparelho>', true). O "true" faz
-- o valor valer só até o fim da transação, então uma conexão do pool nunca leva o aparelho de
-- uma requisição para outra. Sem valor, fica NULL (o que nasce no Corona Gestão). No update a
-- origem é preservada.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION corona.controle_sincronizavel() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.sync_seq := nextval('corona.sync_seq_global'::regclass);
    NEW.sync_xid := pg_current_xact_id();
    NEW.updated_at := now();
    IF TG_OP = 'INSERT' THEN
        NEW.created_at := now();
        NEW.version := 1;
        NEW.origem_dispositivo := NULLIF(current_setting('corona.origem_dispositivo', true), '')::uuid;
    ELSE
        NEW.id := OLD.id;
        NEW.created_at := OLD.created_at;
        NEW.version := OLD.version + 1;
        NEW.origem_dispositivo := OLD.origem_dispositivo;
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION corona.controle_sincronizavel() IS
    'BEFORE INSERT OR UPDATE das tabelas sincronizáveis: sync_seq, sync_xid, version, created_at, updated_at, id e origem_dispositivo (ADR-0002).';

-- Gatilho de controle das tabelas só do servidor que podem ser editadas.
CREATE FUNCTION corona.controle_do_servidor() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    IF TG_OP = 'INSERT' THEN
        NEW.created_at := now();
        NEW.version := 1;
    ELSE
        NEW.id := OLD.id;
        NEW.created_at := OLD.created_at;
        NEW.version := OLD.version + 1;
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION corona.controle_do_servidor() IS
    'BEFORE INSERT OR UPDATE das tabelas só do servidor editáveis: version, created_at, updated_at e id (ADR-0002).';

-- Nunca DELETE (nem TRUNCATE): exclusão é lógica e correção é um registro novo. As recusas são
-- gatilhos por comando (FOR EACH STATEMENT): disparam antes de qualquer linha e recusam até o
-- comando que não acharia linha nenhuma.
CREATE FUNCTION corona.recusar_exclusao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Não é permitido apagar registros de % (comando %): use a exclusão lógica (deleted_at) ou um estorno.',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;
COMMENT ON FUNCTION corona.recusar_exclusao() IS
    'BEFORE DELETE e BEFORE TRUNCATE (por comando) de todas as tabelas: recusa (ADR-0002).';

-- Tabelas imutáveis (vendas, itens, pagamentos, movimentos; históricos do servidor). Também por
-- comando; a exclusão lógica é um UPDATE, então também não existe nessas tabelas.
CREATE FUNCTION corona.recusar_alteracao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Registros de % não podem ser alterados: a correção é feita com um novo registro (estorno).',
        TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;
COMMENT ON FUNCTION corona.recusar_alteracao() IS
    'BEFORE UPDATE (por comando) das tabelas imutáveis: recusa (ADR-0002).';

-- ---------------------------------------------------------------------------------------------
-- Conferências comuns às duas funções de aplicação.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION corona.conferir_tabela_nova(tabela regclass, colunas_proibidas text[]) RETURNS text
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    nome text;
    esquema text;
    tipo char;
    colunas_da_chave text[];
    tipo_do_id text;
    ja_declaradas text[];
BEGIN
    SELECT c.relname, n.nspname, c.relkind INTO nome, esquema, tipo
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.oid = tabela;
    IF esquema <> 'corona' OR tipo <> 'r' THEN
        RAISE EXCEPTION 'As convenções valem para tabelas comuns do schema corona; % não é uma delas.', tabela;
    END IF;

    SELECT array_agg(a.attname::text ORDER BY a.attname)
      INTO colunas_da_chave
      FROM pg_index i
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
     WHERE i.indrelid = tabela AND i.indisprimary;
    SELECT format_type(a.atttypid, a.atttypmod) INTO tipo_do_id
      FROM pg_attribute a
     WHERE a.attrelid = tabela AND a.attname = 'id' AND NOT a.attisdropped;
    IF colunas_da_chave IS DISTINCT FROM ARRAY['id'] OR tipo_do_id IS DISTINCT FROM 'uuid' THEN
        RAISE EXCEPTION 'A tabela % precisa ter a coluna "id uuid PRIMARY KEY" como única chave primária.', tabela;
    END IF;

    SELECT array_agg(a.attname::text ORDER BY a.attname)
      INTO ja_declaradas
      FROM pg_attribute a
     WHERE a.attrelid = tabela AND a.attnum > 0 AND NOT a.attisdropped
       AND a.attname::text = ANY (colunas_proibidas);
    IF ja_declaradas IS NOT NULL THEN
        RAISE EXCEPTION 'Não declare % na tabela %: as colunas de controle são criadas pela função de convenções.',
            array_to_string(ja_declaradas, ', '), tabela;
    END IF;
    RETURN nome;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- Aplica as convenções a uma tabela sincronizável nova (vazia, com "id uuid PRIMARY KEY" e sem as
-- colunas de controle). Cria: as colunas de controle, o CHECK de UUID v4 ou v7, o gatilho de
-- controle, a recusa de DELETE e de TRUNCATE, a recusa de UPDATE (se imutável) e o índice
-- (sync_xid, sync_seq) usado pelo pull.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION corona.aplicar_convencoes_sincronizavel(tabela regclass, imutavel boolean DEFAULT false) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    nome text;
    tem_linhas boolean;
BEGIN
    nome := corona.conferir_tabela_nova(tabela, ARRAY['created_at', 'updated_at', 'version', 'deleted_at',
        'origem_dispositivo', 'sync_seq', 'sync_xid']);
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s)', tabela) INTO tem_linhas;
    IF tem_linhas THEN
        RAISE EXCEPTION 'Aplique as convenções logo depois do CREATE TABLE, com a tabela % vazia.', tabela;
    END IF;

    EXECUTE format(
        'ALTER TABLE %s'
        '  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),'
        '  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),'
        '  ADD COLUMN version integer NOT NULL DEFAULT 1 CONSTRAINT %I CHECK (version >= 1),'
        '  ADD COLUMN deleted_at timestamptz,'
        '  ADD COLUMN origem_dispositivo uuid,'
        '  ADD COLUMN sync_seq bigint NOT NULL,'
        '  ADD COLUMN sync_xid xid8 NOT NULL,'
        '  ADD CONSTRAINT %I CHECK (coalesce(uuid_extract_version(id), 0) IN (4, 7))',
        tabela, nome || '_version_positiva', nome || '_id_v4_ou_v7');

    -- As recusas são por comando e disparam antes do controle, que é por linha.
    EXECUTE format('CREATE TRIGGER corona_sync_controle BEFORE INSERT OR UPDATE ON %s '
        'FOR EACH ROW EXECUTE FUNCTION corona.controle_sincronizavel()', tabela);
    EXECUTE format('CREATE TRIGGER corona_recusa_delete BEFORE DELETE ON %s '
        'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_exclusao()', tabela);
    EXECUTE format('CREATE TRIGGER corona_recusa_truncate BEFORE TRUNCATE ON %s '
        'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_exclusao()', tabela);
    IF imutavel THEN
        EXECUTE format('CREATE TRIGGER corona_recusa_update BEFORE UPDATE ON %s '
            'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_alteracao()', tabela);
    END IF;

    EXECUTE format('CREATE INDEX %I ON %s (sync_xid, sync_seq)', nome || '_sync_idx', tabela);
END;
$$;
COMMENT ON FUNCTION corona.aplicar_convencoes_sincronizavel(regclass, boolean) IS
    'Monta uma tabela sincronizável nova: colunas de controle, CHECK do id, gatilhos e índice (sync_xid, sync_seq). ADR-0002.';

-- ---------------------------------------------------------------------------------------------
-- Aplica as convenções a uma tabela só do servidor nova. Sempre: created_at, CHECK de UUID v4 ou
-- v7 e recusa de DELETE e de TRUNCATE. Editável: updated_at, version e o gatilho de controle.
-- Histórico (editavel => false): só inserção, com a recusa de UPDATE. A coluna deleted_at, quando
-- a exclusão fizer sentido, é declarada por quem cria a tabela (ADR-0002, item 3).
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION corona.aplicar_convencoes_do_servidor(tabela regclass, editavel boolean) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    nome text;
BEGIN
    IF editavel IS NULL THEN
        RAISE EXCEPTION 'Informe editavel => true ou editavel => false para a tabela %.', tabela;
    END IF;
    nome := corona.conferir_tabela_nova(tabela, ARRAY['created_at', 'updated_at', 'version',
        'origem_dispositivo', 'sync_seq', 'sync_xid']);

    EXECUTE format(
        'ALTER TABLE %s'
        '  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),'
        '  ADD CONSTRAINT %I CHECK (coalesce(uuid_extract_version(id), 0) IN (4, 7))',
        tabela, nome || '_id_v4_ou_v7');
    EXECUTE format('CREATE TRIGGER corona_recusa_delete BEFORE DELETE ON %s '
        'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_exclusao()', tabela);
    EXECUTE format('CREATE TRIGGER corona_recusa_truncate BEFORE TRUNCATE ON %s '
        'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_exclusao()', tabela);

    IF editavel THEN
        EXECUTE format(
            'ALTER TABLE %s'
            '  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),'
            '  ADD COLUMN version integer NOT NULL DEFAULT 1 CONSTRAINT %I CHECK (version >= 1)',
            tabela, nome || '_version_positiva');
        EXECUTE format('CREATE TRIGGER corona_servidor_controle BEFORE INSERT OR UPDATE ON %s '
            'FOR EACH ROW EXECUTE FUNCTION corona.controle_do_servidor()', tabela);
    ELSE
        EXECUTE format('CREATE TRIGGER corona_recusa_update BEFORE UPDATE ON %s '
            'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_alteracao()', tabela);
    END IF;
END;
$$;
COMMENT ON FUNCTION corona.aplicar_convencoes_do_servidor(regclass, boolean) IS
    'Monta uma tabela só do servidor nova: created_at, CHECK do id, recusa de DELETE e TRUNCATE; editável ganha updated_at, version e gatilho; histórico recusa UPDATE. ADR-0002.';

-- ---------------------------------------------------------------------------------------------
-- Época do cursor do pull (ADR-0002, item 5): uma linha só, guardada pela restrição "unica".
-- Sobe 1 a cada restauração de backup (corona.preparar_sync_apos_restauracao, V003).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.sync_estado (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    unica boolean NOT NULL DEFAULT true
        CONSTRAINT sync_estado_uma_linha_so CHECK (unica)
        CONSTRAINT sync_estado_unica UNIQUE,
    epoca bigint NOT NULL DEFAULT 1 CONSTRAINT sync_estado_epoca_positiva CHECK (epoca >= 1)
);
COMMENT ON TABLE corona.sync_estado IS
    'Só do servidor: época atual do cursor do pull (época:xid:seq). Uma linha só. A aplicação só lê.';
SELECT corona.aplicar_convencoes_do_servidor('corona.sync_estado', editavel => true);
INSERT INTO corona.sync_estado DEFAULT VALUES;

-- ---------------------------------------------------------------------------------------------
-- Privilégios. O 01-papeis.sh dá à aplicação SELECT, INSERT e UPDATE em toda tabela nova; aqui
-- tiramos o que ela não deve ter. O nome do usuário da aplicação muda por ambiente, então ele
-- é achado na lista de acesso da tabela (todo papel que não é o dono).
--   * sync_estado: a aplicação só lê (a época só muda pela restauração).
--   * flyway_schema_history: a aplicação não tem acesso nenhum (gravar ali permitiria forjar uma
--     migração "já aplicada").
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    papel text;
BEGIN
    FOR papel IN
        SELECT DISTINCT a.grantee::regrole::text
          FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
         WHERE c.oid = 'corona.sync_estado'::regclass AND a.grantee <> c.relowner AND a.grantee <> 0
    LOOP
        EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON corona.sync_estado FROM %s', papel);
    END LOOP;

    FOR papel IN
        SELECT DISTINCT a.grantee::regrole::text
          FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
         WHERE c.oid = 'corona.flyway_schema_history'::regclass AND a.grantee <> c.relowner AND a.grantee <> 0
    LOOP
        EXECUTE format('REVOKE ALL ON corona.flyway_schema_history FROM %s', papel);
    END LOOP;
END;
$$;
REVOKE ALL ON corona.sync_estado FROM PUBLIC;
REVOKE ALL ON corona.flyway_schema_history FROM PUBLIC;

-- Funções desta migração: ninguém além do dono executa diretamente. Os gatilhos continuam
-- disparando para a aplicação (o privilégio só é conferido ao criar o gatilho).
REVOKE EXECUTE ON FUNCTION corona.controle_sincronizavel() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION corona.controle_do_servidor() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION corona.recusar_exclusao() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION corona.recusar_alteracao() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION corona.conferir_tabela_nova(regclass, text[]) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION corona.aplicar_convencoes_sincronizavel(regclass, boolean) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION corona.aplicar_convencoes_do_servidor(regclass, boolean) FROM PUBLIC;
