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

/**
 * As tabelas que a recarga dirigida da versão 4 traz de novo (tarefa 7.3): as duas novas das contas a receber e a que
 * ganhou coluna (`pagamentos_cliente.posterior`; a cópia antiga não tem o valor).
 */
export const TABELAS_DA_RECARGA_DIRIGIDA_V4 = Object.freeze([
  'contas_receber',
  'baixas_receber',
  'pagamentos_cliente',
] as const);

/**
 * Versão 4 (tarefa 7.3; ADR-0012, item 7): as cópias das contas a receber do cliente (parcelas e baixas), com as colunas
 * da lista do servidor, e a coluna nova `posterior` de `pagamentos_cliente`. Dinheiro em texto, datas em texto ISO. Banco
 * que já tinha cursor ganha a marca da recarga dirigida (somada à da versão 3, se ela ainda não terminou, sem repetir e
 * na ordem: as antigas primeiro). Banco novo faz a carga inicial e não precisa.
 */
const V4 = `
CREATE TABLE contas_receber (${CONTROLE},
  devedor_tipo TEXT, cliente_id TEXT, vendedor_id TEXT, origem_tipo TEXT, origem_id TEXT, venda_id TEXT NOT NULL,
  parcela INTEGER, total_parcelas INTEGER, vencimento TEXT, valor TEXT NOT NULL, valor_pago TEXT, valor_cancelado TEXT,
  status TEXT
);
CREATE INDEX contas_receber_da_venda ON contas_receber (venda_id);
CREATE INDEX contas_receber_do_cliente ON contas_receber (cliente_id);

CREATE TABLE baixas_receber (${CONTROLE},
  conta_id TEXT NOT NULL, tipo TEXT, valor TEXT NOT NULL, baixada_em TEXT, pagamento_cliente_id TEXT,
  motivo_cancelamento TEXT, estorna_id TEXT
);
CREATE INDEX baixas_receber_da_conta ON baixas_receber (conta_id);

ALTER TABLE pagamentos_cliente ADD COLUMN posterior INTEGER;

INSERT INTO estado_sync (chave, valor)
  SELECT 'recarga_dirigida', lista FROM (
    SELECT json_group_array(t) AS lista FROM (
      SELECT t FROM (
        SELECT key AS k, value AS t FROM json_each(
          coalesce((SELECT valor FROM estado_sync WHERE chave = 'recarga_dirigida'), '[]'))
        UNION ALL
        SELECT 1000 + key AS k, value AS t FROM json_each('${JSON.stringify(TABELAS_DA_RECARGA_DIRIGIDA_V4)}')
      ) GROUP BY t ORDER BY min(k)
    )
  )
   WHERE EXISTS (SELECT 1 FROM estado_sync WHERE chave = 'cursor' AND valor IS NOT NULL)
  ON CONFLICT (chave) DO UPDATE SET valor = excluded.valor;
`;

/**
 * Versão 5 (tarefa 6.6; ADR-0014, item 10): a devolução de venda a prazo abate as parcelas. Duas colunas novas, sem
 * recarga dirigida: toda linha antiga vale vazio (a venda a prazo nunca foi devolvida antes, então "nada abatido" é o
 * valor certo e a cópia local já está correta).
 */
const V5 = `
ALTER TABLE devolucoes ADD COLUMN valor_abatido TEXT;
ALTER TABLE baixas_receber ADD COLUMN devolucao_id TEXT;
`;

/**
 * As tabelas que a recarga dirigida da versão 6 traz de novo (tarefa 7.4): as duas novas do acerto. O computador que já tinha
 * cursor não receberia o que o servidor gravou antes da migração.
 */
export const TABELAS_DA_RECARGA_DIRIGIDA_V6 = Object.freeze([
  'acertos',
  'leituras_acerto',
] as const);

/**
 * Versão 6 (tarefa 7.4; ADR-0012, itens 2, 3 e 15): as cópias do acerto e da contagem dele, com as colunas da lista do
 * servidor (nunca quem iniciou, desistiu ou leu; a ligação maleta -> acerto não vem). Booleano em 0/1, datas em texto ISO. Banco
 * que já tinha cursor ganha a marca da recarga dirigida (somada à que ainda não terminou, sem repetir e na ordem: as antigas
 * primeiro). Banco novo faz a carga inicial e não precisa.
 */
