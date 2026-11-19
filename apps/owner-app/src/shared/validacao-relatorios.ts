/** Conferência, no processo principal, dos dados dos relatórios de cadastro. */
import {
  NOMES_DOS_RELATORIOS,
  SITUACOES_DO_RELATORIO,
  type ConsultaDeRelatorio,
  type NomeDoRelatorio,
  type SituacaoDoRelatorio,
} from './relatorios';

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
const RECORTE = /^[a-z0-9-]{1,40}$/;
const VALOR = /^\d{1,12}(\.\d{1,2})?$/;
const SIM_NAO = ['SIM', 'NAO'];
/** "tipo" é do resultado das importações ou do tipo do lançamento no estoque. */
const TIPOS_DA_MALETA = ['NOVA', 'REPOSICAO'];
/** Devolução e troca (tarefa 6.10): o tipo de cada relatório. */
const TIPOS_DAS_DEVOLUCOES = ['DEVOLUCAO', 'TROCA'];
const TIPOS_DOS_CREDITOS = ['TROCA', 'DEVOLUCAO', 'TRANSFERENCIA'];
const TIPOS_DAS_AUTORIZACOES = ['FORA_DO_PRAZO', 'ACIMA_DO_VALOR'];
/** Financeiro (tarefa 7.12): o tipo da pendência do acerto. */
const TIPOS_DA_PENDENCIA = [
  'FALTA',
  'DEFEITO',
  'DEVOLUCAO_NAO_ACEITA',
  'CREDITO_SEM_SALDO_NAO_ACEITO',
];
const TODOS_OS_TIPOS_DE_DEVOLUCAO = [...TIPOS_DOS_CREDITOS, ...TIPOS_DAS_AUTORIZACOES];
const TIPOS_DO_LANCAMENTO = ['ENTRADA', 'SAIDA', 'AJUSTE', 'REMESSA', 'DEVOLUCAO', 'AVARIA'];
const ESCOLHAS: [
  (
    | 'comRestricao'
    | 'comProdutos'
    | 'comVendedor'
    | 'estoque'
    | 'foto'
    | 'tipo'
    | 'resultado'
    | 'quem'
    | 'origem'
    | 'divergencia'
    | 'motivo'
    | 'forma'
    | 'devedor'
    | 'momento'
    | 'linha'
    | 'comProblema'
    | 'aplicativo'
  ),
  string[],
][] = [
  ['comRestricao', SIM_NAO],
  ['comProdutos', SIM_NAO],
  ['comVendedor', SIM_NAO],
  ['estoque', ['ZERADO', 'ABAIXO_DO_MINIMO', 'COM_ESTOQUE']],
  ['foto', ['COM', 'SEM']],
  [
    'tipo',
    [
      'PLANILHA',
      'FOTOS',
      ...TIPOS_DO_LANCAMENTO,
      ...TIPOS_DA_MALETA,
      ...TODOS_OS_TIPOS_DE_DEVOLUCAO,
      ...TIPOS_DA_PENDENCIA,
    ],
  ],
  [
    'resultado',
    [
      'CRIADO',
      'ATUALIZADO',
      'JA_EXISTIA',
      'RECUSADO',
      // Acessos (tarefa 8.9)
      'SUCESSO',
      'SENHA_INCORRETA',
      'BLOQUEADO',
      'USUARIO_DESATIVADO',
      'APP_INCOMPATIVEL',
      'SENHA_TEMPORARIA_VENCIDA',
      'PEDIDO_INVALIDO',
      'BLOQUEIO_LIMPO',
    ],
  ],
  ['quem', ['TODOS', 'VENDEDORES', 'CLIENTES']],
  [
    'origem',
    [
      'MANUAL',
      'AJUSTE_MANUAL',
      'COMPRA',
      'INVENTARIO',
      'MALETA',
      'ESTORNO',
      'RECEBIMENTO_VENDEDOR',
      'ACERTO_A_FAVOR_DO_VENDEDOR',
      'PAGAMENTO_FORNECEDOR',
    ],
  ],
  ['divergencia', SIM_NAO],
  ['motivo', ['ARREPENDIMENTO', 'DEFEITO', 'TAMANHO', 'OUTRO']],
  ['forma', ['DINHEIRO', 'PIX', 'CARTAO', 'CHEQUE', 'BOLETO']],
  ['devedor', ['CLIENTE', 'VENDEDOR']],
  ['momento', ['ACERTO', 'AVULSO']],
  ['linha', ['VENDAS', 'DEVOLUCOES', 'CUSTO', 'COMISSOES', 'PERDAS', 'COBRADO', 'CAIXA']],
  ['comProblema', SIM_NAO],
  ['aplicativo', ['gestao', 'vendas']],
];

export interface ParametrosDeRelatorio {
  operacao: 'relatorios.cadastros';
  relatorio: NomeDoRelatorio;
  consulta: ConsultaDeRelatorio;
}

const CAMPOS = [
  'relatorio',
  'busca',
  'categoriaId',
  'fornecedorId',
  'pracaId',
  'vendedorId',
  'restricaoId',
  'situacao',
  'de',
  'ate',
  'mes',
  'limiteDe',
  'limiteAte',
  'comRestricao',
  'estoque',
  'precoDe',
  'precoAte',
  'foto',
  'comProdutos',
  'comVendedor',
  'tipo',
  'resultado',
  'quem',
  'recorte',
  'produtoId',
  'usuarioId',
  'origem',
  'dias',
  'inventarioId',
  'divergencia',
  'compraId',
  'semanaId',
  'remessaId',
  'diasDeAtraso',
  'motivo',
  'forma',
  'devedor',
  'momento',
  'linha',
  'comProblema',
  'aplicativo',
  'pagina',
  'tamanho',
];

