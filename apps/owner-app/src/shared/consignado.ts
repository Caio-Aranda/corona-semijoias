/**
 * Consignado ao cliente na ponte (tarefa 9.4; ESPECIFICACAO §23; ADR-0016): o que o Corona Gestão mostra ou esconde, o
 * Fechamento da maleta (a dona bipa a maleta que voltou) e a visão das notas, semanas, clientes e alertas do consignado.
 * É a lista que o processo principal usa para conferir os dados antes de falar com a API. Nada aqui escreve dado do
 * consignado: as notas e os acertos nascem no aplicativo do representante; a dona só lê, bipa o Fechamento e marca alerta como visto.
 */

export const OPERACOES_DE_CONSIGNADO = [
  'telas.ler',
  'fechamento.iniciar',
  'fechamento.ler',
  'fechamento.leituras',
  'fechamento.concluir',
  'consignado.semanas',
  'consignado.semana',
  'consignado.notas',
  'consignado.nota',
  'consignado.cliente',
  'consignado.fotoDoCliente',
  'consignado.alertas',
  'consignado.alertaVisto',
] as const;
export type OperacaoDeConsignado = (typeof OPERACOES_DE_CONSIGNADO)[number];

export const TIPOS_DE_FOTO_DO_CLIENTE = ['cliente', 'documento-frente', 'documento-verso'] as const;
export type TipoDeFotoDoCliente = (typeof TIPOS_DE_FOTO_DO_CLIENTE)[number];

export const SITUACOES_DA_NOTA = ['EM_ABERTO', 'PENDENTE', 'FECHADO', 'REMARCADO'] as const;
export type SituacaoDaNota = (typeof SITUACOES_DA_NOTA)[number];

/** Quantos bipes o servidor aceita num lote (a tela manda de um em um, ou em lotes pequenos). */
export const MAXIMO_DE_LEITURAS_NO_LOTE = 200;
export const MAXIMO_DA_QUANTIDADE_BIPADA = 100_000;
export const MAXIMO_DO_TEXTO_DA_BUSCA = 100;

export interface LeituraParaEnviar {
  id: string;
  produtoId: string;
  quantidade: number;
}

export type ParametrosDeConsignado =
  | { operacao: 'telas.ler' }
  | {
      operacao:
        | 'fechamento.iniciar'
        | 'fechamento.ler'
        | 'fechamento.concluir'
        | 'consignado.semanas'
        | 'consignado.semana'
        | 'consignado.nota'
        | 'consignado.cliente'
        | 'consignado.alertaVisto';
      id: string;
    }
  | { operacao: 'fechamento.leituras'; id: string; leituras: LeituraParaEnviar[] }
  | {
      operacao: 'consignado.notas';
      filtros: {
        vendedorId?: string;
        semanaId?: string;
        situacao?: SituacaoDaNota;
        clienteId?: string;
        texto?: string;
      };
    }
  | { operacao: 'consignado.fotoDoCliente'; id: string; tipo: TipoDeFotoDoCliente }
  | { operacao: 'consignado.alertas'; soNaoVistos: boolean };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const idValido = (v: unknown): v is string => typeof v === 'string' && UUID.test(v);

export function conferirConsignado(
  operacao: OperacaoDeConsignado,
  p: Record<string, unknown>,
): ParametrosDeConsignado | null {
  const chaves = Object.keys(p);
  const soCom = (...nomes: string[]) => chaves.every((k) => nomes.includes(k));
  switch (operacao) {
    case 'telas.ler':
      return chaves.length === 0 ? { operacao } : null;
    case 'fechamento.iniciar':
    case 'fechamento.ler':
    case 'fechamento.concluir':
    case 'consignado.semanas':
    case 'consignado.semana':
    case 'consignado.nota':
    case 'consignado.cliente':
    case 'consignado.alertaVisto':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'fechamento.leituras': {
      if (!soCom('id', 'leituras') || !idValido(p.id) || !Array.isArray(p.leituras)) return null;
      if (p.leituras.length < 1 || p.leituras.length > MAXIMO_DE_LEITURAS_NO_LOTE) return null;
      const leituras: LeituraParaEnviar[] = [];
      for (const l of p.leituras as unknown[]) {
        if (typeof l !== 'object' || l === null) return null;
        const { id, produtoId, quantidade, ...resto } = l as Record<string, unknown>;
        if (Object.keys(resto).length > 0) return null;
        if (!idValido(id) || !idValido(produtoId)) return null;
        if (
          typeof quantidade !== 'number' ||
          !Number.isInteger(quantidade) ||
          quantidade < 1 ||
          quantidade > MAXIMO_DA_QUANTIDADE_BIPADA
        ) {
          return null;
        }
        leituras.push({ id, produtoId, quantidade });
      }
      return { operacao, id: p.id, leituras };
    }
    case 'consignado.notas': {
      if (!soCom('vendedorId', 'semanaId', 'situacao', 'clienteId', 'texto')) return null;
      const filtros: Extract<ParametrosDeConsignado, { operacao: 'consignado.notas' }>['filtros'] =
        {};
      for (const k of ['vendedorId', 'semanaId', 'clienteId'] as const) {
        const v = p[k];
        if (v === undefined || v === '') continue;
        if (!idValido(v)) return null;
        filtros[k] = v;
      }
      if (p.situacao !== undefined && p.situacao !== '') {
        if (!(SITUACOES_DA_NOTA as readonly unknown[]).includes(p.situacao)) return null;
        filtros.situacao = p.situacao as SituacaoDaNota;
      }
      if (p.texto !== undefined && p.texto !== '') {
        if (typeof p.texto !== 'string' || p.texto.length > MAXIMO_DO_TEXTO_DA_BUSCA) return null;
        filtros.texto = p.texto;
      }
      return { operacao, filtros };
    }
    case 'consignado.fotoDoCliente':
      return soCom('id', 'tipo') &&
        idValido(p.id) &&
        (TIPOS_DE_FOTO_DO_CLIENTE as readonly unknown[]).includes(p.tipo)
        ? { operacao, id: p.id, tipo: p.tipo as TipoDeFotoDoCliente }
        : null;
    case 'consignado.alertas': {
      if (!soCom('soNaoVistos')) return null;
      if (p.soNaoVistos !== undefined && typeof p.soNaoVistos !== 'boolean') return null;
      return { operacao, soNaoVistos: p.soNaoVistos === true };
    }
  }
}