const V6 = `
CREATE TABLE acertos (${CONTROLE},
  vendedor_id TEXT, semana_id TEXT, status TEXT, pedido_pelo_vendedor INTEGER, iniciado_em TEXT, desistido_em TEXT,
  motivo_desistencia TEXT
);
CREATE INDEX acertos_do_vendedor ON acertos (vendedor_id);

CREATE TABLE leituras_acerto (${CONTROLE},
  acerto_id TEXT NOT NULL, produto_id TEXT, quantidade INTEGER, estado_peca TEXT, lida_em TEXT, pelo_vendedor INTEGER,
  dispositivo_id TEXT
);
CREATE INDEX leituras_acerto_do_acerto ON leituras_acerto (acerto_id);

INSERT INTO estado_sync (chave, valor)
  SELECT 'recarga_dirigida', lista FROM (
    SELECT json_group_array(t) AS lista FROM (
      SELECT t FROM (
        SELECT key AS k, value AS t FROM json_each(
          coalesce((SELECT valor FROM estado_sync WHERE chave = 'recarga_dirigida'), '[]'))
        UNION ALL
        SELECT 1000 + key AS k, value AS t FROM json_each('${JSON.stringify(TABELAS_DA_RECARGA_DIRIGIDA_V6)}')
      ) GROUP BY t ORDER BY min(k)
    )
  )
   WHERE EXISTS (SELECT 1 FROM estado_sync WHERE chave = 'cursor' AND valor IS NOT NULL)
  ON CONFLICT (chave) DO UPDATE SET valor = excluded.valor;
`;

/**
 * As tabelas que a recarga dirigida da versão 7 traz de novo (tarefa 7.5): as quatro novas do fechamento e o acerto, que ganhou
 * colunas (o computador que já tinha cursor não receberia o que o servidor gravou antes da migração).
 */
export const TABELAS_DA_RECARGA_DIRIGIDA_V7 = Object.freeze([
  'acertos',
  'itens_acerto',
  'acerto_eventos',
  'comissoes',
  'pendencias_acerto',
] as const);

/**
 * Versão 7 (tarefa 7.5; ADR-0012, itens 3.6, 5, 6 e 8): o fechamento do acerto. Colunas novas em `acertos` (os totais, a meta, o
 * percentual e a soma em Guarani; nunca quem fechou nem o resumo das decisões) e as cópias de `itens_acerto`, `acerto_eventos`,
 * `comissoes` e `pendencias_acerto` (só do vendedor dono). Dinheiro em texto, como o servidor manda; datas em texto ISO. Banco que
 * já tinha cursor ganha a marca da recarga dirigida (somada à que ainda não terminou, sem repetir e na ordem).
 */
