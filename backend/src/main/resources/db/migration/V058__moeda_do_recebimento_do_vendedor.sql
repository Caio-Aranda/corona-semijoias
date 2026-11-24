-- Recebimento do representante digitado em Dólar ou Guarani (pedido do responsável, 23/11/2026; plano em
-- docs/planos/recebimento-em-tres-moedas.md; §7.5 e adendo do ADR-0006, item 13).
--
-- O valor continua gravado em Real (valor, NUMERIC(14,2)), convertido pelo servidor: é ele que vale para contas, caixa, totais e
-- conciliações. A moeda, o valor digitado e a cotação usada ficam só como informação (a lista e a ficha mostram "Recebido em
-- Gs. 500.000"); nunca somam e não vão ao aplicativo do vendedor (o pull lista as colunas). As linhas antigas ficam REAL (o DEFAULT
-- não dispara gatilho).

ALTER TABLE corona.recebimentos_vendedor
    ADD COLUMN moeda text NOT NULL DEFAULT 'REAL'
        CONSTRAINT recebimentos_vendedor_moeda_conhecida CHECK (moeda IN ('REAL', 'DOLAR', 'GUARANI')),
    -- O que a dona digitou na moeda escolhida (só DOLAR e GUARANI); o estorno tem o sinal trocado, como o valor.
    ADD COLUMN valor_na_moeda numeric(14, 2),
    -- A cotação que converteu: a do dia do lançamento (avulso) ou a do acerto (no fechamento).
    ADD COLUMN cotacao_id uuid REFERENCES corona.cotacoes (id),
    ADD CONSTRAINT recebimentos_vendedor_moeda_completa CHECK (
        (moeda = 'REAL' AND valor_na_moeda IS NULL AND cotacao_id IS NULL)
        OR (moeda <> 'REAL' AND valor_na_moeda IS NOT NULL AND cotacao_id IS NOT NULL AND sign(valor_na_moeda) = sign(valor))),
    -- O Guarani não tem centavos.
    ADD CONSTRAINT recebimentos_vendedor_guarani_inteiro CHECK (moeda <> 'GUARANI' OR valor_na_moeda = trunc(valor_na_moeda));

COMMENT ON COLUMN corona.recebimentos_vendedor.moeda IS
    'Em que moeda a dona digitou o recebimento (REAL, DOLAR ou GUARANI). Só informação: o valor que vale é sempre o valor em Real.';
COMMENT ON COLUMN corona.recebimentos_vendedor.valor_na_moeda IS
    'O valor digitado em Dólar ou Guarani (vazio em Real). Só informação; nunca soma nem entra em conferência.';
COMMENT ON COLUMN corona.recebimentos_vendedor.cotacao_id IS
    'A cotação com que o servidor converteu o valor digitado para Real (vazia em Real).';

CREATE INDEX recebimentos_vendedor_por_cotacao ON corona.recebimentos_vendedor (cotacao_id) WHERE cotacao_id IS NOT NULL;

-- O estorno copia o original e inverte o valor: agora também a moeda, a cotação e o valor digitado.
CREATE OR REPLACE FUNCTION corona.conferir_recebimento_do_vendedor() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a record;
    o record;
BEGIN
    IF NEW.estorna_id IS NULL THEN
        IF NEW.momento = 'ACERTO' THEN
            SELECT status, vendedor_id INTO a FROM corona.acertos WHERE id = NEW.acerto_id FOR SHARE;
            IF a.status IS DISTINCT FROM 'EM_ACERTO' OR a.vendedor_id IS DISTINCT FROM NEW.vendedor_id THEN
                RAISE EXCEPTION 'O recebimento % só nasce dentro do fechamento de acerto em andamento do mesmo vendedor.', NEW.id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;
    ELSE
        SELECT vendedor_id, momento, acerto_id, aplicado_em, forma, valor, estorna_id, moeda, valor_na_moeda, cotacao_id INTO o
          FROM corona.recebimentos_vendedor WHERE id = NEW.estorna_id;
        IF NOT FOUND OR o.estorna_id IS NOT NULL OR o.vendedor_id <> NEW.vendedor_id OR o.momento <> NEW.momento
                OR o.acerto_id IS DISTINCT FROM NEW.acerto_id OR o.aplicado_em <> NEW.aplicado_em OR o.forma <> NEW.forma
                OR o.valor <> -NEW.valor OR o.moeda <> NEW.moeda OR o.cotacao_id IS DISTINCT FROM NEW.cotacao_id
                OR o.valor_na_moeda IS DISTINCT FROM -NEW.valor_na_moeda THEN
            RAISE EXCEPTION 'O estorno % precisa desfazer um recebimento do mesmo vendedor, da mesma forma e moeda, pelo mesmo valor.',
                NEW.id USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
