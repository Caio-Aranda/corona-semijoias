-- V053: código da maleta e nome da praça na maleta (Leva 9, tarefa 9.3; ESPECIFICACAO §23.2 e §23.11; ADR-0016, D11 e D12).
--
-- remessas.codigo: texto único (sem diferenciar maiúsculas), gerado pelo servidor ao criar a maleta em sequência simples
-- (0001, 0002...). As maletas que já existem recebem pela ordem de criação. É o código que a Lucia e o representante falam
-- ("a maleta 0007"), no lugar do número longo S12-V7-2 e do M-XXXXXX provisório do Corona Vendas.
-- remessas.praca_nome: a praça da maleta no momento em que ela nasceu (cópia do nome), para o Corona Vendas mostrar a praça
-- sem ganhar a tabela de praças. Se a praça mudar de nome depois, as maletas antigas guardam o nome de então.

ALTER TABLE corona.remessas
    ADD COLUMN codigo text,
    ADD COLUMN praca_nome text;

CREATE SEQUENCE corona.remessas_codigo_seq AS bigint;

CREATE FUNCTION corona.preencher_codigo_da_maleta() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.codigo IS NULL THEN
            NEW.codigo := lpad(nextval('corona.remessas_codigo_seq')::text, 4, '0');
        END IF;
    ELSIF OLD.codigo IS NOT NULL AND NEW.codigo IS DISTINCT FROM OLD.codigo THEN
        RAISE EXCEPTION 'O código da maleta % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'INSERT' OR NEW.praca_id IS DISTINCT FROM OLD.praca_id THEN
        NEW.praca_nome := (SELECT p.nome FROM corona.pracas p WHERE p.id = NEW.praca_id);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER remessas_codigo_e_praca BEFORE INSERT OR UPDATE ON corona.remessas
    FOR EACH ROW EXECUTE FUNCTION corona.preencher_codigo_da_maleta();

-- As maletas que já existem, pela ordem de criação (o gatilho de controle do sync dá versão e sync_seq novos: o pull leva).
WITH ordem AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS n FROM corona.remessas
)
UPDATE corona.remessas r
   SET codigo = lpad(ordem.n::text, 4, '0'),
       praca_nome = (SELECT p.nome FROM corona.pracas p WHERE p.id = r.praca_id)
  FROM ordem
 WHERE ordem.id = r.id;

SELECT setval('corona.remessas_codigo_seq', (SELECT count(*) FROM corona.remessas) + 1, false);

ALTER TABLE corona.remessas
    ALTER COLUMN codigo SET NOT NULL,
    ADD CONSTRAINT remessas_codigo_preenchido CHECK (btrim(codigo) <> '');
CREATE UNIQUE INDEX remessas_codigo_unico ON corona.remessas (lower(codigo));
COMMENT ON COLUMN corona.remessas.codigo IS 'Código da maleta (0001, 0002...), gerado pelo servidor; único sem diferenciar maiúsculas.';
COMMENT ON COLUMN corona.remessas.praca_nome IS 'Nome da praça quando a maleta nasceu (cópia, para o Corona Vendas).';