const V7 = `
ALTER TABLE acertos ADD COLUMN fechado_em TEXT;
ALTER TABLE acertos ADD COLUMN cotacao_id TEXT;
ALTER TABLE acertos ADD COLUMN meta_guarani INTEGER;
ALTER TABLE acertos ADD COLUMN percentual_comissao TEXT;
ALTER TABLE acertos ADD COLUMN vendido_para_meta_guarani INTEGER;
ALTER TABLE acertos ADD COLUMN vendido_bruto TEXT;
ALTER TABLE acertos ADD COLUMN descontos TEXT;
ALTER TABLE acertos ADD COLUMN devolvido TEXT;
ALTER TABLE acertos ADD COLUMN vendido_liquido TEXT;
ALTER TABLE acertos ADD COLUMN comissao TEXT;
ALTER TABLE acertos ADD COLUMN faltas_cobradas TEXT;
ALTER TABLE acertos ADD COLUMN liquido TEXT;
ALTER TABLE acertos ADD COLUMN recebido_no_acerto TEXT;
ALTER TABLE acertos ADD COLUMN ficou_devendo TEXT;

CREATE TABLE itens_acerto (${CONTROLE},
  acerto_id TEXT NOT NULL, produto_id TEXT, remessa_id TEXT, preco_remessa TEXT, esperado INTEGER, contado_boa INTEGER,
  contado_defeito INTEGER, falta INTEGER, defeito_aprovado INTEGER
);
CREATE INDEX itens_acerto_do_acerto ON itens_acerto (acerto_id);

CREATE TABLE acerto_eventos (${CONTROLE},
  acerto_id TEXT NOT NULL, vendedor_id TEXT, origem_tipo TEXT, origem_id TEXT, venda_id TEXT, valor TEXT
);
CREATE INDEX acerto_eventos_do_acerto ON acerto_eventos (acerto_id);

CREATE TABLE comissoes (${CONTROLE},
  vendedor_id TEXT, acerto_id TEXT NOT NULL, venda_id TEXT, evento_id TEXT, tipo TEXT, base TEXT, percentual TEXT, valor TEXT,
  estorna_id TEXT
);
CREATE INDEX comissoes_do_acerto ON comissoes (acerto_id);

CREATE TABLE pendencias_acerto (${CONTROLE},
  acerto_id TEXT NOT NULL, vendedor_id TEXT, tipo TEXT, produto_id TEXT, remessa_id TEXT, quantidade INTEGER, valor TEXT,
  origem_tipo TEXT, origem_id TEXT, status TEXT, cobrada_no_acerto_id TEXT, resolvida_em TEXT
);
CREATE INDEX pendencias_acerto_do_acerto ON pendencias_acerto (acerto_id);

INSERT INTO estado_sync (chave, valor)
  SELECT 'recarga_dirigida', lista FROM (
    SELECT json_group_array(t) AS lista FROM (
      SELECT t FROM (
        SELECT key AS k, value AS t FROM json_each(
          coalesce((SELECT valor FROM estado_sync WHERE chave = 'recarga_dirigida'), '[]'))
        UNION ALL
        SELECT 1000 + key AS k, value AS t FROM json_each('${JSON.stringify(TABELAS_DA_RECARGA_DIRIGIDA_V7)}')
      ) GROUP BY t ORDER BY min(k)
    )
  )
   WHERE EXISTS (SELECT 1 FROM estado_sync WHERE chave = 'cursor' AND valor IS NOT NULL)
  ON CONFLICT (chave) DO UPDATE SET valor = excluded.valor;
`;

/**
 * As tabelas que a recarga dirigida da versão 8 traz de novo (tarefa 7.6): as contas (que ganharam o nível 2 e a forma
 * prevista), as baixas (que ganharam o recebimento do vendedor), a nova `recebimentos_vendedor` e o acerto (que ganhou o
 * pago ao vendedor e o crédito usado). O computador que já tinha cursor não receberia o que o servidor gravou antes da migração.
 */
export const TABELAS_DA_RECARGA_DIRIGIDA_V8 = Object.freeze([
  'contas_receber',
  'baixas_receber',
  'recebimentos_vendedor',
  'acertos',
] as const);

/**
 * Versão 8 (tarefa 7.6; ADR-0012, itens 7, 8, 9 e 11): o dinheiro que o vendedor deve e paga à loja. `contas_receber` aceita
 * a conta do nível 2 (sem venda) e ganha `forma_prevista`: como o SQLite não troca "NOT NULL" no lugar, a cópia local é refeita
 * com as mesmas linhas (nada se perde). `baixas_receber` ganha `recebimento_vendedor_id`, `acertos` ganha `pago_ao_vendedor`,
 * `credito_usado` e `credito_do_acerto_id`, e nasce `recebimentos_vendedor` (só do vendedor dono; nunca quem registrou, o motivo
 * do estorno nem o resumo do pedido). O caixa e as categorias dele não existem aqui: nunca vão ao aplicativo. Dinheiro em
 * texto, datas em texto ISO. Banco que já tinha cursor ganha a marca da recarga dirigida (somada à que ainda não terminou,
 * sem repetir e na ordem). Banco novo faz a carga inicial e não precisa.
 */
