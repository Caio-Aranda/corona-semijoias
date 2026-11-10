-- V018: movimentações de estoque (Fase 2, tarefa 2.2; ESPECIFICACAO §7.6 e §13; ADR-0008).
--
-- Cada entrada, saída, perda, ajuste (e, na Fase 3, remessa e devolução) é UM registro imutável.
-- O saldo é a soma dos registros; produtos.estoque_atual é o atalho, mantido SÓ pelo serviço de
-- estoque, na mesma transação do registro. Só do servidor (o vendedor nunca recebe estoque central).
-- Corrigir é estornar: um registro novo, do mesmo tipo, com a quantidade de sinal oposto, ligado ao
-- original por estorna_id (e um registro só é estornado uma vez).

CREATE TABLE corona.movimentacoes_estoque (
    id uuid PRIMARY KEY,
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    tipo text NOT NULL CONSTRAINT movimentacoes_estoque_tipo_conhecido
        CHECK (tipo IN ('ENTRADA', 'SAIDA', 'AJUSTE', 'REMESSA', 'DEVOLUCAO', 'AVARIA')),
    -- Inteiro COM sinal (nunca zero): + soma, - subtrai. O servidor monta o sinal pelo tipo.
    quantidade integer NOT NULL CONSTRAINT movimentacoes_estoque_quantidade_nao_zero CHECK (quantidade <> 0),
    -- Saldo do produto logo depois deste registro (conferência e histórico).
    saldo_depois integer NOT NULL CONSTRAINT movimentacoes_estoque_saldo_nao_negativo CHECK (saldo_depois >= 0),
    origem_tipo text NOT NULL CONSTRAINT movimentacoes_estoque_origem_conhecida
        CHECK (origem_tipo IN ('MANUAL', 'AJUSTE_MANUAL', 'COMPRA', 'INVENTARIO', 'MALETA', 'ESTORNO')),
    origem_id uuid,
    motivo text CONSTRAINT movimentacoes_estoque_motivo_preenchido CHECK (motivo IS NULL OR btrim(motivo) <> ''),
    usuario_id uuid NOT NULL REFERENCES corona.usuarios (id),
    ocorrida_em timestamptz NOT NULL,
    estorna_id uuid REFERENCES corona.movimentacoes_estoque (id),
    -- O sinal combina com o tipo, exceto no estorno (que tem o sinal oposto ao do original).
    CONSTRAINT movimentacoes_estoque_sinal_do_tipo CHECK (
        estorna_id IS NOT NULL
        OR tipo = 'AJUSTE'
        OR (tipo IN ('ENTRADA', 'DEVOLUCAO') AND quantidade > 0)
        OR (tipo IN ('SAIDA', 'REMESSA', 'AVARIA') AND quantidade < 0)),
    -- Estorno e ajuste manual sempre dizem o motivo.
    CONSTRAINT movimentacoes_estoque_motivo_do_estorno CHECK (
        motivo IS NOT NULL OR (estorna_id IS NULL AND tipo <> 'AJUSTE')),
    CONSTRAINT movimentacoes_estoque_estorno_tem_origem CHECK (
        (estorna_id IS NULL) = (origem_tipo <> 'ESTORNO'))
);
COMMENT ON TABLE corona.movimentacoes_estoque IS
    'Só do servidor, histórico imutável (ADR-0008): cada movimento do estoque central. Saldo do produto = soma de quantidade. Corrigir = estorno (estorna_id), nunca UPDATE nem DELETE.';
COMMENT ON COLUMN corona.movimentacoes_estoque.quantidade IS
    'Com sinal: positivo soma, negativo subtrai. ENTRADA e DEVOLUCAO somam; SAIDA, REMESSA e AVARIA subtraem; AJUSTE e estorno podem ser dos dois.';
SELECT corona.aplicar_convencoes_do_servidor('corona.movimentacoes_estoque', editavel => false);

-- Um movimento só é estornado uma vez.
CREATE UNIQUE INDEX movimentacoes_estoque_estorno_unico ON corona.movimentacoes_estoque (estorna_id)
    WHERE estorna_id IS NOT NULL;
CREATE INDEX movimentacoes_estoque_por_produto ON corona.movimentacoes_estoque (produto_id, ocorrida_em DESC, id);
CREATE INDEX movimentacoes_estoque_por_data ON corona.movimentacoes_estoque (ocorrida_em DESC, id);
CREATE INDEX movimentacoes_estoque_por_origem ON corona.movimentacoes_estoque (origem_tipo, origem_id);

REVOKE ALL ON corona.movimentacoes_estoque FROM PUBLIC;

-- Segunda trava do saldo (a primeira é o serviço): nunca negativo (ADR-0008, item 4).
ALTER TABLE corona.produtos
    ADD CONSTRAINT produtos_estoque_atual_nao_negativo CHECK (estoque_atual >= 0);
