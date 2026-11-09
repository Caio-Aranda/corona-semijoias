-- V003: procedimento de restauração de backup do sync (ADR-0002, item 5, "Restauração de backup (R1)").
--
-- Por quê: ao restaurar um pg_dump num servidor novo, o contador de transações recomeça, mas o
-- sync_xid gravado nas linhas continua com os valores antigos, que podem ser maiores que o xmin
-- do servidor novo. O pull nunca entregaria essas linhas. Este procedimento põe o sync_xid de
-- todas as linhas sincronizáveis no menor valor ('0'::xid8), sem mexer em sync_seq, version nem
-- updated_at (gatilhos desligados durante a troca), e sobe a época do cursor: todo aparelho
-- recebe "cursor inválido" e refaz a carga completa. A ordem entre as linhas antigas continua a
-- do sync_seq, e toda linha gravada depois recebe um identificador maior que zero.
--
-- Quem roda: o usuário das migrações (dono das tabelas; só o dono desliga gatilhos), com a API
-- parada, logo depois do pg_restore. Passo a passo: docs/restauracao-de-backup.md.
--   SELECT corona.preparar_sync_apos_restauracao();
-- Rodar duas vezes não estraga nada: só sobe a época de novo.

CREATE FUNCTION corona.preparar_sync_apos_restauracao() RETURNS bigint
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    tabela regclass;
    linhas bigint;
    nova_epoca bigint;
BEGIN
    -- Todas as tabelas do schema com sync_xid (o teste de convenções garante que só as
    -- sincronizáveis têm essa coluna).
    FOR tabela IN
        SELECT c.oid::regclass
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'sync_xid' AND NOT a.attisdropped
         WHERE n.nspname = 'corona' AND c.relkind = 'r'
         ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER TABLE %s DISABLE TRIGGER USER', tabela);
        EXECUTE format('UPDATE %s SET sync_xid = ''0''::xid8', tabela);
        GET DIAGNOSTICS linhas = ROW_COUNT;
        EXECUTE format('ALTER TABLE %s ENABLE TRIGGER USER', tabela);
        RAISE NOTICE 'Tabela %: % linha(s) preparada(s) para o pull.', tabela, linhas;
    END LOOP;

    UPDATE corona.sync_estado SET epoca = epoca + 1 RETURNING epoca INTO nova_epoca;
    RAISE NOTICE 'Época do sync agora é %. Os aparelhos vão refazer a carga completa.', nova_epoca;
    RETURN nova_epoca;
END;
$$;
COMMENT ON FUNCTION corona.preparar_sync_apos_restauracao() IS
    'Depois de restaurar um backup: sync_xid = 0 em todas as tabelas sincronizáveis (gatilhos desligados) e época + 1. Roda com o usuário das migrações. ADR-0002.';
REVOKE EXECUTE ON FUNCTION corona.preparar_sync_apos_restauracao() FROM PUBLIC;
