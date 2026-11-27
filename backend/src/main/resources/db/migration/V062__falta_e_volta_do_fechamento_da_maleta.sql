-- V062: falta e peças que voltam depois no Fechamento da maleta (revisão de 27/11/2026, C2 e C3; ESPECIFICACAO §23.10).
-- A V061 é da comissão pela meta (PR 287); esta vem depois dela.
--
-- 1. pendencias_da_maleta: cada peça que faltou na conclusão do fechamento, com a decisão da dona (cobrar do representante ou
--    perda da loja). Só do servidor e imutável; nunca vai ao Corona Vendas (a tabela pendencias_acerto vai no pull e, no
--    Corona Vendas 1.2.0, exige acerto_id: uma pendência sem acerto quebraria a sincronização). Toda linha tem o seu
--    movimento PERDA na maleta, da mesma quantidade (conferência C8).
-- 2. leituras_fechamento_maleta.depois_de_fechar: a leitura da peça que a cliente devolveu depois de a maleta ser fechada.

CREATE TABLE corona.pendencias_da_maleta (
    id uuid PRIMARY KEY,
    fechamento_id uuid NOT NULL REFERENCES corona.fechamentos_maleta (id),
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    quantidade integer NOT NULL CONSTRAINT pendencias_da_maleta_quantidade_positiva CHECK (quantidade > 0),
    preco_unitario numeric(14, 2) NOT NULL CONSTRAINT pendencias_da_maleta_preco_nao_negativo CHECK (preco_unitario >= 0),
    valor numeric(14, 2) NOT NULL CONSTRAINT pendencias_da_maleta_valor_nao_negativo CHECK (valor >= 0),
    decisao text NOT NULL CONSTRAINT pendencias_da_maleta_decisao_conhecida
        CHECK (decisao IN ('COBRAR_DO_REPRESENTANTE', 'PERDA_DA_LOJA')),
    motivo text CONSTRAINT pendencias_da_maleta_motivo_curto CHECK (length(motivo) <= 500),
    -- O movimento PERDA desta falta na maleta (um para um).
    movimento_id uuid NOT NULL CONSTRAINT pendencias_da_maleta_um_movimento UNIQUE REFERENCES corona.movimentos_remessa (id),
    registrada_por uuid NOT NULL REFERENCES corona.usuarios (id),
    CONSTRAINT pendencias_da_maleta_uma_por_peca UNIQUE (fechamento_id, produto_id),
    -- A perda da loja precisa de motivo.
    CONSTRAINT pendencias_da_maleta_motivo_da_perda CHECK (
        decisao <> 'PERDA_DA_LOJA' OR (motivo IS NOT NULL AND btrim(motivo) <> ''))
);
SELECT corona.aplicar_convencoes_do_servidor('corona.pendencias_da_maleta', editavel => false);
COMMENT ON TABLE corona.pendencias_da_maleta IS
    'Falta na conclusão do Fechamento da maleta, com a decisão da dona (§23.10, item 1). Imutável; só do servidor.';
CREATE INDEX pendencias_da_maleta_por_remessa ON corona.pendencias_da_maleta (remessa_id);
CREATE INDEX pendencias_da_maleta_por_vendedor ON corona.pendencias_da_maleta (vendedor_id);
REVOKE ALL ON corona.pendencias_da_maleta FROM PUBLIC;

ALTER TABLE corona.leituras_fechamento_maleta
    ADD COLUMN depois_de_fechar boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN corona.leituras_fechamento_maleta.depois_de_fechar IS
    'Peça lida depois da conclusão: a cliente devolveu depois de a maleta ser fechada (§23.10, item 3).';
