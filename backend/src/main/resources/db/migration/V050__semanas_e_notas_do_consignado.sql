-- V050: semana do representante e nota do consignado (Leva 9, tarefa 9.1; ADR-0016, itens 2, 3, 5 e 7; ESPECIFICACAO §23).
--
-- semanas_vendedor: a semana de cada representante (não confundir com corona.semanas, a da dona, §7.4). Só situacao e
-- fechada_em mudam. numeros_da_semana: só do servidor; garante que semana-sequência é único por representante, entre
-- notas e recibos juntos (o número é impresso e assinado: nunca se troca em silêncio).
-- notas_consignado: a nota (DX) nasce completa ao Finalizar (o rascunho é só local); depois só situacao e data_cobranca
-- mudam, e só pelo servidor. Itens, reposições, trocas e remarcações são imutáveis: a nota "cresce" por evento.
-- Dinheiro em NUMERIC(14,2), tudo em Real (ADR-0006); datas em UTC; data_cobranca é o dia do calendário de Brasília.

CREATE TABLE corona.semanas_vendedor (
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    numero integer NOT NULL CONSTRAINT semanas_vendedor_numero_positivo CHECK (numero >= 1),
    aberta_em timestamptz NOT NULL,
    fechada_em timestamptz,
    situacao text NOT NULL DEFAULT 'ABERTA' CONSTRAINT semanas_vendedor_situacao_conhecida CHECK (situacao IN ('ABERTA', 'FECHADA')),
    dispositivo_id uuid,
    CONSTRAINT semanas_vendedor_fechada_confere CHECK ((situacao = 'FECHADA') = (fechada_em IS NOT NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.semanas_vendedor');
COMMENT ON TABLE corona.semanas_vendedor IS
    'Semana do representante (ADR-0016, item 2). Numeração por vendedor. Só situacao e fechada_em mudam. A semana da dona (§7.4) é corona.semanas.';
CREATE UNIQUE INDEX semanas_vendedor_numero_unico ON corona.semanas_vendedor (vendedor_id, numero);
CREATE UNIQUE INDEX semanas_vendedor_uma_aberta ON corona.semanas_vendedor (vendedor_id) WHERE situacao = 'ABERTA';

CREATE FUNCTION corona.proteger_semana_do_vendedor() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.numero IS DISTINCT FROM OLD.numero
            OR NEW.aberta_em IS DISTINCT FROM OLD.aberta_em OR NEW.dispositivo_id IS DISTINCT FROM OLD.dispositivo_id
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'A semana % só muda a situação e a hora do fechamento.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.situacao = 'FECHADA' AND (NEW.situacao IS DISTINCT FROM OLD.situacao OR NEW.fechada_em IS DISTINCT FROM OLD.fechada_em) THEN
        RAISE EXCEPTION 'A semana % já foi fechada e não abre de novo.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER semanas_vendedor_conteudo_imutavel BEFORE UPDATE ON corona.semanas_vendedor
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_semana_do_vendedor();

-- Número semana-sequência de notas e recibos (só do servidor, só inserção).
CREATE TABLE corona.numeros_da_semana (
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    semana_id uuid NOT NULL REFERENCES corona.semanas_vendedor (id),
    sequencia integer NOT NULL CONSTRAINT numeros_da_semana_sequencia_positiva CHECK (sequencia >= 1),
    tipo text NOT NULL CONSTRAINT numeros_da_semana_tipo_conhecido CHECK (tipo IN ('NOTA', 'RECIBO')),
    registro_id uuid NOT NULL
);
SELECT corona.aplicar_convencoes_do_servidor('corona.numeros_da_semana', editavel => false);
COMMENT ON TABLE corona.numeros_da_semana IS
    'Um número semana-sequência por representante, compartilhado por notas e recibos (ADR-0016, item 3). Só do servidor.';
CREATE UNIQUE INDEX numeros_da_semana_unico ON corona.numeros_da_semana (vendedor_id, semana_id, sequencia);
CREATE UNIQUE INDEX numeros_da_semana_registro_unico ON corona.numeros_da_semana (registro_id);

-- ---------------------------------------------------------------------------------------------
-- A nota (pedido). Só situacao e data_cobranca mudam, pelo servidor.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.notas_consignado (
    id uuid PRIMARY KEY,
    vendedor_id uuid NOT NULL REFERENCES corona.vendedores (id),
    semana_id uuid NOT NULL REFERENCES corona.semanas_vendedor (id),
    sequencia integer NOT NULL CONSTRAINT notas_consignado_sequencia_positiva CHECK (sequencia >= 1),
    cliente_id uuid NOT NULL REFERENCES corona.clientes (id),
    saida_em timestamptz NOT NULL,
    data_cobranca date NOT NULL,
    nota_origem_id uuid REFERENCES corona.notas_consignado (id),
    situacao text NOT NULL DEFAULT 'EM_ABERTO'
        CONSTRAINT notas_consignado_situacao_conhecida CHECK (situacao IN ('EM_ABERTO', 'PENDENTE', 'FECHADO', 'REMARCADO')),
    cotacao_id uuid REFERENCES corona.cotacoes (id),
    dispositivo_id uuid,
    hora_ajustada boolean NOT NULL DEFAULT false
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.notas_consignado');
COMMENT ON TABLE corona.notas_consignado IS
    'Nota de saída (DX) do consignado ao cliente (ADR-0016, item 5). Nasce completa; só situacao e data_cobranca mudam, pelo servidor.';
CREATE INDEX notas_consignado_por_cliente ON corona.notas_consignado (cliente_id) WHERE deleted_at IS NULL;
CREATE INDEX notas_consignado_por_semana ON corona.notas_consignado (vendedor_id, semana_id) WHERE deleted_at IS NULL;

CREATE FUNCTION corona.proteger_nota_consignado() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.vendedor_id IS DISTINCT FROM OLD.vendedor_id OR NEW.semana_id IS DISTINCT FROM OLD.semana_id
            OR NEW.sequencia IS DISTINCT FROM OLD.sequencia OR NEW.cliente_id IS DISTINCT FROM OLD.cliente_id
            OR NEW.saida_em IS DISTINCT FROM OLD.saida_em OR NEW.nota_origem_id IS DISTINCT FROM OLD.nota_origem_id
            OR NEW.cotacao_id IS DISTINCT FROM OLD.cotacao_id OR NEW.dispositivo_id IS DISTINCT FROM OLD.dispositivo_id
            OR NEW.hora_ajustada IS DISTINCT FROM OLD.hora_ajustada OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'A nota % só muda a situação e a data de cobrança: a correção é feita com estorno.', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER notas_consignado_conteudo_imutavel BEFORE UPDATE ON corona.notas_consignado
    FOR EACH ROW EXECUTE FUNCTION corona.proteger_nota_consignado();

CREATE TABLE corona.reposicoes_nota (
    id uuid PRIMARY KEY,
    nota_id uuid NOT NULL REFERENCES corona.notas_consignado (id),
    feita_em timestamptz NOT NULL,
    dispositivo_id uuid,
    hora_ajustada boolean NOT NULL DEFAULT false
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.reposicoes_nota', imutavel => true);
COMMENT ON TABLE corona.reposicoes_nota IS 'Reposição na semana atual: peças novas na mesma nota (ADR-0016, item 7). Imutável.';
CREATE INDEX reposicoes_nota_por_nota ON corona.reposicoes_nota (nota_id);

CREATE TABLE corona.trocas_nota (
    id uuid PRIMARY KEY,
    nota_id uuid NOT NULL REFERENCES corona.notas_consignado (id),
    trocada_em timestamptz NOT NULL,
    dispositivo_id uuid,
    hora_ajustada boolean NOT NULL DEFAULT false
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.trocas_nota', imutavel => true);
COMMENT ON TABLE corona.trocas_nota IS 'Troca de uma peça dentro da nota, sem garantia (ADR-0016, item 7). Imutável.';
CREATE INDEX trocas_nota_por_nota ON corona.trocas_nota (nota_id);

CREATE TABLE corona.itens_nota_consignado (
    id uuid PRIMARY KEY,
    nota_id uuid NOT NULL REFERENCES corona.notas_consignado (id),
    produto_id uuid NOT NULL REFERENCES corona.produtos (id),
    remessa_id uuid NOT NULL REFERENCES corona.remessas (id),
    origem text NOT NULL CONSTRAINT itens_nota_consignado_origem_conhecida
        CHECK (origem IN ('SAIDA', 'REPOSICAO', 'TROCA_VOLTA', 'TROCA_ENTRADA')),
    quantidade integer NOT NULL CONSTRAINT itens_nota_consignado_quantidade_nao_zero CHECK (quantidade <> 0),
    preco_unitario numeric(14, 2) NOT NULL CONSTRAINT itens_nota_consignado_preco_nao_negativo CHECK (preco_unitario >= 0),
    reposicao_id uuid REFERENCES corona.reposicoes_nota (id),
    troca_id uuid REFERENCES corona.trocas_nota (id),
    CONSTRAINT itens_nota_consignado_sinal CHECK ((origem = 'TROCA_VOLTA') = (quantidade < 0)),
    CONSTRAINT itens_nota_consignado_evento CHECK (
        (origem IN ('SAIDA') AND reposicao_id IS NULL AND troca_id IS NULL)
        OR (origem = 'REPOSICAO' AND reposicao_id IS NOT NULL AND troca_id IS NULL)
        OR (origem IN ('TROCA_VOLTA', 'TROCA_ENTRADA') AND troca_id IS NOT NULL AND reposicao_id IS NULL))
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.itens_nota_consignado', imutavel => true);
COMMENT ON TABLE corona.itens_nota_consignado IS
    'Itens da nota (DX por produto e maleta = soma das quantidades). Imutável; quantidade negativa só em TROCA_VOLTA (ADR-0016, item 5).';
CREATE INDEX itens_nota_consignado_por_nota ON corona.itens_nota_consignado (nota_id);
CREATE INDEX itens_nota_consignado_por_maleta ON corona.itens_nota_consignado (remessa_id, produto_id);

CREATE TABLE corona.remarcacoes_nota (
    id uuid PRIMARY KEY,
    nota_id uuid NOT NULL REFERENCES corona.notas_consignado (id),
    data_cobranca_anterior date NOT NULL,
    data_cobranca_nova date NOT NULL,
    remarcada_em timestamptz NOT NULL,
    dispositivo_id uuid,
    hora_ajustada boolean NOT NULL DEFAULT false
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.remarcacoes_nota', imutavel => true);
COMMENT ON TABLE corona.remarcacoes_nota IS 'Remarcação da data de cobrança: registro imutável; o servidor copia a data nova para a nota.';
CREATE INDEX remarcacoes_nota_por_nota ON corona.remarcacoes_nota (nota_id);

REVOKE ALL ON corona.semanas_vendedor, corona.numeros_da_semana, corona.notas_consignado, corona.reposicoes_nota,
    corona.trocas_nota, corona.itens_nota_consignado, corona.remarcacoes_nota FROM PUBLIC;
