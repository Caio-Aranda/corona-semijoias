-- A dona muda a cotação do Dólar e do Guarani durante o dia (ADR-0006, item 15; plano
-- docs/planos/cotacao-da-dona-no-dia.md). A cotação da dona é uma linha nova da mesma tabela, com origem
-- 'DONA'. A tabela continua imutável: corrigir é lançar outra. Vale a de maior vigente_desde menor ou igual
-- ao momento do lançamento.

ALTER TABLE corona.cotacoes
    ADD COLUMN origem text NOT NULL DEFAULT 'BANCO_CENTRAL'
        CONSTRAINT cotacoes_origem_conhecida CHECK (origem IN ('BANCO_CENTRAL', 'DONA')),
    ADD COLUMN vigente_desde timestamptz,
    ADD COLUMN lancada_por uuid REFERENCES corona.usuarios (id),
    ADD COLUMN guaranis_por_real numeric(18,8)
        CONSTRAINT cotacoes_guaranis_por_real_positivo CHECK (guaranis_por_real > 0);

-- vigente_desde das linhas do BCB: 00:00 do dia em Brasília. A tabela recusa UPDATE por comando; só esse
-- gatilho é desligado, dentro da migração. O gatilho de controle continua ligado: version e sync_seq sobem e
-- o Corona Vendas recebe as linhas de novo, já com as colunas novas.
ALTER TABLE corona.cotacoes DISABLE TRIGGER corona_recusa_update;
UPDATE corona.cotacoes SET vigente_desde = (data_cotacao::timestamp AT TIME ZONE 'America/Sao_Paulo');
ALTER TABLE corona.cotacoes ENABLE TRIGGER corona_recusa_update;

ALTER TABLE corona.cotacoes
    ALTER COLUMN vigente_desde SET NOT NULL,
    DROP CONSTRAINT cotacoes_data_unica,
    ADD CONSTRAINT cotacoes_origem_completa CHECK (
        (origem = 'BANCO_CENTRAL' AND lancada_por IS NULL AND guaranis_por_real IS NULL)
        OR (origem = 'DONA' AND lancada_por IS NOT NULL AND guaranis_por_real IS NOT NULL
            AND usd_compra = usd_venda AND pyg_compra = pyg_venda
            AND data_cotacao = (vigente_desde AT TIME ZONE 'America/Sao_Paulo')::date));

CREATE UNIQUE INDEX cotacoes_bcb_um_por_dia ON corona.cotacoes (data_cotacao) WHERE origem = 'BANCO_CENTRAL';
CREATE INDEX cotacoes_por_vigencia ON corona.cotacoes (vigente_desde DESC, id DESC);

COMMENT ON TABLE corona.cotacoes IS
    'Sincronizável e imutável: uma linha por dia útil do BCB (Dólar e Guarani juntos) mais as cotações que a dona lança durante o dia. Vale a de maior vigente_desde menor ou igual ao momento do lançamento. ADR-0006.';
COMMENT ON COLUMN corona.cotacoes.data_cotacao IS
    'BCB: dia do arquivo (um por dia). Dona: dia, em Brasília, em que ela lançou.';
COMMENT ON COLUMN corona.cotacoes.origem IS 'BANCO_CENTRAL (buscada) ou DONA (digitada na loja).';
COMMENT ON COLUMN corona.cotacoes.vigente_desde IS
    'Instante a partir do qual vale. BCB: 00:00 do dia em Brasília. Dona: a hora em que o servidor gravou.';
COMMENT ON COLUMN corona.cotacoes.lancada_por IS 'Só servidor (nunca vai ao Corona Vendas): quem lançou a cotação da dona.';
COMMENT ON COLUMN corona.cotacoes.guaranis_por_real IS
    'Só na cotação da dona: Guaranis por 1 Real como ela digitou (ex.: 1350). Recebimento em Guarani divide por este valor.';
