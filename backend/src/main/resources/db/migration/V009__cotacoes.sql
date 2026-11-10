-- Cotações do Banco Central (tarefa 19; ADR-0006, itens 1 a 4). Uma linha por dia útil, com o
-- Dólar e o Guarani do mesmo dia, como vieram do arquivo de fechamento do BCB.
--
-- A tabela nasce só no servidor, mas chega ao app do vendedor: é sincronizável e imutável (recusa
-- UPDATE, DELETE e TRUNCATE). Dia gravado nunca é relido nem corrigido por cima. Não existe cotação
-- parcial: as oito colunas são NOT NULL e entram na mesma linha, na mesma transação.
--
-- NUMERIC(18,8) é o tipo da cotação (8 casas, como o arquivo; até 10 dígitos antes da vírgula).
-- Só estas oito colunas podem usar esse tipo: o teste de convenções reprova nas outras.

CREATE TABLE corona.cotacoes (
    id uuid PRIMARY KEY,
    data_cotacao date NOT NULL
        CONSTRAINT cotacoes_data_unica UNIQUE,
    usd_compra numeric(18,8) NOT NULL CONSTRAINT cotacoes_usd_compra_positiva CHECK (usd_compra > 0),
    usd_venda numeric(18,8) NOT NULL CONSTRAINT cotacoes_usd_venda_positiva CHECK (usd_venda > 0),
    usd_paridade_compra numeric(18,8) NOT NULL CONSTRAINT cotacoes_usd_paridade_compra_positiva CHECK (usd_paridade_compra > 0),
    usd_paridade_venda numeric(18,8) NOT NULL CONSTRAINT cotacoes_usd_paridade_venda_positiva CHECK (usd_paridade_venda > 0),
    pyg_compra numeric(18,8) NOT NULL CONSTRAINT cotacoes_pyg_compra_positiva CHECK (pyg_compra > 0),
    pyg_venda numeric(18,8) NOT NULL CONSTRAINT cotacoes_pyg_venda_positiva CHECK (pyg_venda > 0),
    pyg_paridade_compra numeric(18,8) NOT NULL CONSTRAINT cotacoes_pyg_paridade_compra_positiva CHECK (pyg_paridade_compra > 0),
    pyg_paridade_venda numeric(18,8) NOT NULL CONSTRAINT cotacoes_pyg_paridade_venda_positiva CHECK (pyg_paridade_venda > 0),
    buscada_em timestamptz NOT NULL,
    fonte text NOT NULL,
    CONSTRAINT cotacoes_usd_compra_ate_venda CHECK (usd_compra <= usd_venda),
    CONSTRAINT cotacoes_pyg_compra_ate_venda CHECK (pyg_compra <= pyg_venda)
);
COMMENT ON TABLE corona.cotacoes IS
    'Sincronizável e imutável: cotação do Banco Central (arquivo de fechamento), uma linha por dia útil, Dólar e Guarani juntos. A tela usa as colunas de venda. ADR-0006.';
COMMENT ON COLUMN corona.cotacoes.data_cotacao IS
    'Dia do arquivo do BCB (data de calendário, não instante). Não repete: dia gravado nunca é relido.';
COMMENT ON COLUMN corona.cotacoes.usd_venda IS
    'Reais por dólar (taxa de venda). A conta da tela é R$ ÷ usd_venda, 2 casas, HALF_UP.';
COMMENT ON COLUMN corona.cotacoes.pyg_venda IS
    'Reais por guarani (taxa de venda, como o BCB publica). A conta da tela é R$ ÷ pyg_venda, 0 casas, HALF_UP.';
COMMENT ON COLUMN corona.cotacoes.buscada_em IS 'Hora da leitura do arquivo, em UTC.';
COMMENT ON COLUMN corona.cotacoes.fonte IS 'Endereço do arquivo que foi lido.';

SELECT corona.aplicar_convencoes_sincronizavel('corona.cotacoes', true);

REVOKE ALL ON corona.cotacoes FROM PUBLIC;
