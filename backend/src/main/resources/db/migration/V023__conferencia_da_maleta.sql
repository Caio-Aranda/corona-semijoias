-- V023: conferência da maleta pelo vendedor (Fase 4, tarefa 4.3; ESPECIFICACAO §7.1; ADR-0011, item 11).
--
-- O vendedor bipa as peças recebidas e confirma; a diferença entre o que a maleta devia ter (o saldo no momento) e o
-- que ele contou fica registrada e a dona vê. A conferência é única por maleta e imutável; quem a faz liga a maleta ao
-- aparelho (remessas.dispositivo_id). Dados sem custo.

CREATE TABLE corona.conferencias (
    id uuid PRIMARY KEY,
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    dispositivo_id uuid,
    -- Quantas peças diferentes ficaram com falta ou sobra (calculado pelo servidor).
    divergencias integer NOT NULL CONSTRAINT conferencias_divergencias_nao_negativa CHECK (divergencias >= 0),
    conferida_em timestamptz NOT NULL DEFAULT now()
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.conferencias', imutavel => true);
COMMENT ON TABLE corona.conferencias IS 'Conferência da maleta pelo vendedor: uma por maleta, imutável (ADR-0011, item 11).';
CREATE UNIQUE INDEX conferencias_uma_por_remessa ON corona.conferencias (remessa_id);

CREATE TABLE corona.itens_conferencia (
    id uuid PRIMARY KEY,
    conferencia_id uuid NOT NULL REFERENCES corona.conferencias (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    esperado integer NOT NULL CONSTRAINT itens_conferencia_esperado_nao_negativo CHECK (esperado >= 0),
    contado integer NOT NULL CONSTRAINT itens_conferencia_contado_nao_negativo CHECK (contado >= 0)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.itens_conferencia', imutavel => true);
COMMENT ON TABLE corona.itens_conferencia IS 'Uma linha por peça da maleta: o que devia haver e o que o vendedor contou.';
CREATE UNIQUE INDEX itens_conferencia_produto_unico ON corona.itens_conferencia (conferencia_id, produto_id);

REVOKE ALL ON corona.conferencias, corona.itens_conferencia FROM PUBLIC;
