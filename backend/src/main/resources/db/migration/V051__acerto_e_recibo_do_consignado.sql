-- V051: acerto da nota, recibo, aplicações e alertas do consignado (Leva 9, tarefa 9.1; ADR-0016, itens 8, 10, 11 e 12).
--
-- O acerto (acertos_nota) é um DÉBITO do cliente no valor do bruto; a pendência do cliente é a soma dos saldos dos débitos, e
-- o saldo é bruto − Σ aplicações ativas (aplicacoes_recibo, imutáveis). Nada se edita: acerto e recibo só ganham a marca do
-- estorno (estornado_em, estorno_id, motivo_estorno), uma vez; o dinheiro volta por aplicações negativas.
-- Dinheiro em NUMERIC(14,2), tudo em Real (ADR-0006). O servidor recalcula tudo; o banco confere as contas como segunda trava.

CREATE TABLE corona.acertos_nota (
    id uuid PRIMARY KEY,
    nota_id uuid NOT NULL REFERENCES corona.notas_consignado (id),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    semana_id uuid NOT NULL REFERENCES corona.semanas_vendedor (id),
    acertado_em timestamptz NOT NULL,
    comissao_percentual numeric(5, 2) NOT NULL CONSTRAINT acertos_nota_comissao_na_faixa
        CHECK (comissao_percentual >= 0 AND comissao_percentual <= 40),
    itens_dx integer NOT NULL CONSTRAINT acertos_nota_itens_dx_nao_negativo CHECK (itens_dx >= 0),
    itens_dv integer NOT NULL CONSTRAINT acertos_nota_itens_dv_nao_negativo CHECK (itens_dv >= 0),
    itens_vd integer NOT NULL,
    valor_dx numeric(14, 2) NOT NULL CONSTRAINT acertos_nota_valor_dx_nao_negativo CHECK (valor_dx >= 0),
    valor_dv numeric(14, 2) NOT NULL CONSTRAINT acertos_nota_valor_dv_nao_negativo CHECK (valor_dv >= 0),
    valor_vd numeric(14, 2) NOT NULL,
    comissao numeric(14, 2) NOT NULL CONSTRAINT acertos_nota_comissao_nao_negativa CHECK (comissao >= 0),
    bruto numeric(14, 2) NOT NULL CONSTRAINT acertos_nota_bruto_nao_negativo CHECK (bruto >= 0),
    pendencias_anteriores numeric(14, 2) NOT NULL CONSTRAINT acertos_nota_pendencias_nao_negativas CHECK (pendencias_anteriores >= 0),
    total_a_receber numeric(14, 2) NOT NULL,
    total_pago numeric(14, 2) NOT NULL CONSTRAINT acertos_nota_pago_nao_negativo CHECK (total_pago >= 0),
    ficou_devendo numeric(14, 2) NOT NULL CONSTRAINT acertos_nota_devendo_nao_negativo CHECK (ficou_devendo >= 0),
    recibo_id uuid,
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    dispositivo_id uuid,
    hora_ajustada boolean NOT NULL DEFAULT false,
    estornado_em timestamptz,
    estorno_id uuid,
    motivo_estorno text,
    CONSTRAINT acertos_nota_contas_conferem CHECK (
        itens_vd = itens_dx - itens_dv AND itens_vd >= 0
        AND valor_vd = valor_dx - valor_dv AND valor_vd >= 0
        AND bruto = valor_vd - comissao
        AND total_a_receber = bruto + pendencias_anteriores
        AND ficou_devendo = greatest(total_a_receber - total_pago, 0)),
    CONSTRAINT acertos_nota_estorno_completo CHECK (
        (estornado_em IS NULL AND estorno_id IS NULL AND motivo_estorno IS NULL)
        OR (estornado_em IS NOT NULL AND estorno_id IS NOT NULL AND btrim(motivo_estorno) <> ''))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.acertos_nota');
COMMENT ON TABLE corona.acertos_nota IS
    'Acerto da nota (ADR-0016, item 8): o débito do cliente é o bruto. Imutável salvo a marca do estorno, uma vez.';
CREATE UNIQUE INDEX acertos_nota_um_ativo_por_nota ON corona.acertos_nota (nota_id) WHERE estornado_em IS NULL;
CREATE INDEX acertos_nota_por_vendedor ON corona.acertos_nota (vendedor_id, semana_id);

CREATE FUNCTION corona.proteger_acerto_nota() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.nota_id IS DISTINCT FROM OLD.nota_id OR NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id
            OR NEW.semana_id IS DISTINCT FROM OLD.semana_id OR NEW.acertado_em IS DISTINCT FROM OLD.acertado_em
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
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.acertos_nota
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_acerto_nota();

CREATE TABLE corona.itens_acerto_nota (
    id uuid PRIMARY KEY,
    acerto_id uuid NOT NULL REFERENCES corona.acertos_nota (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    qtd_dx integer NOT NULL CONSTRAINT itens_acerto_nota_dx_nao_negativo CHECK (qtd_dx >= 0),
    qtd_dv integer NOT NULL CONSTRAINT itens_acerto_nota_dv_nao_negativo CHECK (qtd_dv >= 0),
    preco_unitario numeric(14, 2) NOT NULL CONSTRAINT itens_acerto_nota_preco_nao_negativo CHECK (preco_unitario >= 0),
    CONSTRAINT itens_acerto_nota_dv_cabe_no_dx CHECK (qtd_dv <= qtd_dx)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.itens_acerto_nota', imutavel => true);
COMMENT ON TABLE corona.itens_acerto_nota IS 'Uma linha por produto e maleta do acerto: DX, DV e preço da maleta. VD = DX − DV. Imutável.';
CREATE INDEX itens_acerto_nota_por_acerto ON corona.itens_acerto_nota (acerto_id);

-- ---------------------------------------------------------------------------------------------
-- Recibo: um pagamento do cliente (no acerto ou lançado à parte), em uma ou mais formas.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.recibos_consignado (
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    cliente_id uuid NOT NULL REFERENCES corona.clientes (id),
    semana_id uuid NOT NULL REFERENCES corona.semanas_vendedor (id),
    sequencia integer NOT NULL CONSTRAINT recibos_consignado_sequencia_positiva CHECK (sequencia >= 1),
    acerto_id uuid REFERENCES corona.acertos_nota (id),
    pago_em timestamptz NOT NULL,
    total numeric(14, 2) NOT NULL CONSTRAINT recibos_consignado_total_positivo CHECK (total > 0),
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    dispositivo_id uuid,
    hora_ajustada boolean NOT NULL DEFAULT false,
    estornado_em timestamptz,
    estorno_id uuid,
    motivo_estorno text,
    CONSTRAINT recibos_consignado_estorno_completo CHECK (
        (estornado_em IS NULL AND estorno_id IS NULL AND motivo_estorno IS NULL)
        OR (estornado_em IS NOT NULL AND estorno_id IS NOT NULL AND btrim(motivo_estorno) <> ''))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.recibos_consignado');
COMMENT ON TABLE corona.recibos_consignado IS
    'Recibo de pagamento do cliente (ADR-0016, item 10). Imutável salvo a marca do estorno, uma vez.';
CREATE INDEX recibos_consignado_por_cliente ON corona.recibos_consignado (cliente_id);
CREATE UNIQUE INDEX recibos_consignado_um_por_acerto ON corona.recibos_consignado (acerto_id) WHERE acerto_id IS NOT NULL;

-- O acerto e o recibo dele nascem juntos (um aponta para o outro): a FK do acerto é conferida no COMMIT.
ALTER TABLE corona.acertos_nota ADD CONSTRAINT acertos_nota_recibo_fk FOREIGN KEY (recibo_id)
    REFERENCES corona.recibos_consignado (id) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION corona.proteger_recibo_consignado() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
            OR NEW.semana_id IS DISTINCT FROM OLD.semana_id OR NEW.sequencia IS DISTINCT FROM OLD.sequencia
            OR NEW.acerto_id IS DISTINCT FROM OLD.acerto_id OR NEW.pago_em IS DISTINCT FROM OLD.pago_em
            OR NEW.total IS DISTINCT FROM OLD.total OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id
            OR NEW.dispositivo_id IS DISTINCT FROM OLD.dispositivo_id OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'O recibo % não pode ser alterado: a correção é feita com estorno.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.estornado_em IS NOT NULL AND (NEW.estornado_em IS DISTINCT FROM OLD.estornado_em
            OR NEW.estorno_id IS DISTINCT FROM OLD.estorno_id OR NEW.motivo_estorno IS DISTINCT FROM OLD.motivo_estorno) THEN
        RAISE EXCEPTION 'O recibo % já foi estornado e não muda mais.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.recibos_consignado
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_recibo_consignado();

CREATE TABLE corona.formas_recibo_consignado (
    id uuid PRIMARY KEY,
    recibo_id uuid NOT NULL REFERENCES corona.recibos_consignado (id),
    forma text NOT NULL CONSTRAINT formas_recibo_consignado_forma_conhecida CHECK (forma IN ('DINHEIRO', 'PIX', 'DEBITO', 'CREDITO')),
    valor numeric(14, 2) NOT NULL CONSTRAINT formas_recibo_consignado_valor_positivo CHECK (valor > 0)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.formas_recibo_consignado', imutavel => true);
COMMENT ON TABLE corona.formas_recibo_consignado IS
    'Uma linha por forma de pagamento do recibo. Sem cheque, boleto, entrada, crédito parcelado ou desconto (§23.8).';
CREATE UNIQUE INDEX formas_recibo_consignado_uma_por_forma ON corona.formas_recibo_consignado (recibo_id, forma);

-- Aplicação: quanto de um recibo quita de um débito (um acerto). Negativa só no estorno.
CREATE TABLE corona.aplicacoes_recibo (
    id uuid PRIMARY KEY,
    recibo_id uuid NOT NULL REFERENCES corona.recibos_consignado (id),
    acerto_id uuid NOT NULL REFERENCES corona.acertos_nota (id),
    valor numeric(14, 2) NOT NULL CONSTRAINT aplicacoes_recibo_valor_nao_zero CHECK (valor <> 0),
    estorna_id uuid REFERENCES corona.aplicacoes_recibo (id),
    CONSTRAINT aplicacoes_recibo_sinal CHECK ((valor > 0 AND estorna_id IS NULL) OR (valor < 0 AND estorna_id IS NOT NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.aplicacoes_recibo', imutavel => true);
COMMENT ON TABLE corona.aplicacoes_recibo IS
    'Quanto cada recibo quitou de cada débito (acerto). A pendência é o saldo disso (ADR-0016, item 10). Imutável; negativa só no estorno.';
CREATE UNIQUE INDEX aplicacoes_recibo_estorno_unico ON corona.aplicacoes_recibo (estorna_id) WHERE estorna_id IS NOT NULL;
CREATE INDEX aplicacoes_recibo_por_acerto ON corona.aplicacoes_recibo (acerto_id);
CREATE INDEX aplicacoes_recibo_por_recibo ON corona.aplicacoes_recibo (recibo_id);

-- Alertas para a dona (só do servidor): guardam o que o app viu, não se calculam depois (ADR-0016, item 11).
CREATE TABLE corona.alertas_consignado (
    id uuid PRIMARY KEY,
    tipo text NOT NULL CONSTRAINT alertas_consignado_tipo_conhecido CHECK (tipo IN (
        'PECA_SEM_SALDO_NA_MALETA', 'PRECO_DIFERENTE_DA_MALETA', 'VALOR_DO_ACERTO_RECALCULADO', 'PAGAMENTO_ACIMA_DO_DEVIDO',
        'DOCUMENTO_REPETIDO', 'ENVIO_REPETIDO_COM_OUTRO_CONTEUDO')),
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    registro_tabela text NOT NULL,
    registro_id uuid NOT NULL,
    detalhe jsonb NOT NULL DEFAULT '{}'::jsonb,
    visto_em timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.alertas_consignado', editavel => true);
COMMENT ON TABLE corona.alertas_consignado IS 'Divergências aceitas do consignado, para a dona (ADR-0016, item 11). Só do servidor.';
CREATE INDEX alertas_consignado_nao_vistos ON corona.alertas_consignado (created_at) WHERE visto_em IS NULL;

REVOKE ALL ON corona.acertos_nota, corona.itens_acerto_nota, corona.recibos_consignado, corona.formas_recibo_consignado,
    corona.aplicacoes_recibo, corona.alertas_consignado FROM PUBLIC;
