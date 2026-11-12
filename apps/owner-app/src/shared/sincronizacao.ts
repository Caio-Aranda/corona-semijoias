/**
 * Computadores do vendedor, envios com problema e vendas de computador bloqueado na ponte (tarefa 5.10). É a lista que o
 * processo principal usa para conferir os dados antes de falar com a API; a tela usa os mesmos nomes.
 */

export const OPERACOES_DE_SINCRONIZACAO = [
  'sincronizacao.aparelhos',
  'sincronizacao.liberarAparelho',
  'sincronizacao.bloquearAparelho',
  'sincronizacao.envios',
  'sincronizacao.tentarDeNovo',
  'sincronizacao.descartarEnvio',
  'sincronizacao.quarentena',
  'sincronizacao.aprovarQuarentena',
  'sincronizacao.descartarQuarentena',
  'sincronizacao.valoresSubstituidos',
] as const;
export type OperacaoDeSincronizacao = (typeof OPERACOES_DE_SINCRONIZACAO)[number];

export const MAXIMO_DO_MOTIVO_DO_ENVIO = 500;

export type ParametrosDeSincronizacao =
  | { operacao: 'sincronizacao.aparelhos'; vendedorId: string }
  | {
      operacao:
        | 'sincronizacao.liberarAparelho'
        | 'sincronizacao.tentarDeNovo'
        | 'sincronizacao.aprovarQuarentena';
      id: string;
    }
  | { operacao: 'sincronizacao.bloquearAparelho'; id: string; motivo?: string }
  | {
      operacao: 'sincronizacao.descartarEnvio' | 'sincronizacao.descartarQuarentena';
      id: string;
      motivo: string;
    }
  | {
      operacao:
        'sincronizacao.envios' | 'sincronizacao.quarentena' | 'sincronizacao.valoresSubstituidos';
    };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const idValido = (v: unknown): v is string => typeof v === 'string' && UUID.test(v);

function motivoValido(v: unknown): v is string {
  return typeof v === 'string' && v.trim() !== '' && v.length <= MAXIMO_DO_MOTIVO_DO_ENVIO;
}

export function conferirSincronizacao(
  operacao: OperacaoDeSincronizacao,
  p: Record<string, unknown>,
): ParametrosDeSincronizacao | null {
  const chaves = Object.keys(p);
  const soCom = (...nomes: string[]) => chaves.every((k) => nomes.includes(k));
  switch (operacao) {
    case 'sincronizacao.envios':
    case 'sincronizacao.quarentena':
    case 'sincronizacao.valoresSubstituidos':
      return chaves.length === 0 ? { operacao } : null;
    case 'sincronizacao.aparelhos':
      return soCom('vendedorId') && idValido(p.vendedorId)
        ? { operacao, vendedorId: p.vendedorId }
        : null;
    case 'sincronizacao.liberarAparelho':
    case 'sincronizacao.tentarDeNovo':
    case 'sincronizacao.aprovarQuarentena':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'sincronizacao.bloquearAparelho': {
      if (!soCom('id', 'motivo') || !idValido(p.id)) return null;
      if (p.motivo === undefined || p.motivo === '') return { operacao, id: p.id };
      if (typeof p.motivo !== 'string' || p.motivo.length > MAXIMO_DO_MOTIVO_DO_ENVIO) return null;
      return { operacao, id: p.id, motivo: p.motivo };
    }
    case 'sincronizacao.descartarEnvio':
    case 'sincronizacao.descartarQuarentena':
      return soCom('id', 'motivo') && idValido(p.id) && motivoValido(p.motivo)
        ? { operacao, id: p.id, motivo: p.motivo }
        : null;
  }
}
