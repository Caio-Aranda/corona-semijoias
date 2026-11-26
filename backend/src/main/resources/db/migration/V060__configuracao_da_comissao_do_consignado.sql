-- Meta, percentuais e dias do retorno do consignado (plano docs/planos/acerto-45-dias.md, D5 e D6; especificação §23.13).
-- Quatro chaves próprias, separadas das da §7.4 (acerto escondido). Teto de 40% = CHECK de acertos_nota (V051).
INSERT INTO corona.configuracoes (id, chave, tipo, valor_inteiro, valor_dinheiro) VALUES
    (uuidv7(), 'consignado_meta_guarani', 'INTEIRO', 700000, NULL),
    (uuidv7(), 'consignado_comissao_abaixo_da_meta', 'INTEIRO', 30, NULL),
    (uuidv7(), 'consignado_comissao_na_meta', 'INTEIRO', 40, NULL),
    (uuidv7(), 'consignado_dias_do_retorno', 'INTEIRO', 45, NULL);

ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_consignado_meta_faixa
    CHECK (chave <> 'consignado_meta_guarani' OR valor_inteiro BETWEEN 1 AND 1000000000);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_consignado_abaixo_faixa
    CHECK (chave <> 'consignado_comissao_abaixo_da_meta' OR valor_inteiro BETWEEN 0 AND 40);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_consignado_na_meta_faixa
    CHECK (chave <> 'consignado_comissao_na_meta' OR valor_inteiro BETWEEN 0 AND 40);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_consignado_dias_faixa
    CHECK (chave <> 'consignado_dias_do_retorno' OR valor_inteiro BETWEEN 1 AND 365);

-- Segunda trava, adiada para o COMMIT (a dona muda as duas de uma vez): abaixo da meta nunca passa de na meta.
CREATE FUNCTION corona.conferir_percentuais_do_consignado() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    abaixo integer;
    na_meta integer;
BEGIN
    SELECT valor_inteiro INTO abaixo FROM corona.configuracoes WHERE chave = 'consignado_comissao_abaixo_da_meta';
    SELECT valor_inteiro INTO na_meta FROM corona.configuracoes WHERE chave = 'consignado_comissao_na_meta';
    IF abaixo > na_meta THEN
        RAISE EXCEPTION 'O percentual abaixo da meta (%) não pode passar do percentual na meta (%).', abaixo, na_meta
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER configuracoes_consignado_em_ordem AFTER INSERT OR UPDATE ON corona.configuracoes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (NEW.chave IN ('consignado_comissao_abaixo_da_meta', 'consignado_comissao_na_meta'))
    EXECUTE FUNCTION corona.conferir_percentuais_do_consignado();
