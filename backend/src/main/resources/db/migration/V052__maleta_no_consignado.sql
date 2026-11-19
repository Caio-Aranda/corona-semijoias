-- V052: a maleta no consignado e o Fechamento da maleta (Leva 9, tarefa 9.1; ADR-0016, itens 6 e 14; ESPECIFICACAO §23.6 e §23.10).
--
-- movimentos_remessa ganha CONSIGNADO_SAIDA (negativo: a peça sai da maleta na nota, na Reposição e a peça nova da troca) e
-- CONSIGNADO_RETORNO (positivo: devolução no acerto e peça que volta na troca). O saldo continua sendo "enviada + Σ movimentos".
-- A venda pelo cliente NÃO é movimento de maleta: a peça já saiu na nota; o acerto só decide se volta. A saída da nota é
-- aceita acima do saldo (a peça está com o cliente) e o serviço grava um alerta (dúvida D4): por isso o gatilho de saldo
-- não olha os tipos CONSIGNADO_*.
-- remessas.status ganha FECHADA (a dona bipou a maleta na volta). Maleta fechada não aceita CONSIGNADO_SAIDA e continua
-- aceitando CONSIGNADO_RETORNO (o acerto das notas pendentes acontece depois).

ALTER TABLE corona.movimentos_remessa
    ADD COLUMN nota_id uuid CONSTRAINT movimentos_remessa_nota_fk REFERENCES corona.notas_consignado (id),
    ADD COLUMN acerto_nota_id uuid CONSTRAINT movimentos_remessa_acerto_nota_fk REFERENCES corona.acertos_nota (id);
COMMENT ON COLUMN corona.movimentos_remessa.nota_id IS 'A nota do consignado deste movimento CONSIGNADO_SAIDA ou CONSIGNADO_RETORNO (e do estorno dele).';
COMMENT ON COLUMN corona.movimentos_remessa.acerto_nota_id IS 'O acerto que devolveu a peça (CONSIGNADO_RETORNO do acerto).';
CREATE INDEX movimentos_remessa_por_nota ON corona.movimentos_remessa (nota_id) WHERE nota_id IS NOT NULL;

ALTER TABLE corona.movimentos_remessa DROP CONSTRAINT movimentos_remessa_tipo_conhecido;
ALTER TABLE corona.movimentos_remessa ADD CONSTRAINT movimentos_remessa_tipo_conhecido
    CHECK (tipo IN ('VENDA', 'ESTORNO_VENDA', 'DEVOLUCAO_CLIENTE', 'TROCA_ENTRADA', 'TROCA_SAIDA', 'PERDA', 'AVARIA',
                    'AJUSTE', 'DEVOLUCAO_DONA', 'CONSIGNADO_SAIDA', 'CONSIGNADO_RETORNO'));
ALTER TABLE corona.movimentos_remessa DROP CONSTRAINT movimentos_remessa_sinal_pelo_tipo;
ALTER TABLE corona.movimentos_remessa ADD CONSTRAINT movimentos_remessa_sinal_pelo_tipo CHECK (
    CASE
        WHEN tipo = 'ESTORNO_VENDA' THEN quantidade > 0
        WHEN estorna_id IS NOT NULL OR tipo = 'AJUSTE' THEN true
        WHEN tipo IN ('VENDA', 'TROCA_SAIDA', 'PERDA', 'AVARIA', 'DEVOLUCAO_DONA', 'CONSIGNADO_SAIDA') THEN quantidade < 0
        ELSE quantidade > 0
    END);
ALTER TABLE corona.movimentos_remessa ADD CONSTRAINT movimentos_remessa_consignado_da_nota
    CHECK ((tipo IN ('CONSIGNADO_SAIDA', 'CONSIGNADO_RETORNO')) = (nota_id IS NOT NULL));
ALTER TABLE corona.movimentos_remessa ADD CONSTRAINT movimentos_remessa_acerto_nota_do_retorno
    CHECK (acerto_nota_id IS NULL OR tipo = 'CONSIGNADO_RETORNO');

