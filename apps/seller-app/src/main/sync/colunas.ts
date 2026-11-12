import { TABELAS_SINCRONIZAVEIS } from '../banco/migracoes';

export type TabelaSincronizavel = (typeof TABELAS_SINCRONIZAVEIS)[number];

/**
 * A lista explícita de colunas que o Corona Vendas aceita do pull, por tabela (a mesma do `RegistroDeTabelas` do
 * servidor, ADR-0013 item 4, mais `created_at` e `updated_at`). `id`, `version` e `deleted_at` vêm fora dos `campos`.
 *
 * Coluna fora desta lista recusa o lote inteiro: nenhum custo, fornecedor ou estoque da loja entra no computador do
 * vendedor, nem por engano do servidor (o teste `colunas.test.ts` confere).
 */
export const COLUNAS_DO_PULL: Readonly<Record<TabelaSincronizavel, readonly string[]>> =
  Object.freeze({
    cotacoes: [
      'data_cotacao',
      'usd_compra',
      'usd_venda',
      'usd_paridade_compra',
      'usd_paridade_venda',
      'pyg_compra',
      'pyg_venda',
      'pyg_paridade_compra',
      'pyg_paridade_venda',
      'buscada_em',
      'fonte',
    ],
    categorias: ['nome', 'ativo'],
    semanas: ['numero', 'inicio', 'fim', 'situacao'],
    clientes: [
      'nome',
      'cpf',
      'data_nascimento',
      'telefone',
      'whatsapp',
      'ativo',
      'endereco_logradouro',
      'endereco_numero',
      'endereco_complemento',
      'endereco_bairro',
      'endereco_cidade',
      'endereco_uf',
      'endereco_cep',
      'observacoes',
      'vendedor_responsavel_id',
    ],
    produtos: ['nome', 'referencia', 'categoria_id', 'preco_venda', 'ativo', 'foto_principal_hash'],
    produto_codigos: ['produto_id', 'codigo', 'tipo', 'principal', 'ativo'],
    produtos_fotos: ['produto_id', 'arquivo_hash', 'principal', 'ordem'],
    remessas: [
      'vendedor_id',
      'semana_id',
      'praca_id',
      'tipo',
      'status',
      'dispositivo_id',
      'total_pecas',
      'total_valor',
      'enviada_em',
      'motivo_cancelamento',
    ],
    itens_remessa: ['remessa_id', 'produto_id', 'qtd_enviada', 'preco_remessa'],
    movimentos_remessa: [
      'remessa_id',
      'produto_id',
      'tipo',
      'quantidade',
      'venda_id',
      'dispositivo_id',
      'estorna_id',
    ],
    vendas: [
      'vendedor_id',
      'cliente_id',
      'remessa_id',
      'status',
      'subtotal',
      'desconto',
      'total',
      'cotacao_id',
      'vendida_em',
      'estorno_id',
      'estornada_em',
      'motivo_estorno',
    ],
    itens_venda: ['venda_id', 'produto_id', 'quantidade', 'preco_unitario', 'desconto'],
    pagamentos_cliente: ['venda_id', 'tipo', 'forma', 'valor', 'pago_em'],
    conferencias: ['remessa_id', 'vendedor_id', 'dispositivo_id', 'divergencias', 'conferida_em'],
    itens_conferencia: ['conferencia_id', 'produto_id', 'esperado', 'contado'],
  });

/** Datas de controle que o servidor manda junto dos campos de negócio. */
export const COLUNAS_DE_DATA = Object.freeze(['created_at', 'updated_at'] as const);

/** Marcadores que nunca podem aparecer no nome de uma coluna do computador do vendedor. */
export const MARCADORES_PROIBIDOS = Object.freeze(['custo', 'fornecedor', 'estoque']);

export function ehTabelaSincronizavel(nome: unknown): nome is TabelaSincronizavel {
  return typeof nome === 'string' && Object.prototype.hasOwnProperty.call(COLUNAS_DO_PULL, nome);
}

/** Todas as colunas que o aplicador grava numa tabela (negócio e datas). */
export function colunasGravaveis(tabela: TabelaSincronizavel): readonly string[] {
  return [...COLUNAS_DO_PULL[tabela], ...COLUNAS_DE_DATA];
}
