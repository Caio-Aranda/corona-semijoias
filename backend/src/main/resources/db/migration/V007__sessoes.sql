-- Sessão longa (tarefa 11; ADR-0004, item 2). Duas tabelas só do servidor, nada é apagado (ADR-0002).
-- As horas vêm do relógio da aplicação (o mesmo que emite o token), não do now() do banco.

-- ---------------------------------------------------------------------------------------------
-- A família da sessão: nasce no login, o id é o "sid" do token de acesso. Revogar a família derruba
-- todos os tokens dela (de acesso e de renovação) no próximo uso.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.sessoes (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL
        CONSTRAINT sessoes_usuario_existe REFERENCES corona.usuarios (id),
    tipo text NOT NULL
        CONSTRAINT sessoes_tipo_conhecido CHECK (tipo IN ('NORMAL', 'TROCA_SENHA')),
    dispositivo_id uuid,
    criada_em timestamptz NOT NULL,
    usada_em timestamptz NOT NULL,
    revogada_em timestamptz,
    motivo_da_revogacao text
        CONSTRAINT sessoes_motivo_conhecido CHECK (motivo_da_revogacao IN (
            'SAIDA', 'REUSO_FORA_DA_JANELA', 'SEGUNDA_REAPRESENTACAO', 'ANTERIOR_DEPOIS_DO_SUCESSOR',
            'OUTRO_APARELHO', 'SENHA_REDEFINIDA', 'SENHA_REDEFINIDA_PELO_SERVIDOR', 'SENHA_TROCADA')),
    CONSTRAINT sessoes_revogacao_com_motivo CHECK ((revogada_em IS NULL) = (motivo_da_revogacao IS NULL))
);
COMMENT ON TABLE corona.sessoes IS
    'Só do servidor: a família de uma sessão (id = sid do token). NORMAL tem tokens de renovação; TROCA_SENHA é a do token restrito da troca obrigatória, sem renovação. ADR-0004, item 2.';
COMMENT ON COLUMN corona.sessoes.dispositivo_id IS
    'Identificador do aparelho do Corona Vendas (ADR-0004, item 4). Vazio no Corona Gestão. Na Fase 0 só é registrado.';
COMMENT ON COLUMN corona.sessoes.usada_em IS
    'Última renovação (ou a criação). Os 30 dias contam de cada renovação.';
SELECT corona.aplicar_convencoes_do_servidor('corona.sessoes', editavel => true);
CREATE INDEX sessoes_abertas_do_usuario ON corona.sessoes (usuario_id) WHERE revogada_em IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Um registro por token de renovação. Só o SHA-256 do valor é guardado. O sucessor é derivado
-- (HMAC-SHA256 com o segredo do .env sobre o id do registro filho), então o servidor consegue
-- devolver o mesmo sucessor dentro da janela de tolerância sem guardar o valor.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE corona.tokens_de_renovacao (
    id uuid PRIMARY KEY,
    sessao_id uuid NOT NULL
        CONSTRAINT tokens_de_renovacao_sessao_existe REFERENCES corona.sessoes (id),
    anterior_id uuid
        CONSTRAINT tokens_de_renovacao_um_sucessor_so UNIQUE
        CONSTRAINT tokens_de_renovacao_anterior_existe REFERENCES corona.tokens_de_renovacao (id),
    hash bytea NOT NULL
        CONSTRAINT tokens_de_renovacao_hash_unico UNIQUE
        CONSTRAINT tokens_de_renovacao_hash_sha256 CHECK (length(hash) = 32),
    criado_em timestamptz NOT NULL,
    vence_em timestamptz NOT NULL,
    usado_em timestamptz,
    reapresentado_em timestamptz,
    CONSTRAINT tokens_de_renovacao_vence_depois CHECK (vence_em > criado_em),
    CONSTRAINT tokens_de_renovacao_reapresentado_depois_de_usado
        CHECK (reapresentado_em IS NULL OR usado_em IS NOT NULL)
);
COMMENT ON TABLE corona.tokens_de_renovacao IS
    'Só do servidor: cada token de renovação de uma sessão. anterior_id é único: um token tem no máximo um sucessor. ADR-0004, item 2.';
COMMENT ON COLUMN corona.tokens_de_renovacao.hash IS
    'SHA-256 do valor entregue ao aplicativo. O valor em si nunca é guardado.';
COMMENT ON COLUMN corona.tokens_de_renovacao.usado_em IS
    'Quando este token foi trocado pelo sucessor. Daí conta a janela de tolerância (2 minutos).';
COMMENT ON COLUMN corona.tokens_de_renovacao.reapresentado_em IS
    'A única reapresentação aceita, dentro da janela, que devolveu o mesmo sucessor. Uma segunda revoga a família.';
SELECT corona.aplicar_convencoes_do_servidor('corona.tokens_de_renovacao', editavel => true);
CREATE INDEX tokens_de_renovacao_da_sessao ON corona.tokens_de_renovacao (sessao_id);

REVOKE ALL ON corona.sessoes FROM PUBLIC;
REVOKE ALL ON corona.tokens_de_renovacao FROM PUBLIC;

-- A V005 não pode ser editada: o comentário da coluna é atualizado aqui.
COMMENT ON COLUMN corona.usuarios.credencial_alterada_em IS
    'Última redefinição ou troca de senha (UTC); NULL = nunca. Token de acesso emitido antes dela é recusado. Segunda barreira: a redefinição e a troca também revogam todas as sessões do usuário (tabela sessoes, tarefa 11).';
