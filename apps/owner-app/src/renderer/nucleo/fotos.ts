/** Conferências da foto escolhida, colada ou arrastada, antes de enviar (o servidor confere de novo). */
import {
  EXTENSOES_DE_FOTO,
  TAMANHO_MAXIMO_DA_FOTO,
  extensaoDeFotoAceita,
} from '../../shared/produtos';
import { TEXTOS } from '../textos';

const T = TEXTOS.produtos.fotos;

const EXTENSAO_DO_TIPO: Record<string, string> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
};

export interface ArquivoEscolhido {
  name: string;
  size: number;
  type: string;
}

/** Foto colada da área de transferência costuma vir só como "image.png" ou sem extensão. */
export function nomeParaEnviar(a: ArquivoEscolhido): string {
  if (extensaoDeFotoAceita(a.name)) return a.name;
  const extensao = EXTENSAO_DO_TIPO[a.type];
  const base = a.name.replace(/\.[^.]*$/, '') || 'foto';
  return extensao ? `${base}.${extensao}` : a.name;
}

/** Devolve o texto do problema, ou `null` se a foto pode ser enviada. */
export function conferirArquivoDeFoto(a: ArquivoEscolhido): string | null {
  if (a.size === 0) return T.vazia;
  if (!extensaoDeFotoAceita(nomeParaEnviar(a))) return T.naoEFoto;
  if (a.size > TAMANHO_MAXIMO_DA_FOTO) return T.grandeDemais;
  return null;
}

export { EXTENSOES_DE_FOTO };
