-- V055: data do acerto editável (Leva 9, tarefa 9.8; ADR-0016, "Ajustes da tarefa 9.8"; ESPECIFICACAO §23.8).
--
-- acertado_em passa a ser a Data do acerto escolhida na Cobrança (hoje, ou um dia que já passou: meio-dia de Brasília). O
-- momento em que o acerto foi LANÇADO (com a correção de relógio do computador) vira registrado_em, que é a chave da ordem
-- dos débitos do cliente (o mais antigo é quitado primeiro). Para todo acerto já gravado registrado_em = acertado_em, então a
-- ordem de hoje não muda em nenhuma linha. O gatilho passa a proteger a coluna nova (acerto é imutável; correção = estorno).

ALTER TABLE corona.acertos_nota ADD COLUMN registrado_em timestamptz;
UPDATE corona.acertos_nota SET registrado_em = acertado_em;
ALTER TABLE corona.acertos_nota ALTER COLUMN registrado_em SET NOT NULL;
COMMENT ON COLUMN corona.acertos_nota.registrado_em IS
    'Quando o acerto foi lançado (hora do computador corrigida): chave da ordem dos débitos. acertado_em é a Data do acerto.';

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
            OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'O acerto % não pode ser alterado: a correção é feita com estorno.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.estornado_em IS NOT NULL AND (NEW.estornado_em IS DISTINCT FROM OLD.estornado_em
            OR NEW.estorno_id IS DISTINCT FROM OLD.estorno_id OR NEW.motivo_estorno IS DISTINCT FROM OLD.motivo_estorno) THEN
        RAISE EXCEPTION 'O acerto % já foi estornado e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
