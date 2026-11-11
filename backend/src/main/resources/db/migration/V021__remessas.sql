-- V021: remessas (maletas), itens e movimentos (Fase 3, tarefa 3.2; ESPECIFICACAO §7.1 e §9; ADR-0010).
--
-- Remessa = a maleta que a dona monta e entrega ao vendedor. Rascunho não mexe no estoque; só o envio baixa o
-- estoque central (pelo serviço de estoque, ADR-0008). Dinheiro em NUMERIC(14,2). Datas em UTC.
-- Sincronizáveis porque o vendedor passa a ler a própria maleta (Fases 4 e 5); o filtro por vendedor é da Fase 5.
-- O saldo da maleta por peça = qtd_enviada + soma de movimentos_remessa (§7.1); não há movimento de "saldo inicial".

CREATE TABLE corona.remessas (
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    semana_id uuid NOT NULL REFERENCES corona.semanas (id),
    praca_id uuid REFERENCES corona.pracas (id),
    tipo text NOT NULL CONSTRAINT remessas_tipo_conhecido CHECK (tipo IN ('NOVA', 'REPOSICAO')),
    -- Os estados CONFERIDA, EM_ACERTO e ACERTADA existem no banco, mas só as Fases 4 e 7 chegam a eles (ADR-0010, item 2).
    status text NOT NULL DEFAULT 'RASCUNHO' CONSTRAINT remessas_status_conhecido
        CHECK (status IN ('RASCUNHO', 'ENVIADA', 'CONFERIDA', 'EM_ACERTO', 'ACERTADA', 'CANCELADA')),
    -- Vazio nesta fase: só a conferência da Fase 4 preenche (ADR-0010, item 10).
    dispositivo_id uuid,
    -- Calculados pelo servidor: no rascunho, a preço de venda de hoje; a partir do envio, congelados.
    total_pecas integer NOT NULL DEFAULT 0 CONSTRAINT remessas_total_pecas_nao_negativo CHECK (total_pecas >= 0),
    total_valor numeric(14, 2) NOT NULL DEFAULT 0 CONSTRAINT remessas_total_valor_nao_negativo CHECK (total_valor >= 0),
    criada_por uuid NOT NULL REFERENCES corona.usuarios (id),
    enviada_em timestamptz,
    enviada_por uuid REFERENCES corona.usuarios (id),
    motivo_cancelamento text CONSTRAINT remessas_motivo_preenchido CHECK (motivo_cancelamento IS NULL OR btrim(motivo_cancelamento) <> ''),
    CONSTRAINT remessas_envio_completo CHECK (
        status = 'CANCELADA' OR (status = 'RASCUNHO' AND enviada_em IS NULL) OR (status <> 'RASCUNHO' AND enviada_em IS NOT NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.remessas');
COMMENT ON TABLE corona.remessas IS
    'Maleta do vendedor (ADR-0010). Rascunho é editável e não mexe no estoque; depois do envio o conteúdo é imutável (gatilho) e a correção é estorno.';
CREATE INDEX remessas_por_vendedor ON corona.remessas (vendedor_id, status) WHERE deleted_at IS NULL;
CREATE INDEX remessas_por_semana ON corona.remessas (semana_id) WHERE deleted_at IS NULL;

CREATE TABLE corona.itens_remessa (
    id uuid PRIMARY KEY,
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    qtd_enviada integer NOT NULL CONSTRAINT itens_remessa_qtd_positiva CHECK (qtd_enviada > 0),
    -- Preço de venda vigente no momento do envio (ADR-0010, item 6). Vazio no rascunho.
    preco_remessa numeric(14, 2) CONSTRAINT itens_remessa_preco_nao_negativo CHECK (preco_remessa IS NULL OR preco_remessa >= 0)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.itens_remessa');
COMMENT ON TABLE corona.itens_remessa IS
    'Itens da remessa: uma linha por produto. No rascunho podem ser trocados e tirados (deleted_at); depois do envio, nunca mudam.';
CREATE UNIQUE INDEX itens_remessa_produto_unico ON corona.itens_remessa (remessa_id, produto_id) WHERE deleted_at IS NULL;
CREATE INDEX itens_remessa_por_produto ON corona.itens_remessa (produto_id) WHERE deleted_at IS NULL;

CREATE TABLE corona.movimentos_remessa (
    id uuid PRIMARY KEY,
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    tipo text NOT NULL CONSTRAINT movimentos_remessa_tipo_conhecido
        CHECK (tipo IN ('VENDA', 'ESTORNO_VENDA', 'DEVOLUCAO_CLIENTE', 'TROCA_ENTRADA', 'TROCA_SAIDA', 'PERDA', 'AVARIA',
                        'AJUSTE', 'DEVOLUCAO_DONA')),
    -- Com sinal: o que sai da maleta é negativo, o que volta a ela é positivo. Nunca zero.
    quantidade integer NOT NULL CONSTRAINT movimentos_remessa_quantidade_nao_zero CHECK (quantidade <> 0),
    venda_id uuid,
    dispositivo_id uuid,
    estorna_id uuid REFERENCES corona.movimentos_remessa (id)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.movimentos_remessa', imutavel => true);
COMMENT ON TABLE corona.movimentos_remessa IS
    'Histórico imutável da maleta (ADR-0010, item 5). O id vem do aplicativo e é a chave de idempotência. Corrigir = estorno (estorna_id).';
CREATE UNIQUE INDEX movimentos_remessa_estorno_unico ON corona.movimentos_remessa (estorna_id) WHERE estorna_id IS NOT NULL;
CREATE INDEX movimentos_remessa_por_remessa ON corona.movimentos_remessa (remessa_id, produto_id);

-- Depois do envio o conteúdo da remessa não muda: só o status (e os dados do envio e do cancelamento).
CREATE FUNCTION corona.proteger_remessa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'RASCUNHO' AND (NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id
            OR NEW.semana_id IS DISTINCT FROM OLD.semana_id OR NEW.praca_id IS DISTINCT FROM OLD.praca_id
            OR NEW.tipo IS DISTINCT FROM OLD.tipo OR NEW.total_pecas IS DISTINCT FROM OLD.total_pecas
            OR NEW.total_valor IS DISTINCT FROM OLD.total_valor OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at) THEN
        RAISE EXCEPTION 'O conteúdo da remessa % não pode ser alterado depois do envio: a correção é feita com estorno.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER remessas_conteudo_imutavel BEFORE UPDATE ON corona.remessas
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_remessa();

-- Itens só mudam enquanto a remessa é rascunho; o envio grava o preço congelado ainda como rascunho, na mesma transação.
CREATE FUNCTION corona.proteger_item_de_remessa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    situacao text;
BEGIN
    SELECT status INTO situacao FROM corona.remessas WHERE id = NEW.remessa_id;
    IF situacao IS DISTINCT FROM 'RASCUNHO' THEN
        RAISE EXCEPTION 'Os itens da remessa % não podem mudar depois do envio: a correção é feita com estorno.', NEW.remessa_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER itens_remessa_so_no_rascunho BEFORE INSERT OR UPDATE ON corona.itens_remessa
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_item_de_remessa();

REVOKE ALL ON corona.remessas, corona.itens_remessa, corona.movimentos_remessa FROM PUBLIC;
