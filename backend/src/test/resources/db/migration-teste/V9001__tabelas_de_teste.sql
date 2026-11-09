-- Tabelas que existem SÓ nos testes (pasta db/migration-teste, que nunca entra no jar).
-- Numeração 9000+ para nunca colidir com as migrações reais: o banco dos testes é sempre novo,
-- então as reais (V002, V003, ...) são aplicadas antes destas. O job "migracoes" do CI só olha
-- src/main/resources/db/migration e não vê esta pasta.
--
-- Servem para provar os gatilhos, o pull, a restauração e o estresse (ADR-0002, item 5).

CREATE TABLE corona.teste_sync_a (
    id uuid PRIMARY KEY,
    texto text NOT NULL,
    valor numeric(14,2) NOT NULL DEFAULT 0,
    quantidade integer NOT NULL DEFAULT 0 CONSTRAINT teste_sync_a_quantidade_faixa CHECK (quantidade BETWEEN 0 AND 100000)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.teste_sync_a');

CREATE TABLE corona.teste_sync_b (
    id uuid PRIMARY KEY,
    nome text NOT NULL,
    porcentagem numeric(5,2) NOT NULL DEFAULT 0 CONSTRAINT teste_sync_b_porcentagem_faixa CHECK (porcentagem BETWEEN 0 AND 100)
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.teste_sync_b');

CREATE TABLE corona.teste_sync_imutavel (
    id uuid PRIMARY KEY,
    valor numeric(14,2) NOT NULL
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.teste_sync_imutavel', imutavel => true);

CREATE TABLE corona.teste_servidor (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    descricao text NOT NULL,
    deleted_at timestamptz
);
SELECT corona.aplicar_convencoes_do_servidor('corona.teste_servidor', editavel => true);

CREATE TABLE corona.teste_servidor_historico (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    evento text NOT NULL
);
SELECT corona.aplicar_convencoes_do_servidor('corona.teste_servidor_historico', editavel => false);