const V8 = `
ALTER TABLE contas_receber RENAME TO contas_receber_antiga;
CREATE TABLE contas_receber (${CONTROLE},
  devedor_tipo TEXT, cliente_id TEXT, vendedor_id TEXT, origem_tipo TEXT, origem_id TEXT, venda_id TEXT,
  parcela INTEGER, total_parcelas INTEGER, vencimento TEXT, valor TEXT NOT NULL, valor_pago TEXT, valor_cancelado TEXT,
  status TEXT, forma_prevista TEXT
);
INSERT INTO contas_receber (id, version, created_at, updated_at, deleted_at, origem_local, devedor_tipo, cliente_id,
    vendedor_id, origem_tipo, origem_id, venda_id, parcela, total_parcelas, vencimento, valor, valor_pago, valor_cancelado,
    status)
  SELECT id, version, created_at, updated_at, deleted_at, origem_local, devedor_tipo, cliente_id,
    vendedor_id, origem_tipo, origem_id, venda_id, parcela, total_parcelas, vencimento, valor, valor_pago, valor_cancelado,
    status
  FROM contas_receber_antiga;
DROP TABLE contas_receber_antiga;
CREATE INDEX contas_receber_da_venda ON contas_receber (venda_id);
CREATE INDEX contas_receber_do_cliente ON contas_receber (cliente_id);
CREATE INDEX contas_receber_do_vendedor ON contas_receber (vendedor_id);

ALTER TABLE baixas_receber ADD COLUMN recebimento_vendedor_id TEXT;

ALTER TABLE acertos ADD COLUMN pago_ao_vendedor TEXT;
ALTER TABLE acertos ADD COLUMN credito_usado TEXT;
ALTER TABLE acertos ADD COLUMN credito_do_acerto_id TEXT;

CREATE TABLE recebimentos_vendedor (${CONTROLE},
  vendedor_id TEXT, momento TEXT, acerto_id TEXT, aplicado_em TEXT, forma TEXT, valor TEXT NOT NULL, recebido_em TEXT,
  estorna_id TEXT
);
CREATE INDEX recebimentos_vendedor_do_vendedor ON recebimentos_vendedor (vendedor_id);
CREATE INDEX recebimentos_vendedor_do_acerto ON recebimentos_vendedor (acerto_id);

INSERT INTO estado_sync (chave, valor)
  SELECT 'recarga_dirigida', lista FROM (
    SELECT json_group_array(t) AS lista FROM (
      SELECT t FROM (
        SELECT key AS k, value AS t FROM json_each(
          coalesce((SELECT valor FROM estado_sync WHERE chave = 'recarga_dirigida'), '[]'))
        UNION ALL
        SELECT 1000 + key AS k, value AS t FROM json_each('${JSON.stringify(TABELAS_DA_RECARGA_DIRIGIDA_V8)}')
      ) GROUP BY t ORDER BY min(k)
    )
  )
   WHERE EXISTS (SELECT 1 FROM estado_sync WHERE chave = 'cursor' AND valor IS NOT NULL)
  ON CONFLICT (chave) DO UPDATE SET valor = excluded.valor;
`;

/**
 * As tabelas que a recarga dirigida da versão 9 traz de novo (tarefa 9.2; ADR-0016, item 13): as onze do consignado ao
 * cliente e as duas que ganharam coluna ou regra (`clientes`, com a lista só do representante, e `movimentos_remessa`, com os
 * movimentos do consignado). O computador que já tinha cursor não receberia o que o servidor gravou antes da migração.
 */
export const TABELAS_DA_RECARGA_DIRIGIDA_V9 = Object.freeze([
  'clientes',
  'movimentos_remessa',
  'semanas_vendedor',
  'notas_consignado',
  'reposicoes_nota',
  'trocas_nota',
  'itens_nota_consignado',
  'remarcacoes_nota',
  'acertos_nota',
  'itens_acerto_nota',
  'recibos_consignado',
  'formas_recibo_consignado',
  'aplicacoes_recibo',
] as const);

