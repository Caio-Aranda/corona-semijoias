-- Tarefa 6.2 (ADR-0014, item 10): a garantia e os limites de devolução em `configuracoes`.
-- Cinco chaves novas, todas com o padrão seguro até a dona responder (plano da Fase 6, dúvidas D1, D3 e D9):
--   prazo_arrependimento_dias   INTEIRO   7    (1 a 365)
--   prazo_defeito_dias          INTEIRO   30   (1 a 365)
--   limite_reembolso_semanal    DINHEIRO  sem limite (vazio)   (maior que 0 e até 99.999.999,99)
--   valor_aprovacao_reembolso   DINHEIRO  sem valor (vazio)    (idem)
--   percentual_alerta_devolucao INTEIRO   20   (1 a 100)
-- Tipo novo DINHEIRO (V006 prevê): coluna valor_dinheiro NUMERIC(14,2), nunca float. Para DINHEIRO, vazio
-- significa "sem limite"; para INTEIRO o valor é sempre obrigatório.

ALTER TABLE corona.configuracoes ADD COLUMN valor_dinheiro numeric(14,2);

ALTER TABLE corona.configuracoes DROP CONSTRAINT configuracoes_tipo_conhecido;
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_tipo_conhecido
    CHECK (tipo IN ('INTEIRO', 'DINHEIRO'));

ALTER TABLE corona.configuracoes DROP CONSTRAINT configuracoes_valor_do_tipo;
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_valor_do_tipo
    CHECK ((tipo = 'INTEIRO' AND valor_inteiro IS NOT NULL AND valor_dinheiro IS NULL)
        OR (tipo = 'DINHEIRO' AND valor_inteiro IS NULL));

ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_prazo_arrependimento_faixa
    CHECK (chave <> 'prazo_arrependimento_dias' OR valor_inteiro BETWEEN 1 AND 365);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_prazo_defeito_faixa
    CHECK (chave <> 'prazo_defeito_dias' OR valor_inteiro BETWEEN 1 AND 365);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_percentual_alerta_devolucao_faixa
    CHECK (chave <> 'percentual_alerta_devolucao' OR valor_inteiro BETWEEN 1 AND 100);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_limite_reembolso_semanal_faixa
    CHECK (chave <> 'limite_reembolso_semanal' OR (tipo = 'DINHEIRO' AND (valor_dinheiro IS NULL OR valor_dinheiro > 0)));
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_valor_aprovacao_reembolso_faixa
    CHECK (chave <> 'valor_aprovacao_reembolso' OR (tipo = 'DINHEIRO' AND (valor_dinheiro IS NULL OR valor_dinheiro > 0)));

COMMENT ON COLUMN corona.configuracoes.tipo IS
    'Qual coluna de valor vale para esta chave: INTEIRO (valor_inteiro, obrigatório) ou DINHEIRO (valor_dinheiro, vazio = sem limite).';
COMMENT ON COLUMN corona.configuracoes.valor_dinheiro IS
    'Valor em Real, NUMERIC(14,2). Só para chaves do tipo DINHEIRO; vazio = sem limite.';

INSERT INTO corona.configuracoes (id, chave, tipo, valor_inteiro, valor_dinheiro) VALUES
    (uuidv7(), 'prazo_arrependimento_dias', 'INTEIRO', 7, NULL),
    (uuidv7(), 'prazo_defeito_dias', 'INTEIRO', 30, NULL),
    (uuidv7(), 'limite_reembolso_semanal', 'DINHEIRO', NULL, NULL),
    (uuidv7(), 'valor_aprovacao_reembolso', 'DINHEIRO', NULL, NULL),
    (uuidv7(), 'percentual_alerta_devolucao', 'INTEIRO', 20, NULL);
