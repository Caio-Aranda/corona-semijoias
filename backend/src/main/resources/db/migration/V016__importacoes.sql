-- V016: resultado das importações em massa (Fase 1, tarefa 1.13; ESPECIFICACAO §12, "Resultado das
-- importações"). Cada importação de planilha ou de fotos vira um lote: quem fez, quando, e o que
-- aconteceu com cada linha (criada, atualizada, já existia ou recusada, com o motivo).
--
-- Só do servidor e só inserção (histórico, ADR-0002): o lote não se edita nem se apaga. O id do lote é
-- gerado pelo aplicativo, então repetir o envio do mesmo lote não duplica nada.

CREATE TABLE corona.importacoes (
    id uuid PRIMARY KEY,
    tipo text NOT NULL CONSTRAINT importacoes_tipo_conhecido CHECK (tipo IN ('PLANILHA', 'FOTOS')),
    arquivo_nome text,
    usuario_id uuid NOT NULL REFERENCES corona.usuarios (id)
);
COMMENT ON TABLE corona.importacoes IS
    'Só do servidor, histórico (só inserção): um lote de importação em massa (planilha de produtos ou fotos), com quem importou. O quando é o created_at.';
SELECT corona.aplicar_convencoes_do_servidor('corona.importacoes', editavel => false);

CREATE TABLE corona.importacoes_linhas (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    importacao_id uuid NOT NULL REFERENCES corona.importacoes (id),
    ordem integer NOT NULL CONSTRAINT importacoes_linhas_ordem_nao_negativa CHECK (ordem >= 0),
    -- O que a pessoa reconhece: "Linha 12" na planilha, o nome do arquivo na foto.
    referencia text NOT NULL CONSTRAINT importacoes_linhas_referencia_preenchida CHECK (btrim(referencia) <> ''),
    resultado text NOT NULL CONSTRAINT importacoes_linhas_resultado_conhecido
        CHECK (resultado IN ('CRIADO', 'ATUALIZADO', 'JA_EXISTIA', 'RECUSADO')),
    motivo text,
    CONSTRAINT importacoes_linhas_ordem_unica UNIQUE (importacao_id, ordem)
);
COMMENT ON TABLE corona.importacoes_linhas IS
    'Só do servidor, histórico (só inserção): o que aconteceu com cada linha ou foto de um lote de importação.';
SELECT corona.aplicar_convencoes_do_servidor('corona.importacoes_linhas', editavel => false);
CREATE INDEX importacoes_linhas_por_lote ON corona.importacoes_linhas (importacao_id, ordem);

REVOKE ALL ON corona.importacoes FROM PUBLIC;
REVOKE ALL ON corona.importacoes_linhas FROM PUBLIC;
