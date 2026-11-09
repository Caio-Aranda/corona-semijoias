import type { DadosDeEntrada, DadosDeNovaSenha, PedidoDeChamada } from './ponte';
import { OPERACOES_DA_API } from './ponte';

/** Tamanhos máximos aceitos na ponte. O servidor tem a palavra final sobre a senha (72 bytes). */
const MAX_USUARIO = 200;
const MAX_SENHA = 1000;

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const ehObjeto = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

/** Aceita só o formato esperado e devolve uma cópia limpa; qualquer outra coisa vira `null`. */
export function validarEntrada(v: unknown): DadosDeEntrada | null {
  if (!ehObjeto(v)) return null;
  const { usuario, senha } = v;
  if (!ehTexto(usuario) || !ehTexto(senha)) return null;
  if (usuario.length === 0 || usuario.length > MAX_USUARIO) return null;
  if (senha.length === 0 || senha.length > MAX_SENHA) return null;
  return { usuario, senha };
}

export function validarNovaSenha(v: unknown): DadosDeNovaSenha | null {
  if (!ehObjeto(v)) return null;
  const { senhaNova } = v;
  if (!ehTexto(senhaNova) || senhaNova.length === 0 || senhaNova.length > MAX_SENHA) return null;
  return { senhaNova };
}

export function validarChamada(v: unknown): PedidoDeChamada | null {
  if (!ehObjeto(v)) return null;
  const { operacao } = v;
  if (!ehTexto(operacao)) return null;
  const permitida = (OPERACOES_DA_API as readonly string[]).includes(operacao);
  return permitida ? { operacao: operacao as PedidoDeChamada['operacao'] } : null;
}
