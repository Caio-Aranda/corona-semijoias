/**
 * Migrações do banco local do Corona Vendas (ADR-0005, item 5; ADR-0013, itens 7 e 16).
 *
 * Regras (as mesmas do Flyway no servidor):
 * - lista ordenada, só para a frente; cada uma roda numa transação que grava `_migracoes` e `PRAGMA user_version`;
 * - **migração publicada nunca se edita**: o hash do SQL fica gravado e uma diferença derruba a abertura. Mudança de
 *   banco = migração nova no fim da lista;
 * - a outbox nunca é migrada: as operações guardam o JSON exato e o formato (ADR-0013, item 16).
 *
 * Esquema da versão 1: as cópias das tabelas sincronizáveis (só as colunas da lista explícita do servidor, mais
 * `version`, `deleted_at` e `origem_local`), a outbox, o estado do envio e recebimento e a tabela das migrações.
 * Dinheiro é TEXTO com duas casas ("12.34"), nunca número de ponto flutuante; datas em texto ISO-8601 UTC.
 */
export interface MigracaoLocal {
  versao: number;
  descricao: string;
  sql: string;
}

/** Colunas de controle de toda cópia local de tabela sincronizável. */
const CONTROLE = `
  id TEXT PRIMARY KEY NOT NULL,
  version INTEGER NOT NULL CHECK (version >= 1),
  created_at TEXT,
  updated_at TEXT,
  deleted_at TEXT,
  origem_local TEXT NOT NULL DEFAULT 'SERVIDOR' CHECK (origem_local IN ('SERVIDOR', 'PROVISORIO'))`;

const V1 = `
CREATE TABLE cotacoes (${CONTROLE},
  data_cotacao TEXT NOT NULL,
  usd_compra TEXT, usd_venda TEXT, usd_paridade_compra TEXT, usd_paridade_venda TEXT,
  pyg_compra TEXT, pyg_venda TEXT, pyg_paridade_compra TEXT, pyg_paridade_venda TEXT,
  buscada_em TEXT, fonte TEXT
);
CREATE INDEX cotacoes_por_data ON cotacoes (data_cotacao);

CREATE TABLE categorias (${CONTROLE},
  nome TEXT NOT NULL,
  ativo INTEGER
);

CREATE TABLE semanas (${CONTROLE},
  numero INTEGER, inicio TEXT, fim TEXT, situacao TEXT
);

-- Clientes da loja (11/11/2026): o diretório (nome, CPF, telefone, cidade) de todos e o cadastro completo dos que o
-- vendedor atende. As colunas a mais ficam vazias quando o servidor não as manda.
CREATE TABLE clientes (${CONTROLE},
  nome TEXT NOT NULL,
  cpf TEXT, data_nascimento TEXT, telefone TEXT, whatsapp TEXT, ativo INTEGER,
  endereco_logradouro TEXT, endereco_numero TEXT, endereco_complemento TEXT, endereco_bairro TEXT,
  endereco_cidade TEXT, endereco_uf TEXT, endereco_cep TEXT,
  observacoes TEXT, vendedor_responsavel_id TEXT
);
CREATE INDEX clientes_por_cpf ON clientes (cpf);
CREATE INDEX clientes_por_nome ON clientes (nome);

-- Sem custo, sem fornecedor e sem estoque da loja (o servidor nunca manda).
CREATE TABLE produtos (${CONTROLE},
  nome TEXT NOT NULL,
  referencia TEXT, categoria_id TEXT, preco_venda TEXT, ativo INTEGER, foto_principal_hash TEXT
);

CREATE TABLE produto_codigos (${CONTROLE},
  produto_id TEXT NOT NULL, codigo TEXT NOT NULL, tipo TEXT, principal INTEGER, ativo INTEGER
);
CREATE INDEX produto_codigos_por_codigo ON produto_codigos (codigo COLLATE NOCASE);
CREATE INDEX produto_codigos_do_produto ON produto_codigos (produto_id);

CREATE TABLE produtos_fotos (${CONTROLE},
  produto_id TEXT NOT NULL, arquivo_hash TEXT, principal INTEGER, ordem INTEGER
);
CREATE INDEX produtos_fotos_do_produto ON produtos_fotos (produto_id);

CREATE TABLE remessas (${CONTROLE},
  vendedor_id TEXT, semana_id TEXT, praca_id TEXT, tipo TEXT, status TEXT, dispositivo_id TEXT,
  total_pecas INTEGER, total_valor TEXT, enviada_em TEXT, motivo_cancelamento TEXT
);

CREATE TABLE itens_remessa (${CONTROLE},
  remessa_id TEXT NOT NULL, produto_id TEXT NOT NULL, qtd_enviada INTEGER NOT NULL, preco_remessa TEXT NOT NULL
);
CREATE INDEX itens_remessa_da_remessa ON itens_remessa (remessa_id);

CREATE TABLE movimentos_remessa (${CONTROLE},
  remessa_id TEXT NOT NULL, produto_id TEXT NOT NULL, tipo TEXT NOT NULL, quantidade INTEGER NOT NULL,
  venda_id TEXT, dispositivo_id TEXT, estorna_id TEXT
);
CREATE INDEX movimentos_remessa_da_remessa ON movimentos_remessa (remessa_id, produto_id);

CREATE TABLE vendas (${CONTROLE},
  vendedor_id TEXT, cliente_id TEXT, remessa_id TEXT, status TEXT, subtotal TEXT, desconto TEXT, total TEXT,
  cotacao_id TEXT, vendida_em TEXT, estorno_id TEXT, estornada_em TEXT, motivo_estorno TEXT
);
CREATE INDEX vendas_por_data ON vendas (vendida_em);

CREATE TABLE itens_venda (${CONTROLE},
  venda_id TEXT NOT NULL, produto_id TEXT NOT NULL, quantidade INTEGER NOT NULL, preco_unitario TEXT, desconto TEXT
);
CREATE INDEX itens_venda_da_venda ON itens_venda (venda_id);

CREATE TABLE pagamentos_cliente (${CONTROLE},
  venda_id TEXT NOT NULL, tipo TEXT, forma TEXT, valor TEXT NOT NULL, pago_em TEXT
);
CREATE INDEX pagamentos_cliente_da_venda ON pagamentos_cliente (venda_id);

CREATE TABLE conferencias (${CONTROLE},
  remessa_id TEXT NOT NULL, vendedor_id TEXT, dispositivo_id TEXT, divergencias INTEGER, conferida_em TEXT
);

CREATE TABLE itens_conferencia (${CONTROLE},
  conferencia_id TEXT NOT NULL, produto_id TEXT NOT NULL, esperado INTEGER, contado INTEGER
);

-- Uma linha por operação feita no computador, gravada na MESMA transação da operação (ADR-0013, item 2).
-- "dados" é o JSON exato da hora da operação: o reenvio manda sempre estes bytes.
CREATE TABLE outbox (
  ordem INTEGER PRIMARY KEY AUTOINCREMENT,
  operacao_id TEXT NOT NULL UNIQUE,
  tipo TEXT NOT NULL,
  formato INTEGER NOT NULL CHECK (formato >= 1),
  dados TEXT NOT NULL,
  criado_em_local TEXT NOT NULL,
  ultimo_contato_servidor TEXT,
  situacao TEXT NOT NULL DEFAULT 'AGUARDANDO'
    CHECK (situacao IN ('AGUARDANDO', 'ENVIADO', 'RECUSADO', 'CONFLITO')),
  tentativas INTEGER NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
  ultimo_erro TEXT,
  codigo_do_resultado TEXT,
  respondido_em TEXT,
  entendido_em TEXT
);
CREATE INDEX outbox_aguardando ON outbox (situacao, ordem);

-- Estado do envio e do recebimento: cursor do pull, último contato com o servidor (hora do servidor e hora local),
-- maior hora local já vista (prazo offline, ADR-0013 item 12), "dados até" dos relatórios.
CREATE TABLE estado_sync (
  chave TEXT PRIMARY KEY NOT NULL,
  valor TEXT
);
`;