export function conferirRelatorio(p: Record<string, unknown>): ParametrosDeRelatorio | null {
  if (!Object.keys(p).every((k) => CAMPOS.includes(k))) return null;
  if (!(NOMES_DOS_RELATORIOS as readonly unknown[]).includes(p.relatorio)) return null;
  const pagina = p.pagina ?? 0;
  const tamanho = p.tamanho ?? 20;
  if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
  if (typeof tamanho !== 'number' || !Number.isInteger(tamanho)) return null;
  if (tamanho < 1 || tamanho > 100) return null;
  const consulta: ConsultaDeRelatorio = { pagina, tamanho };

  if (p.busca !== undefined && p.busca !== '') {
    if (!ehTexto(p.busca) || p.busca.length > 200) return null;
    consulta.busca = p.busca;
  }
  for (const k of [
    'categoriaId',
    'fornecedorId',
    'pracaId',
    'vendedorId',
    'restricaoId',
    'produtoId',
    'usuarioId',
    'inventarioId',
    'compraId',
    'semanaId',
    'remessaId',
  ] as const) {
    if (p[k] !== undefined && p[k] !== '') {
      if (!ehTexto(p[k]) || !UUID.test(p[k])) return null;
      consulta[k] = p[k];
    }
  }
  if (p.situacao !== undefined && p.situacao !== '') {
    if (!(SITUACOES_DO_RELATORIO as readonly unknown[]).includes(p.situacao)) return null;
    consulta.situacao = p.situacao as SituacaoDoRelatorio;
  }
  for (const k of ['de', 'ate'] as const) {
    if (p[k] !== undefined && p[k] !== '') {
      if (!ehTexto(p[k]) || !DATA.test(p[k]) || Number.isNaN(Date.parse(p[k]))) return null;
      consulta[k] = p[k];
    }
  }
  for (const k of ['limiteDe', 'limiteAte', 'precoDe', 'precoAte'] as const) {
    if (p[k] !== undefined && p[k] !== '') {
      if (!ehTexto(p[k]) || !VALOR.test(p[k])) return null;
      consulta[k] = p[k];
    }
  }
  for (const [k, aceitos] of ESCOLHAS) {
    if (p[k] !== undefined && p[k] !== '') {
      if (!ehTexto(p[k]) || !aceitos.includes(p[k])) return null;
      consulta[k] = p[k];
    }
  }
  if (p.mes !== undefined && p.mes !== '') {
    if (typeof p.mes !== 'number' || !Number.isInteger(p.mes) || p.mes < 1 || p.mes > 12)
      return null;
    consulta.mes = p.mes;
  }
  if (p.dias !== undefined && p.dias !== '') {
    if (typeof p.dias !== 'number' || !Number.isInteger(p.dias) || p.dias < 1 || p.dias > 3650)
      return null;
    consulta.dias = p.dias;
  }
  if (p.diasDeAtraso !== undefined && p.diasDeAtraso !== '') {
    if (
      typeof p.diasDeAtraso !== 'number' ||
      !Number.isInteger(p.diasDeAtraso) ||
      p.diasDeAtraso < 0 ||
      p.diasDeAtraso > 365
    )
      return null;
    consulta.diasDeAtraso = p.diasDeAtraso;
  }
  // "tipo" das importações só vale nelas, e o do lançamento só no estoque.
  if (p.tipo !== undefined && p.tipo !== '') {
    const eDeImportacao = p.relatorio === 'resultado-das-importacoes';
    const eDeMaleta = typeof p.relatorio === 'string' && p.relatorio.startsWith('maleta-');
    const aceitos = eDeImportacao
      ? ['PLANILHA', 'FOTOS']
      : eDeMaleta
        ? TIPOS_DA_MALETA
        : p.relatorio === 'financeiro-pendencias'
          ? TIPOS_DA_PENDENCIA
          : p.relatorio === 'financeiro-caixa'
            ? ['ENTRADA', 'SAIDA']
            : p.relatorio === 'devolucao-por-motivo'
              ? TIPOS_DAS_DEVOLUCOES
              : p.relatorio === 'devolucao-creditos'
                ? TIPOS_DOS_CREDITOS
                : p.relatorio === 'devolucao-autorizacoes'
                  ? TIPOS_DAS_AUTORIZACOES
                  : TIPOS_DO_LANCAMENTO;
    if (!ehTexto(p.tipo) || !aceitos.includes(p.tipo)) return null;
  }
  if (p.recorte !== undefined && p.recorte !== '') {
    if (!ehTexto(p.recorte) || !RECORTE.test(p.recorte)) return null;
    consulta.recorte = p.recorte;
  }
  // A lista das peças de uma compra só existe para uma compra.
  if (p.relatorio === 'compras-pecas' && !consulta.compraId) return null;
  // O fechamento da maleta só existe para uma maleta.
  if (p.relatorio === 'maleta-fechamento' && !consulta.remessaId) return null;
  return { operacao: 'relatorios.cadastros', relatorio: p.relatorio as NomeDoRelatorio, consulta };
}
