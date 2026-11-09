-- Configurações da loja (tarefa 11; especificação §9, ADR-0004 itens 5 e 7). Tabela só do servidor.
-- Uma linha por chave, com o valor numa coluna do tipo certo (dinheiro nunca vira texto). Nesta
-- tarefa só existem as três chaves que ela usa; as outras da §9 chegam com as fases delas.
--
-- Tipo novo de valor (dinheiro NUMERIC(14,2), porcentagem NUMERIC(5,2), texto): uma migração nova
-- acrescenta a coluna (valor_dinheiro, valor_porcentagem, valor_texto), amplia o CHECK do tipo e o
-- CHECK de "a coluna do tipo está preenchida e só ela".

CREATE TABLE corona.configuracoes (
    id uuid PRIMARY KEY,
    chave text NOT NULL
        CONSTRAINT configuracoes_chave_unica UNIQUE
        CONSTRAINT configuracoes_chave_formato CHECK (chave ~ '^[a-z][a-z0-9_]*$'),
    tipo text NOT NULL
        CONSTRAINT configuracoes_tipo_conhecido CHECK (tipo IN ('INTEIRO')),
    valor_inteiro integer,
    CONSTRAINT configuracoes_valor_do_tipo CHECK ((tipo = 'INTEIRO') = (valor_inteiro IS NOT NULL)),
    CONSTRAINT configuracoes_tempo_maximo_offline_dias_faixa
        CHECK (chave <> 'tempo_maximo_offline_dias' OR valor_inteiro BETWEEN 1 AND 365),
    CONSTRAINT configuracoes_tentativas_login_faixa
        CHECK (chave <> 'tentativas_login_antes_do_bloqueio' OR valor_inteiro BETWEEN 1 AND 100),
    CONSTRAINT configuracoes_tempo_bloqueio_login_faixa
        CHECK (chave <> 'tempo_bloqueio_login_minutos' OR valor_inteiro BETWEEN 1 AND 1440)
);
COMMENT ON TABLE corona.configuracoes IS
    'Só do servidor: configurações da loja da §9, uma linha por chave. Chave nova só por migração (a aplicação lê e altera, não insere). ADR-0004, itens 5 e 7.';
COMMENT ON COLUMN corona.configuracoes.tipo IS
    'Qual coluna de valor vale para esta chave. Hoje só INTEIRO; tipos novos chegam por migração com a coluna deles.';
SELECT corona.aplicar_convencoes_do_servidor('corona.configuracoes', editavel => true);

INSERT INTO corona.configuracoes (id, chave, tipo, valor_inteiro) VALUES
    (uuidv7(), 'tempo_maximo_offline_dias', 'INTEIRO', 10),
    (uuidv7(), 'tentativas_login_antes_do_bloqueio', 'INTEIRO', 5),
    (uuidv7(), 'tempo_bloqueio_login_minutos', 'INTEIRO', 15);

-- A aplicação lê e altera os valores, mas não cria chaves (mesmo padrão de "perfis" na V004).
DO $$
DECLARE
    papel text;
BEGIN
    FOR papel IN
        SELECT DISTINCT a.grantee::regrole::text
          FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
         WHERE c.oid = 'corona.configuracoes'::regclass AND a.grantee <> c.relowner AND a.grantee <> 0
    LOOP
        EXECUTE format('REVOKE INSERT, DELETE, TRUNCATE ON corona.configuracoes FROM %s', papel);
    END LOOP;
END;
$$;
REVOKE ALL ON corona.configuracoes FROM PUBLIC;