-- O gatilho de saldo da V024, inteiro, sem olhar os movimentos do consignado (D4: aceita com alerta).
CREATE OR REPLACE FUNCTION corona.conferir_saldo_da_maleta() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    saldo bigint;
BEGIN
    IF NEW.quantidade > 0 OR NEW.tipo IN ('CONSIGNADO_SAIDA', 'CONSIGNADO_RETORNO') THEN
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

ALTER TABLE corona.remessas DROP CONSTRAINT remessas_status_conhecido;
ALTER TABLE corona.remessas ADD CONSTRAINT remessas_status_conhecido
    CHECK (status IN ('RASCUNHO', 'ENVIADA', 'CONFERIDA', 'EM_ACERTO', 'ACERTADA', 'CANCELADA', 'FECHADA'));

-- Saída do consignado só de maleta enviada ou conferida (a conferência por bipe deixou de ser obrigatória, §23.6).
CREATE FUNCTION corona.conferir_maleta_do_consignado() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    situacao text;
BEGIN
    SELECT status INTO situacao FROM corona.remessas WHERE id = NEW.remessa_id;
    IF NEW.tipo = 'CONSIGNADO_SAIDA' AND NEW.estorna_id IS NULL AND situacao NOT IN ('ENVIADA', 'CONFERIDA') THEN
        RAISE EXCEPTION 'A maleta % não está com o representante (situação %): ela não recebe saída de nota.', NEW.remessa_id, situacao
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER movimentos_remessa_maleta_do_consignado BEFORE INSERT ON corona.movimentos_remessa
    FOR EACH ROW EXECUTE FUNCTION corona.conferir_maleta_do_consignado();

-- ---------------------------------------------------------------------------------------------
-- Fechamento da maleta (Corona Gestão; só do servidor): a dona bipa as peças na volta.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.fechamentos_maleta (
    id uuid PRIMARY KEY,
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    iniciado_em timestamptz NOT NULL,
    concluido_em timestamptz,
    situacao text NOT NULL DEFAULT 'EM_ANDAMENTO' CONSTRAINT fechamentos_maleta_situacao_conhecida
        CHECK (situacao IN ('EM_ANDAMENTO', 'CONCLUIDO')),
    iniciado_por uuid NOT NULL REFERENCES corona.usuarios (id),
    concluido_por uuid REFERENCES corona.usuarios (id),
    CONSTRAINT fechamentos_maleta_concluido_confere CHECK (
        (situacao = 'CONCLUIDO') = (concluido_em IS NOT NULL AND concluido_por IS NOT NULL))
);
SELECT corona.aplicar_convencoes_do_servidor('corona.fechamentos_maleta', editavel => true);
COMMENT ON TABLE corona.fechamentos_maleta IS 'Fechamento da maleta: a dona bipa as peças na volta (ADR-0016, item 14). Só do servidor.';
CREATE UNIQUE INDEX fechamentos_maleta_um_em_andamento ON corona.fechamentos_maleta (remessa_id) WHERE situacao = 'EM_ANDAMENTO';
CREATE UNIQUE INDEX fechamentos_maleta_um_concluido ON corona.fechamentos_maleta (remessa_id) WHERE situacao = 'CONCLUIDO';

CREATE TABLE corona.leituras_fechamento_maleta (
    id uuid PRIMARY KEY,
    fechamento_id uuid NOT NULL REFERENCES corona.fechamentos_maleta (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    quantidade integer NOT NULL CONSTRAINT leituras_fechamento_maleta_quantidade_positiva CHECK (quantidade > 0),
    lida_em timestamptz NOT NULL
);
SELECT corona.aplicar_convencoes_do_servidor('corona.leituras_fechamento_maleta', editavel => false);
COMMENT ON TABLE corona.leituras_fechamento_maleta IS 'Bipes do Fechamento da maleta (imutável). Só do servidor.';
CREATE INDEX leituras_fechamento_maleta_por_fechamento ON corona.leituras_fechamento_maleta (fechamento_id);

REVOKE ALL ON corona.fechamentos_maleta, corona.leituras_fechamento_maleta FROM PUBLIC;
