-- V049: o cliente do consignado (Leva 9, tarefa 9.1; ADR-0016, itens 4 e 15; ESPECIFICACAO §23.5).
--
-- O cadastro do cliente ganha documento (texto livre: CPF, RG, CI paraguaia...), quem indicou, como conheceu, a comissão
-- do cliente (0% a 40%, padrão 30%) e os hashes das três fotos (cliente, frente e verso do documento). As fotos são
-- ARQUIVOS (nunca binário no PostgreSQL); aqui fica só o hash SHA-256. A coluna antiga cpf e o índice único dela (V030)
-- continuam para os cadastros da dona; o Corona Vendas passa a gravar em documento.
-- Também nasce a configuração que liga ou desliga o fluxo antigo de acerto com a dona (§7.4): desligada por padrão; nada é
-- apagado, ligar de volta é mudar a configuração.

ALTER TABLE corona.clientes
    ADD COLUMN documento text,
    ADD COLUMN documento_tipo text,
    ADD COLUMN indicado_por_cliente_id uuid REFERENCES corona.clientes (id),
    ADD COLUMN indicado_por_nome text,
    ADD COLUMN como_conheceu text,
    ADD COLUMN comissao_percentual numeric(5, 2) NOT NULL DEFAULT 30,
    ADD COLUMN foto_hash text,
    ADD COLUMN foto_documento_frente_hash text,
    ADD COLUMN foto_documento_verso_hash text;

ALTER TABLE corona.clientes
    ADD CONSTRAINT clientes_documento_tipo_conhecido CHECK (documento_tipo IS NULL OR documento_tipo IN ('CPF', 'RG', 'CI', 'OUTRO')),
    ADD CONSTRAINT clientes_comissao_na_faixa CHECK (comissao_percentual >= 0 AND comissao_percentual <= 40),
    ADD CONSTRAINT clientes_indicado_por_um_so CHECK (indicado_por_cliente_id IS NULL OR indicado_por_nome IS NULL),
    ADD CONSTRAINT clientes_foto_hash_valido CHECK (foto_hash IS NULL OR foto_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT clientes_foto_frente_hash_valido
        CHECK (foto_documento_frente_hash IS NULL OR foto_documento_frente_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT clientes_foto_verso_hash_valido
        CHECK (foto_documento_verso_hash IS NULL OR foto_documento_verso_hash ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN corona.clientes.documento IS 'Documento do cliente, texto livre (CPF, RG, CI paraguaia...). Repetido avisa, não barra (§23.5).';
COMMENT ON COLUMN corona.clientes.comissao_percentual IS 'Comissão do cliente no acerto, de 0 a 40 (padrão 30). Cada acerto grava a que usou.';
COMMENT ON COLUMN corona.clientes.foto_hash IS 'SHA-256 da foto do cliente. O arquivo fica fora do banco (ArmazenamentoDeArquivos, pasta clientes/).';

CREATE INDEX clientes_documento_idx ON corona.clientes (lower(btrim(documento)))
    WHERE documento IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX clientes_por_responsavel ON corona.clientes (vendedor_responsavel_id) WHERE deleted_at IS NULL;

-- A tela esconde Acertos, Comissão por meta, Pendências do acerto e a "semana" da §7.4 quando vale 0 (padrão). Chave
-- INTEIRO 0 ou 1 (a tabela ainda não tem tipo booleano).
INSERT INTO corona.configuracoes (id, chave, tipo, valor_inteiro) VALUES (uuidv7(), 'acerto_do_vendedor_visivel', 'INTEIRO', 0);
ALTER TABLE corona.configuracoes ADD CONSTRAINT configuracoes_acerto_visivel_faixa
    CHECK (chave <> 'acerto_do_vendedor_visivel' OR valor_inteiro IN (0, 1));

-- Cada representante recebe so a sua lista de clientes (ADR-0016, item 4). Como a pertenca nao pode mudar depois de entregue
-- (ADR-0013, item 5, prova 3; o cursor do pull ja passou e o antigo responsavel ficaria com uma copia que nao e mais dele),
-- o responsavel de um cliente, uma vez definido, nao muda (V032). Atribuir a quem ainda nao tinha responsavel continua valendo.
CREATE FUNCTION corona.recusar_mudanca_de_responsavel_do_cliente() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Um cadastro salvo sem responsavel (o formulario da dona) nao tira o responsavel que ja existe.
    IF OLD.vendedor_responsavel_id IS NOT NULL AND NEW.vendedor_responsavel_id IS NULL THEN
        NEW.vendedor_responsavel_id := OLD.vendedor_responsavel_id;
    END IF;
    IF OLD.vendedor_responsavel_id IS NOT NULL AND NEW.vendedor_responsavel_id IS DISTINCT FROM OLD.vendedor_responsavel_id THEN
        RAISE EXCEPTION 'O representante responsavel pelo cliente % nao muda depois de definido (ADR-0016, item 4).', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER corona_pertenca_nao_muda BEFORE UPDATE ON corona.clientes
    FOR EACH ROW EXECUTE FUNCTION corona.recusar_mudanca_de_responsavel_do_cliente();
