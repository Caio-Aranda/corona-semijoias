import { conferirValorDoRecebimento, type ValorDoRecebimento } from './valor-do-recebimento';
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
  'fechamento.voltas',
  'cobranca.pagar',
  'cobranca.desfazer',
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

/** Como o representante pagou a cobrança da falta (as formas do caixa; §23.10, item 1a). */
export const FORMAS_DA_COBRANCA = ['DINHEIRO', 'PIX'] as const;
export type FormaDaCobranca = (typeof FORMAS_DA_COBRANCA)[number];

/** O que a dona decide para cada peça que falta ao concluir o fechamento (§23.10, item 1). */
export const DECISOES_DA_FALTA = ['COBRAR_DO_REPRESENTANTE', 'PERDA_DA_LOJA'] as const;
export type DecisaoDaFalta = (typeof DECISOES_DA_FALTA)[number];
export const MAXIMO_DO_MOTIVO = 500;

export interface DecisaoParaEnviar {
  id: string;
  produtoId: string;
  decisao: DecisaoDaFalta;
  motivo?: string;
}

export type ParametrosDeConsignado =
  | { operacao: 'telas.ler' }
  | {
      operacao:
        | 'fechamento.iniciar'
        | 'fechamento.ler'
        | 'consignado.semanas'
        | 'consignado.semana'
        | 'consignado.nota'
        | 'consignado.cliente'
        | 'consignado.alertaVisto';
      id: string;
    }
  | {
      operacao: 'fechamento.leituras' | 'fechamento.voltas';
      id: string;
      leituras: LeituraParaEnviar[];
    }
  | { operacao: 'fechamento.concluir'; id: string; decisoes: DecisaoParaEnviar[] }
  | {
      operacao: 'cobranca.pagar';
      /** A falta cobrada. */
      id: string;
      pagamentoId: string;
      forma: FormaDaCobranca;
      valor: ValorDoRecebimento;
      cotacaoId?: string;
    }
  | { operacao: 'cobranca.desfazer'; id: string; estornoId: string; motivo: string }
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
    case 'consignado.semanas':
    case 'consignado.semana':
    case 'consignado.nota':
    case 'consignado.cliente':
    case 'consignado.alertaVisto':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'fechamento.concluir': {
      if (!soCom('id', 'decisoes') || !idValido(p.id)) return null;
      const lista = p.decisoes ?? [];
      if (!Array.isArray(lista) || lista.length > MAXIMO_DE_LEITURAS_NO_LOTE) return null;
      const decisoes: DecisaoParaEnviar[] = [];
      for (const d of lista as unknown[]) {
        if (typeof d !== 'object' || d === null) return null;
        const { id, produtoId, decisao, motivo, ...resto } = d as Record<string, unknown>;
        if (Object.keys(resto).length > 0) return null;
        if (!idValido(id) || !idValido(produtoId)) return null;
        if (!(DECISOES_DA_FALTA as readonly unknown[]).includes(decisao)) return null;
        if (
          motivo !== undefined &&
          (typeof motivo !== 'string' || motivo.length > MAXIMO_DO_MOTIVO)
        ) {
          return null;
        }
        decisoes.push({
          id,
          produtoId,
          decisao: decisao as DecisaoDaFalta,
          ...(typeof motivo === 'string' ? { motivo } : {}),
        });
      }
      return { operacao, id: p.id, decisoes };
    }
    case 'cobranca.pagar': {
      if (!soCom('id', 'pagamentoId', 'forma', 'valor', 'moeda', 'valorNaMoeda', 'cotacaoId'))
        return null;
      if (!idValido(p.id) || !idValido(p.pagamentoId)) return null;
      if (!(FORMAS_DA_COBRANCA as readonly unknown[]).includes(p.forma)) return null;
      if (p.cotacaoId !== undefined && !idValido(p.cotacaoId)) return null;
      const valor = conferirValorDoRecebimento({
        ...(p.valor !== undefined ? { valor: p.valor } : {}),
        ...(p.moeda !== undefined ? { moeda: p.moeda } : {}),
        ...(p.valorNaMoeda !== undefined ? { valorNaMoeda: p.valorNaMoeda } : {}),
      });
      if (!valor) return null;
      return {
        operacao,
        id: p.id,
        pagamentoId: p.pagamentoId,
        forma: p.forma as FormaDaCobranca,
        valor,
        ...(typeof p.cotacaoId === 'string' ? { cotacaoId: p.cotacaoId } : {}),
      };
    }
    case 'cobranca.desfazer': {
      if (!soCom('id', 'estornoId', 'motivo') || !idValido(p.id) || !idValido(p.estornoId))
        return null;
      if (
        typeof p.motivo !== 'string' ||
        p.motivo.trim() === '' ||
        p.motivo.length > MAXIMO_DO_MOTIVO
      ) {
        return null;
      }
      return { operacao, id: p.id, estornoId: p.estornoId, motivo: p.motivo };
    }
    case 'fechamento.voltas':
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
