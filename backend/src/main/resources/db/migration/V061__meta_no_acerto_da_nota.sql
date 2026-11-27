-- O acerto da nota guarda a regra da comissão e a meta usada (plano docs/planos/acerto-45-dias.md, seção 1; especificação §23.13).
-- Nenhum UPDATE em dado existente: os acertos já gravados ficam INFORMADA, com o Guarani vazio.
ALTER TABLE corona.acertos_nota
    ADD COLUMN regra_da_comissao text NOT NULL DEFAULT 'INFORMADA'
        CONSTRAINT acertos_nota_regra_da_comissao_conhecida CHECK (regra_da_comissao IN ('INFORMADA', 'META')),
    ADD COLUMN meta_guarani bigint,
    ADD COLUMN vd_guarani bigint;

COMMENT ON COLUMN corona.acertos_nota.regra_da_comissao IS
    'INFORMADA: a porcentagem veio do app (formato 1, Corona Vendas 1.2.0). META: o servidor decidiu pela meta do consignado (formato 2).';
COMMENT ON COLUMN corona.acertos_nota.meta_guarani IS 'A meta em Guarani usada na decisão (só regra META).';
COMMENT ON COLUMN corona.acertos_nota.vd_guarani IS
    'O VD da nota em Guarani, convertido uma vez pela cotação do acerto (só regra META; vazio quando não havia cotação).';

-- INFORMADA: os dois vazios. META: a meta preenchida e positiva; o VD em Guarani não negativo, e só pode faltar quando o acerto
-- não tem cotação (sem cotação, a comissão fica abaixo da meta, com alerta). Que o percentual combine com o lado da meta é
-- conferido pela conciliação K9 (o banco não sabe quais eram os percentuais do dia).
ALTER TABLE corona.acertos_nota ADD CONSTRAINT acertos_nota_meta_coerente CHECK (
    (regra_da_comissao = 'INFORMADA' AND meta_guarani IS NULL AND vd_guarani IS NULL)
    OR (regra_da_comissao = 'META' AND meta_guarani IS NOT NULL AND meta_guarani > 0
        AND ((vd_guarani IS NOT NULL AND vd_guarani >= 0) OR (vd_guarani IS NULL AND cotacao_id IS NULL))));

-- A imutabilidade protege também as três colunas novas.
CREATE OR REPLACE FUNCTION corona.proteger_acerto_nota() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.nota_id IS DISTINCT FROM OLD.nota_id OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id
            OR NEW.semana_id IS DISTINCT FROM OLD.semana_id OR NEW.acertado_em IS DISTINCT FROM OLD.acertado_em
            OR NEW.registrado_em IS DISTINCT FROM OLD.registrado_em
            OR NEW.comissao_percentual IS DISTINCT FROM OLD.comissao_percentual OR NEW.itens_dx IS DISTINCT FROM OLD.itens_dx
            OR NEW.itens_dv IS DISTINCT FROM OLD.itens_dv OR NEW.itens_vd IS DISTINCT FROM OLD.itens_vd
            OR NEW.valor_dx IS DISTINCT FROM OLD.valor_dx OR NEW.valor_dv IS DISTINCT FROM OLD.valor_dv
            OR NEW.valor_vd IS DISTINCT FROM OLD.valor_vd OR NEW.comissao IS DISTINCT FROM OLD.comissao
            OR NEW.bruto IS DISTINCT FROM OLD.bruto OR NEW.pendencias_anteriores IS DISTINCT FROM OLD.pendencias_anteriores
            OR NEW.total_a_receber IS DISTINCT FROM OLD.total_a_receber OR NEW.total_pago IS DISTINCT FROM OLD.total_pago
            OR NEW.ficou_devendo IS DISTINCT FROM OLD.ficou_devendo OR NEW.recibo_id IS DISTINCT FROM OLD.recibo_id
            OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id OR NEW.dispositivo_id IS DISTINCT FROM OLD.dispositivo_id
            OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
            OR NEW.regra_da_comissao IS DISTINCT FROM OLD.regra_da_comissao OR NEW.meta_guarani IS DISTINCT FROM OLD.meta_guarani
            OR NEW.vd_guarani IS DISTINCT FROM OLD.vd_guarani THEN
        RAISE EXCEPTION 'O acerto % não pode ser alterado: a correção é feita com estorno.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.estornado_em IS NOT NULL AND (NEW.estornado_em IS DISTINCT FROM OLD.estornado_em
            OR NEW.estorno_id IS DISTINCT FROM OLD.estorno_id OR NEW.motivo_estorno IS DISTINCT FROM OLD.motivo_estorno) THEN
        RAISE EXCEPTION 'O acerto % já foi estornado e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