/**
 * Versão 9 (tarefa 9.2; ADR-0016, itens 4, 13 e 9): o consignado ao cliente. Espelho das onze tabelas novas do servidor e das
 * colunas novas de `clientes` e `movimentos_remessa` (dinheiro em texto com duas casas, datas em texto ISO, booleano 0/1), mais
 * as tabelas SÓ DO COMPUTADOR: o rascunho do Novo Pedido (nunca vai ao servidor), o contador da semana (o número
 * semana-sequência nasce aqui) e a fila de envio das fotos do cliente. Cada representante tem a SUA lista de clientes: as
 * cópias vindas do servidor que não são dele (o diretório da loja) saem agora, e a recarga dirigida traz a lista nova. As
 * telas antigas de venda saem do app, mas nenhuma tabela antiga é apagada (histórico e envios antigos continuam).
 */
const V9 = `
ALTER TABLE clientes ADD COLUMN documento TEXT;
ALTER TABLE clientes ADD COLUMN documento_tipo TEXT;
ALTER TABLE clientes ADD COLUMN indicado_por_cliente_id TEXT;
ALTER TABLE clientes ADD COLUMN indicado_por_nome TEXT;
ALTER TABLE clientes ADD COLUMN como_conheceu TEXT;
ALTER TABLE clientes ADD COLUMN comissao_percentual TEXT;
ALTER TABLE clientes ADD COLUMN foto_hash TEXT;
ALTER TABLE clientes ADD COLUMN foto_documento_frente_hash TEXT;
ALTER TABLE clientes ADD COLUMN foto_documento_verso_hash TEXT;
CREATE INDEX clientes_por_documento ON clientes (documento);

ALTER TABLE movimentos_remessa ADD COLUMN nota_id TEXT;
ALTER TABLE movimentos_remessa ADD COLUMN acerto_nota_id TEXT;
CREATE INDEX movimentos_remessa_da_nota ON movimentos_remessa (nota_id);

-- O diretório da loja não é mais do representante (ADR-0016, item 4): saem as cópias do servidor de cliente que não é dele.
-- O vendedor é conhecido pelas maletas que recebeu; a recarga dirigida traz de volta a lista própria.
DELETE FROM clientes
 WHERE origem_local = 'SERVIDOR'
   AND (vendedor_responsavel_id IS NULL
        OR vendedor_responsavel_id NOT IN (SELECT vendedor_id FROM remessas WHERE vendedor_id IS NOT NULL));

CREATE TABLE semanas_vendedor (${CONTROLE},
  vendedor_id TEXT, numero INTEGER NOT NULL, aberta_em TEXT, fechada_em TEXT, situacao TEXT, dispositivo_id TEXT
);
CREATE INDEX semanas_vendedor_por_numero ON semanas_vendedor (numero);

CREATE TABLE notas_consignado (${CONTROLE},
  vendedor_id TEXT, semana_id TEXT NOT NULL, sequencia INTEGER NOT NULL, cliente_id TEXT NOT NULL, saida_em TEXT,
  data_cobranca TEXT, nota_origem_id TEXT, situacao TEXT, cotacao_id TEXT, dispositivo_id TEXT, hora_ajustada INTEGER
);
CREATE INDEX notas_consignado_da_semana ON notas_consignado (semana_id);
CREATE INDEX notas_consignado_do_cliente ON notas_consignado (cliente_id);

CREATE TABLE reposicoes_nota (${CONTROLE},
  nota_id TEXT NOT NULL, feita_em TEXT, dispositivo_id TEXT, hora_ajustada INTEGER
);
CREATE INDEX reposicoes_nota_da_nota ON reposicoes_nota (nota_id);

CREATE TABLE trocas_nota (${CONTROLE},
  nota_id TEXT NOT NULL, trocada_em TEXT, dispositivo_id TEXT, hora_ajustada INTEGER
);
CREATE INDEX trocas_nota_da_nota ON trocas_nota (nota_id);

CREATE TABLE itens_nota_consignado (${CONTROLE},
  nota_id TEXT NOT NULL, produto_id TEXT NOT NULL, remessa_id TEXT NOT NULL, origem TEXT, quantidade INTEGER NOT NULL,
  preco_unitario TEXT, reposicao_id TEXT, troca_id TEXT
);
CREATE INDEX itens_nota_consignado_da_nota ON itens_nota_consignado (nota_id);
CREATE INDEX itens_nota_consignado_da_maleta ON itens_nota_consignado (remessa_id, produto_id);

CREATE TABLE remarcacoes_nota (${CONTROLE},
  nota_id TEXT NOT NULL, data_cobranca_anterior TEXT, data_cobranca_nova TEXT, remarcada_em TEXT, dispositivo_id TEXT,
  hora_ajustada INTEGER
);
CREATE INDEX remarcacoes_nota_da_nota ON remarcacoes_nota (nota_id);

CREATE TABLE acertos_nota (${CONTROLE},
  nota_id TEXT NOT NULL, vendedor_id TEXT, semana_id TEXT, acertado_em TEXT, comissao_percentual TEXT, itens_dx INTEGER,
  itens_dv INTEGER, itens_vd INTEGER, valor_dx TEXT, valor_dv TEXT, valor_vd TEXT, comissao TEXT, bruto TEXT,
  pendencias_anteriores TEXT, total_a_receber TEXT, total_pago TEXT, ficou_devendo TEXT, recibo_id TEXT, cotacao_id TEXT,
  dispositivo_id TEXT, hora_ajustada INTEGER, estornado_em TEXT, estorno_id TEXT, motivo_estorno TEXT
);
CREATE INDEX acertos_nota_da_nota ON acertos_nota (nota_id);
CREATE INDEX acertos_nota_da_semana ON acertos_nota (semana_id);

CREATE TABLE itens_acerto_nota (${CONTROLE},
  acerto_id TEXT NOT NULL, produto_id TEXT NOT NULL, remessa_id TEXT NOT NULL, qtd_dx INTEGER, qtd_dv INTEGER,
  preco_unitario TEXT
);
CREATE INDEX itens_acerto_nota_do_acerto ON itens_acerto_nota (acerto_id);

CREATE TABLE recibos_consignado (${CONTROLE},
  vendedor_id TEXT, cliente_id TEXT NOT NULL, semana_id TEXT, sequencia INTEGER NOT NULL, acerto_id TEXT, pago_em TEXT,
  total TEXT, cotacao_id TEXT, dispositivo_id TEXT, hora_ajustada INTEGER, estornado_em TEXT, estorno_id TEXT,
  motivo_estorno TEXT
);
CREATE INDEX recibos_consignado_do_cliente ON recibos_consignado (cliente_id);
CREATE INDEX recibos_consignado_da_semana ON recibos_consignado (semana_id);

CREATE TABLE formas_recibo_consignado (${CONTROLE},
  recibo_id TEXT NOT NULL, forma TEXT, valor TEXT
);
CREATE INDEX formas_recibo_consignado_do_recibo ON formas_recibo_consignado (recibo_id);

CREATE TABLE aplicacoes_recibo (${CONTROLE},
  recibo_id TEXT NOT NULL, acerto_id TEXT NOT NULL, valor TEXT, estorna_id TEXT
);
CREATE INDEX aplicacoes_recibo_do_recibo ON aplicacoes_recibo (recibo_id);
CREATE INDEX aplicacoes_recibo_do_acerto ON aplicacoes_recibo (acerto_id);

-- Só do computador (nunca vão ao servidor): o Novo Pedido em montagem (bipes, diminuir, remover); a nota só nasce no servidor
-- ao Finalizar (ADR-0016, item 5).
CREATE TABLE rascunhos_nota (
  id TEXT PRIMARY KEY NOT NULL,
  cliente_id TEXT,
  remessa_id TEXT,
  data_cobranca TEXT,
  criado_em TEXT NOT NULL,
  atualizado_em TEXT NOT NULL
);
CREATE TABLE itens_rascunho_nota (
  id TEXT PRIMARY KEY NOT NULL,
  rascunho_id TEXT NOT NULL,
  produto_id TEXT NOT NULL,
  remessa_id TEXT NOT NULL,
  quantidade INTEGER NOT NULL CHECK (quantidade >= 1),
  preco_unitario TEXT NOT NULL,
  ordem INTEGER NOT NULL
);
CREATE INDEX itens_rascunho_nota_do_rascunho ON itens_rascunho_nota (rascunho_id, ordem);

-- O contador semana-sequência, um por semana, compartilhado por notas e recibos (ADR-0016, item 3): só há um computador
-- ativo por representante.
CREATE TABLE contadores_da_semana (
  semana_id TEXT PRIMARY KEY NOT NULL,
  proxima_sequencia INTEGER NOT NULL CHECK (proxima_sequencia >= 1)
);

-- Fotos do cliente que ainda não subiram (ou subiram e ficam no computador): arquivo criptografado, uma linha por foto.
CREATE TABLE fila_fotos_cliente (
  cliente_id TEXT NOT NULL,
  tipo TEXT NOT NULL CHECK (tipo IN ('cliente', 'documento-frente', 'documento-verso')),
  hash TEXT NOT NULL,
  caminho TEXT NOT NULL,
  tentativas INTEGER NOT NULL DEFAULT 0,
  enviada_em TEXT,
  PRIMARY KEY (cliente_id, tipo)
);

INSERT INTO estado_sync (chave, valor)
  SELECT 'recarga_dirigida', lista FROM (
    SELECT json_group_array(t) AS lista FROM (
      SELECT t FROM (
        SELECT key AS k, value AS t FROM json_each(
          coalesce((SELECT valor FROM estado_sync WHERE chave = 'recarga_dirigida'), '[]'))
        UNION ALL
        SELECT 1000 + key AS k, value AS t FROM json_each('${JSON.stringify(TABELAS_DA_RECARGA_DIRIGIDA_V9)}')
      ) GROUP BY t ORDER BY min(k)
    )
  )
   WHERE EXISTS (SELECT 1 FROM estado_sync WHERE chave = 'cursor' AND valor IS NOT NULL)
  ON CONFLICT (chave) DO UPDATE SET valor = excluded.valor;
`;

