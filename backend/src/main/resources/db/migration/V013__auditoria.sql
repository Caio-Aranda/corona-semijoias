-- Auditoria (Fase 1, tarefa 1.4; ESPECIFICACAO §9 e §11). Tabela de histórico só do servidor: só
-- inserção, nada é alterado nem apagado (ADR-0002). Quem fez, o quê, em qual registro, com o antes e
-- o depois. A origem diz de onde veio a ação: API (uma pessoa logada) ou COMANDO (comando do servidor,
-- sem pessoa logada, mostrado como "pelo servidor").

CREATE TABLE corona.auditoria (
    id uuid PRIMARY KEY,
    ator_usuario_id uuid REFERENCES corona.usuarios (id),
    origem text NOT NULL
        CONSTRAINT auditoria_origem_conhecida CHECK (origem IN ('API', 'COMANDO')),
    acao text NOT NULL
        CONSTRAINT auditoria_acao_formato CHECK (acao ~ '^[A-Z][A-Z_]*$'),
    tabela text NOT NULL,
    registro_id uuid NOT NULL,
    antes jsonb,
    depois jsonb,
    ocorrida_em timestamptz NOT NULL,
    CONSTRAINT auditoria_ator_combina_com_origem CHECK ((origem = 'COMANDO') = (ator_usuario_id IS NULL))
);
COMMENT ON TABLE corona.auditoria IS
    'Só do servidor, histórico (só inserção): quem fez qual ação em qual registro, com o antes e o depois. Nunca guarda senha nem hash. Origem COMANDO = comando do servidor, sem pessoa logada (ator vazio).';
COMMENT ON COLUMN corona.auditoria.ator_usuario_id IS
    'Quem fez. Vazio só quando a origem é COMANDO ("pelo servidor").';
COMMENT ON COLUMN corona.auditoria.acao IS
    'Verbo no passado em maiúsculas, por exemplo USUARIO_CRIADO, USUARIO_DESATIVADO, SENHA_REDEFINIDA.';
COMMENT ON COLUMN corona.auditoria.antes IS
    'Só os campos que importam, antes da ação (vazio ao criar). Nunca a senha nem o hash.';
SELECT corona.aplicar_convencoes_do_servidor('corona.auditoria', editavel => false);

CREATE INDEX auditoria_por_registro ON corona.auditoria (tabela, registro_id, ocorrida_em);
CREATE INDEX auditoria_por_ator ON corona.auditoria (ator_usuario_id, ocorrida_em);

REVOKE ALL ON corona.auditoria FROM PUBLIC;

-- Dois motivos novos de revogação de sessão: a dona desativou o usuário; a dona mudou o tipo de acesso
-- ou o vendedor dele. (A V007 já foi publicada: o CHECK é refeito aqui.)
ALTER TABLE corona.sessoes DROP CONSTRAINT sessoes_motivo_conhecido;
ALTER TABLE corona.sessoes ADD CONSTRAINT sessoes_motivo_conhecido CHECK (motivo_da_revogacao IN (
    'SAIDA', 'REUSO_FORA_DA_JANELA', 'SEGUNDA_REAPRESENTACAO', 'ANTERIOR_DEPOIS_DO_SUCESSOR',
    'OUTRO_APARELHO', 'SENHA_REDEFINIDA', 'SENHA_REDEFINIDA_PELO_SERVIDOR', 'SENHA_TROCADA',
    'USUARIO_DESATIVADO', 'ACESSO_ALTERADO'));
