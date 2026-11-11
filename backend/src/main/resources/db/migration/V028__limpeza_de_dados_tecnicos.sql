-- V028: exceção à regra "nunca DELETE" só para dados TÉCNICOS que não são dados de negócio (decisão do responsável de
-- 11/11/2026, para o banco não crescer à toa na VPS; ADR-0002, linha datada).
--
-- Sessões encerradas (e os tokens de renovação delas) e tentativas de login são apagadas de verdade depois de 90 dias
-- pela limpeza agendada do servidor (LimpezaDeDadosTecnicos). Tudo o mais continua com exclusão lógica ou imutável.
--
-- A recusa de DELETE (V002) passa a deixar passar o DELETE destas três tabelas, e só delas, quando a transação avisa
-- (corona.limpeza_tecnica = 'sim', valor local da transação). TRUNCATE continua recusado em todas as tabelas.

CREATE OR REPLACE FUNCTION corona.recusar_exclusao() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND TG_TABLE_NAME IN ('sessoes', 'tokens_de_renovacao', 'tentativas_login')
       AND current_setting('corona.limpeza_tecnica', true) = 'sim' THEN
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'Não é permitido apagar registros de % (comando %): use a exclusão lógica (deleted_at) ou um estorno.',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;
COMMENT ON FUNCTION corona.recusar_exclusao() IS
    'BEFORE DELETE e BEFORE TRUNCATE (por comando) de todas as tabelas: recusa (ADR-0002). Exceção da V028: DELETE de sessoes, tokens_de_renovacao e tentativas_login na limpeza técnica de 90 dias.';

-- Privilégio: o papel da aplicação não tinha DELETE (só SELECT, INSERT e UPDATE). Ganha DELETE só nestas três tabelas
-- (os papéis que já escrevem nelas, sem o dono), do mesmo modo que a V004 acha o papel da aplicação.
DO $$
DECLARE
    papel text;
    tabela text;
BEGIN
    FOREACH tabela IN ARRAY ARRAY['corona.sessoes', 'corona.tokens_de_renovacao', 'corona.tentativas_login'] LOOP
        FOR papel IN
            SELECT DISTINCT a.grantee::regrole::text
              FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
             WHERE c.oid = tabela::regclass AND a.grantee <> c.relowner AND a.grantee <> 0 AND a.privilege_type = 'INSERT'
        LOOP
            EXECUTE format('GRANT DELETE ON %s TO %s', tabela, papel);
        END LOOP;
    END LOOP;
END
$$;