/**
 * Versão 2 (tarefa 5.6): de qual operação da outbox é cada linha provisória. Uma venda feita sem internet grava a
 * venda, os itens, os movimentos da maleta e os pagamentos; os itens e os movimentos ganham outro id no servidor. Com
 * esta tabela o motor sabe o que desfazer quando a operação é recusada (o saldo volta) e o que tirar quando a cópia do
 * servidor chega, sem nunca depender do id da linha do servidor.
 */
const V2 = `
CREATE TABLE provisorias (
  tabela TEXT NOT NULL,
  linha_id TEXT NOT NULL,
  operacao_id TEXT NOT NULL,
  PRIMARY KEY (tabela, linha_id)
);
CREATE INDEX provisorias_da_operacao ON provisorias (operacao_id);
`;

export const MIGRACOES: readonly MigracaoLocal[] = Object.freeze([
  {
    versao: 1,
    descricao: 'cópias das tabelas sincronizáveis, outbox e estado do envio',
    sql: V1,
  },
  {
    versao: 2,
    descricao: 'operação de origem de cada linha provisória',
    sql: V2,
  },
]);

/** A versão mais nova do banco local que este aplicativo conhece. */
export const VERSAO_DO_BANCO = MIGRACOES[MIGRACOES.length - 1]?.versao ?? 0;

/** Tabelas sincronizáveis com cópia local (mesma lista do `RegistroDeTabelas` do servidor, ADR-0013, item 4). */
export const TABELAS_SINCRONIZAVEIS = Object.freeze([
  'cotacoes',
  'categorias',
  'semanas',
  'clientes',
  'produtos',
  'produto_codigos',
  'produtos_fotos',
  'remessas',
  'itens_remessa',
  'movimentos_remessa',
  'vendas',
  'itens_venda',
  'pagamentos_cliente',
  'conferencias',
  'itens_conferencia',
] as const);