/** As tabelas que a recarga dirigida da versão 10 traz de novo (tarefa 9.3): só `remessas`, que ganhou o código e a praça. */
export const TABELAS_DA_RECARGA_DIRIGIDA_V10 = Object.freeze(['remessas']);

/**
 * Código da maleta e nome da praça (tarefa 9.3; V053 do servidor, ADR-0016, D11 e D12): duas colunas novas em `remessas` e a
 * recarga dirigida dela, porque o computador que já tinha cursor não receberia o código que o servidor gerou antes.
 */
const V10 = `
ALTER TABLE remessas ADD COLUMN codigo TEXT;
ALTER TABLE remessas ADD COLUMN praca_nome TEXT;

INSERT INTO estado_sync (chave, valor)
  SELECT 'recarga_dirigida', lista FROM (
    SELECT json_group_array(t) AS lista FROM (
      SELECT t FROM (
        SELECT key AS k, value AS t FROM json_each(
          coalesce((SELECT valor FROM estado_sync WHERE chave = 'recarga_dirigida'), '[]'))
        UNION ALL
        SELECT 1000 + key AS k, value AS t FROM json_each('${JSON.stringify(TABELAS_DA_RECARGA_DIRIGIDA_V10)}')
      ) GROUP BY t ORDER BY min(k)
    )
  )
   WHERE EXISTS (SELECT 1 FROM estado_sync WHERE chave = 'cursor' AND valor IS NOT NULL)
  ON CONFLICT (chave) DO UPDATE SET valor = excluded.valor;
`;

