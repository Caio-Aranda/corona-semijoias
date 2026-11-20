-- V056: "representante" no lugar de "vendedor" no nome das categorias do sistema do caixa (Leva 9, tarefa 9.8, item 4).
--
-- Só o nome visível das duas categorias do sistema muda; códigos, ids, origens e valores não mudam. Os lançamentos já gravados
-- (imutáveis) NÃO são reescritos: a descrição deles fica como foi gravada. Nenhum gatilho é desligado: a função que protege a
-- categoria do sistema passa a aceitar só estas duas trocas de nome (e nenhuma outra), e o UPDATE vem depois.

CREATE OR REPLACE FUNCTION corona.proteger_categoria_do_caixa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tipo IS DISTINCT FROM OLD.tipo OR NEW.sistema IS DISTINCT FROM OLD.sistema THEN
        RAISE EXCEPTION 'O tipo da categoria % não muda.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.sistema AND (NEW.ativo IS DISTINCT FROM OLD.ativo OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
            OR (NEW.nome IS DISTINCT FROM OLD.nome AND NOT (
                (OLD.nome = 'Repasse do vendedor' AND NEW.nome = 'Repasse do representante')
                OR (OLD.nome = 'Pagamento ao vendedor' AND NEW.nome = 'Pagamento ao representante')))) THEN
        RAISE EXCEPTION 'A categoria do sistema % não pode ser alterada.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

UPDATE corona.categorias_caixa SET nome = 'Repasse do representante' WHERE sistema AND nome = 'Repasse do vendedor';
UPDATE corona.categorias_caixa SET nome = 'Pagamento ao representante' WHERE sistema AND nome = 'Pagamento ao vendedor';
