-- V017: ativo/inativo em todos os cadastros e data de nascimento do cliente (Fase 1, tarefa 1.14;
-- ESPECIFICACAO §9, v1.7).
--
-- Inativar NÃO é excluir: o cadastro continua no banco e nos relatórios e pode ser reativado. Por isso
-- é uma coluna à parte de deleted_at. Linhas que já existem ficam ativas (DEFAULT true; o PostgreSQL
-- não reescreve a tabela nem dispara o gatilho de sync, e o app do vendedor assume ativo quando o campo
-- ainda não chegou). Semanas não ganham a coluna: ABERTA/FECHADA já diz se estão em uso.
--
-- clientes e categorias são sincronizáveis: as colunas novas entram no contrato do pull (RegistroDeTabelas).
-- A data de nascimento é opcional; "não pode ser futura" é conferido pela API (o banco não usa o dia de hoje
-- numa regra) e o banco só recusa datas absurdas.

ALTER TABLE corona.fornecedores ADD COLUMN ativo boolean NOT NULL DEFAULT true;
ALTER TABLE corona.pracas ADD COLUMN ativo boolean NOT NULL DEFAULT true;
ALTER TABLE corona.restricoes ADD COLUMN ativo boolean NOT NULL DEFAULT true;
ALTER TABLE corona.categorias ADD COLUMN ativo boolean NOT NULL DEFAULT true;
ALTER TABLE corona.clientes ADD COLUMN ativo boolean NOT NULL DEFAULT true;

ALTER TABLE corona.clientes ADD COLUMN data_nascimento date
    CONSTRAINT clientes_data_nascimento_plausivel CHECK (data_nascimento >= DATE '1900-01-01');

-- Aniversariantes: o relatório filtra pelo mês.
CREATE INDEX clientes_nascimento_mes_idx ON corona.clientes ((extract(month FROM data_nascimento)))
    WHERE data_nascimento IS NOT NULL AND deleted_at IS NULL;
