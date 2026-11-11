-- V024: segunda trava do saldo da maleta, no banco (auditoria de 11/11/2026, achado A3; ADR-0008, item 4; ADR-0010).
--
-- O saldo de uma peça na maleta é qtd_enviada + a soma dos movimentos (nunca um número guardado). A venda, a devolução
-- e a conferência já recusam o que o deixaria negativo, com a linha da remessa travada. Este gatilho é a segunda trava,
-- como o CHECK de produtos.estoque_atual no estoque central: um movimento gravado por qualquer caminho (inclusive o envio
-- do aparelho na Fase 5) nunca deixa a peça negativa na maleta.
--
-- Só o movimento que tira peça da maleta (quantidade negativa) é conferido. Ele trava a linha da remessa antes de somar:
-- é a mesma linha que os serviços travam primeiro, então não cria uma ordem nova de travas, e dois movimentos da mesma
-- maleta gravados ao mesmo tempo por fora do serviço somam um depois do outro.

CREATE FUNCTION corona.conferir_saldo_da_maleta() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    saldo bigint;
BEGIN
    IF NEW.quantidade > 0 THEN
        RETURN NULL;
    END IF;
    PERFORM 1 FROM corona.remessas WHERE id = NEW.remessa_id FOR UPDATE;
    SELECT coalesce((SELECT i.qtd_enviada FROM corona.itens_remessa i
                      WHERE i.remessa_id = NEW.remessa_id AND i.produto_id = NEW.produto_id AND i.deleted_at IS NULL), 0)
           + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = NEW.remessa_id AND m.produto_id = NEW.produto_id), 0)
      INTO saldo;
    IF saldo < 0 THEN
        RAISE EXCEPTION 'A peça % ficaria com saldo negativo (%) na maleta %: o saldo da maleta nunca passa de zero.',
            NEW.produto_id, saldo, NEW.remessa_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER movimentos_remessa_saldo_nao_negativo AFTER INSERT ON corona.movimentos_remessa
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_saldo_da_maleta();