/**
 * Numeração separada de notas e recibos (tarefa 9.8; V054 do servidor, ADR-0016, D2): o contador de cada semana passa a ter
 * uma coluna para o recibo. A coluna antiga continua sendo o contador das NOTAS (já está à frente de toda nota); o recibo
 * continua do maior recibo gravado mais um. Nada vem do servidor, então não há recarga.
 */
/** As tabelas que a recarga dirigida da versão 11 traz de novo (tarefa 9.8): `acertos_nota`, que ganhou `registrado_em`. */
export const TABELAS_DA_RECARGA_DIRIGIDA_V11 = Object.freeze(['acertos_nota']);

/**
 * Tarefa 9.8 (V054 e V055 do servidor): contador próprio do recibo (numeração separada de notas e recibos) e `registrado_em`
 * no acerto (quando foi lançado; `acertado_em` passa a ser a Data do acerto escolhida). Para os acertos que o computador já
 * tem, `registrado_em = acertado_em` (a ordem dos débitos de hoje não muda) e a recarga dirigida traz a coluna do servidor.
 */
const V11 = `
ALTER TABLE contadores_da_semana ADD COLUMN proxima_sequencia_recibo INTEGER
  CHECK (proxima_sequencia_recibo IS NULL OR proxima_sequencia_recibo >= 1);

ALTER TABLE acertos_nota ADD COLUMN registrado_em TEXT;
UPDATE acertos_nota SET registrado_em = acertado_em WHERE registrado_em IS NULL;

INSERT INTO estado_sync (chave, valor)
  SELECT 'recarga_dirigida', lista FROM (
    SELECT json_group_array(t) AS lista FROM (
      SELECT t FROM (
        SELECT key AS k, value AS t FROM json_each(
          coalesce((SELECT valor FROM estado_sync WHERE chave = 'recarga_dirigida'), '[]'))
        UNION ALL
        SELECT 1000 + key AS k, value AS t FROM json_each('${JSON.stringify(TABELAS_DA_RECARGA_DIRIGIDA_V11)}')
      ) GROUP BY t ORDER BY min(k)
    )
  )
   WHERE EXISTS (SELECT 1 FROM estado_sync WHERE chave = 'cursor' AND valor IS NOT NULL)
  ON CONFLICT (chave) DO UPDATE SET valor = excluded.valor;
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
  {
    versao: 4,
    descricao:
      'contas a receber do cliente (parcelas e baixas), pagamento posterior e a recarga dirigida',
    sql: V4,
  },
  {
    versao: 5,
    descricao: 'valor abatido da devolução e a devolução da baixa de parcela (venda a prazo)',
    sql: V5,
  },
  {
    versao: 6,
    descricao: 'acerto e a contagem dele (iniciar, contar e desistir) e a recarga dirigida',
    sql: V6,
  },
  {
    versao: 7,
    descricao:
      'fechamento do acerto (totais, comissão, itens, eventos e pendências) e a recarga dirigida',
    sql: V7,
  },
  {
    versao: 8,
    descricao:
      'dinheiro do vendedor (recebimentos, contas do nível 2, pago e crédito do acerto) e a recarga dirigida',
    sql: V8,
  },
  {
    versao: 9,
    descricao:
      'consignado ao cliente (semanas, notas, acertos, recibos), lista própria de clientes, rascunho, contador e fila de fotos',
    sql: V9,
  },
  {
    versao: 10,
    descricao:
      'código da maleta e nome da praça (relatórios do consignado) e a recarga dirigida das maletas',
    sql: V10,
  },
  {
    versao: 11,
    descricao:
      'contador próprio do recibo, data do acerto (registrado_em) e a recarga dirigida dos acertos',
    sql: V11,
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
  // Fase 7 (tarefa 7.3, migração local 4).
  'contas_receber',
  'baixas_receber',
  // Fase 7 (tarefa 7.4, migração local 6).
  'acertos',
  'leituras_acerto',
  // Fase 7 (tarefa 7.5, migração local 7).
  'itens_acerto',
  'acerto_eventos',
  'comissoes',
  'pendencias_acerto',
  // Fase 7 (tarefa 7.6, migração local 8).
  'recebimentos_vendedor',
  // Consignado ao cliente (tarefa 9.2, migração local 9).
  'semanas_vendedor',
  'notas_consignado',
  'reposicoes_nota',
  'trocas_nota',
  'itens_nota_consignado',
  'remarcacoes_nota',
  'acertos_nota',
  'itens_acerto_nota',
  'recibos_consignado',
  'formas_recibo_consignado',
  'aplicacoes_recibo',
] as const);
