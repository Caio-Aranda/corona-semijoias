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

/**
 * As tabelas que a recarga dirigida da versão 3 traz de novo (tarefa 6.7, dúvida S6): as oito novas da Fase 6 e as três
 * que ganharam coluna (as cópias antigas delas não têm o valor novo).
 */
export const TABELAS_DA_RECARGA_DIRIGIDA_V3 = Object.freeze([
  'devolucoes',
  'itens_devolucao',
  'autorizacoes_excecao',
  'itens_autorizacao',
  'trocas',
  'creditos_cliente',
  'usos_credito',
  'creditos_sem_saldo',
  'vendas',
  'pagamentos_cliente',
  'movimentos_remessa',
] as const);

/**
 * Versão 3 (tarefa 6.7; ADR-0014, item 14): as cópias das tabelas da Fase 6 (devolução, autorização, troca, crédito do
 * cliente e a venda com crédito para a dona revisar), com as colunas da lista do servidor, e as colunas novas de
 * `vendas`, `pagamentos_cliente` e `movimentos_remessa`. Dinheiro em texto, datas em texto ISO. Banco que já tinha
 * cursor (já recebeu dados) ganha a marca da recarga dirigida: as onze tabelas vêm de novo, sem apagar nada antes. Banco
 * novo faz a carga inicial e não precisa.
 */
const V3 = `
CREATE TABLE devolucoes (${CONTROLE},
  venda_id TEXT NOT NULL, cliente_id TEXT, vendedor_id TEXT, remessa_id TEXT, motivo TEXT, observacao TEXT, destino TEXT,
  dentro_garantia INTEGER, situacao TEXT, autorizacao_id TEXT, valor_devolvido TEXT, valor_reembolsado TEXT,
  valor_em_credito TEXT, forma_reembolso TEXT, cotacao_id TEXT, devolvida_em TEXT, hora_ajustada INTEGER,
  cancelamento_id TEXT, cancelada_em TEXT, motivo_cancelamento TEXT, revisao_fora_do_prazo INTEGER,
  revisao_acima_do_limite_semanal INTEGER, revisao_acima_do_valor_de_aprovacao INTEGER, revisao_autorizacao_invalida INTEGER,
  revisao_reembolso_maior_que_pago INTEGER, revisada_em TEXT, observacao_revisao TEXT
);
CREATE INDEX devolucoes_da_venda ON devolucoes (venda_id);
CREATE INDEX devolucoes_do_cliente ON devolucoes (cliente_id);

CREATE TABLE itens_devolucao (${CONTROLE},
  devolucao_id TEXT NOT NULL, item_venda_id TEXT, quantidade INTEGER NOT NULL, valor TEXT, estado_peca TEXT, destino_peca TEXT
);
CREATE INDEX itens_devolucao_da_devolucao ON itens_devolucao (devolucao_id);

CREATE TABLE autorizacoes_excecao (${CONTROLE},
  venda_id TEXT NOT NULL, vendedor_id TEXT, cliente_id TEXT, motivo TEXT, observacao TEXT, tipo TEXT, situacao TEXT,
  valor_pedido TEXT, cotacao_id TEXT, pedida_em TEXT, hora_ajustada INTEGER, decidida_em TEXT, motivo_decisao TEXT,
  valida_ate TEXT
);
CREATE INDEX autorizacoes_da_venda ON autorizacoes_excecao (venda_id);

CREATE TABLE itens_autorizacao (${CONTROLE},
  autorizacao_id TEXT NOT NULL, item_venda_id TEXT, quantidade INTEGER NOT NULL
);
CREATE INDEX itens_autorizacao_da_autorizacao ON itens_autorizacao (autorizacao_id);

CREATE TABLE trocas (${CONTROLE},
  venda_origem_id TEXT NOT NULL, devolucao_id TEXT, venda_nova_id TEXT, cliente_id TEXT, vendedor_id TEXT, diferenca TEXT,
  cotacao_id TEXT, trocada_em TEXT, hora_ajustada INTEGER, situacao TEXT, cancelamento_id TEXT, cancelada_em TEXT,
  motivo_cancelamento TEXT
);
CREATE INDEX trocas_da_venda_de_origem ON trocas (venda_origem_id);
CREATE INDEX trocas_do_cliente ON trocas (cliente_id);

CREATE TABLE creditos_cliente (${CONTROLE},
  cliente_id TEXT NOT NULL, vendedor_id TEXT, origem TEXT, troca_id TEXT, devolucao_id TEXT, valor TEXT NOT NULL,
  valor_utilizado TEXT, situacao TEXT, cotacao_id TEXT, gerado_em TEXT, hora_ajustada INTEGER, cancelado_em TEXT
);
CREATE INDEX creditos_cliente_do_cliente ON creditos_cliente (cliente_id);

CREATE TABLE usos_credito (${CONTROLE},
  credito_id TEXT NOT NULL, tipo TEXT, venda_id TEXT, pagamento_id TEXT, valor TEXT NOT NULL, estorna_id TEXT, usado_em TEXT
);
CREATE INDEX usos_credito_do_credito ON usos_credito (credito_id);
CREATE INDEX usos_credito_da_venda ON usos_credito (venda_id);

CREATE TABLE creditos_sem_saldo (${CONTROLE},
  venda_id TEXT NOT NULL, pagamento_id TEXT, credito_id TEXT, valor_pedido TEXT, valor_usado TEXT, situacao TEXT, registrada_em TEXT,
  revisada_em TEXT, observacao_revisao TEXT
);
CREATE INDEX creditos_sem_saldo_da_venda ON creditos_sem_saldo (venda_id);
CREATE INDEX creditos_sem_saldo_do_credito ON creditos_sem_saldo (credito_id);

ALTER TABLE vendas ADD COLUMN troca_id TEXT;
ALTER TABLE pagamentos_cliente ADD COLUMN devolucao_id TEXT;
ALTER TABLE pagamentos_cliente ADD COLUMN estorna_id TEXT;
ALTER TABLE pagamentos_cliente ADD COLUMN credito_id TEXT;
ALTER TABLE movimentos_remessa ADD COLUMN devolucao_id TEXT;
CREATE INDEX pagamentos_cliente_do_credito ON pagamentos_cliente (credito_id);

INSERT INTO estado_sync (chave, valor)
  SELECT 'recarga_dirigida', '${JSON.stringify(TABELAS_DA_RECARGA_DIRIGIDA_V3)}'
   WHERE EXISTS (SELECT 1 FROM estado_sync WHERE chave = 'cursor' AND valor IS NOT NULL);
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
  {
    versao: 3,
    descricao: 'tabelas da Fase 6, colunas novas e a recarga dirigida',
    sql: V3,
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
  // Fase 6 (tarefa 6.7, migração local 3).
  'devolucoes',
  'itens_devolucao',
  'autorizacoes_excecao',
  'itens_autorizacao',
  'trocas',
  'creditos_cliente',
  'usos_credito',
  'creditos_sem_saldo',
] as const);
