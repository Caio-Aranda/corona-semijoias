/**
 * Regras dos códigos da peça na tela (ADR-0007): texto livre, único sem diferenciar maiúsculas,
 * dígito verificador só para o tipo EAN-13 com 13 dígitos, leitor que age como teclado.
 */
import { TAMANHO_MAXIMO_DO_CODIGO, type TipoDeCodigo } from '../../shared/produtos';
import { TEXTOS_SOLTOS as SOLTOS } from '../textos-soltos';

/** Tira só os espaços das pontas; zeros à esquerda e o resto ficam como estão. */
export const normalizarCodigo = (c: string): string => c.trim();

/** Forma usada para comparar códigos (igual à unicidade do servidor). */
export const chaveDoCodigo = (c: string): string => normalizarCodigo(c).toLowerCase();

const SO_DIGITOS = /^\d+$/;

/** O 13º dígito confere com os 12 primeiros (pesos 1 e 3, alternados). */
export function digitoDoEan13Confere(codigo: string): boolean {
  if (codigo.length !== 13 || !SO_DIGITOS.test(codigo)) return false;
  let soma = 0;
  for (let i = 0; i < 12; i++) soma += Number(codigo[i]) * (i % 2 === 0 ? 1 : 3);
  return (10 - (soma % 10)) % 10 === Number(codigo[12]);
}

export interface AvisoDeCodigo {
  nivel: 'erro' | 'aviso';
  mensagem: string;
}

/**
 * Confere o que dá para conferir sem o servidor. Só o tipo EAN-13 tem dígito verificador; um EAN-13
 * com poucos dígitos costuma ser leitura cortada.
 */
export function avaliarCodigo(bruto: string, tipo: TipoDeCodigo): AvisoDeCodigo | null {
  const codigo = normalizarCodigo(bruto);
  if (codigo === '') return null;
  if (codigo.length > TAMANHO_MAXIMO_DO_CODIGO) {
    return { nivel: 'erro', mensagem: SOLTOS.codigos.longo };
  }
  if (tipo !== 'EAN13') return null;
  if (codigo.length === 13 && SO_DIGITOS.test(codigo)) {
    return digitoDoEan13Confere(codigo)
      ? null
      : {
          nivel: 'erro',
          mensagem: SOLTOS.codigos.naoConfere(codigo),
        };
  }
  if (SO_DIGITOS.test(codigo) && codigo.length < 13) {
    return {
      nivel: 'aviso',
      mensagem: SOLTOS.codigos.incompleto,
    };
  }
  return null;
}

/** Um código de barras de 13 números que confere é EAN-13; senão fica o tipo que já estava. */
export function tipoSugerido(codigo: string, atual: TipoDeCodigo): TipoDeCodigo {
  return digitoDoEan13Confere(normalizarCodigo(codigo)) ? 'EAN13' : atual;
}

export interface CodigoDaFicha {
  codigo: string;
  tipo: TipoDeCodigo;
  principal: boolean;
  fornecedorId?: string;
}

/** Erros da lista inteira de uma peça (mensagens do glossário, parte 6). */
export function conferirListaDeCodigos(lista: readonly CodigoDaFicha[]): string[] {
  const erros: string[] = [];
  const preenchidos = lista.filter((c) => normalizarCodigo(c.codigo) !== '');
  if (preenchidos.length === 0) return [SOLTOS.codigos.semCodigo];
  const vistos = new Set<string>();
  for (const c of preenchidos) {
    const chave = chaveDoCodigo(c.codigo);
    if (vistos.has(chave)) {
      erros.push(SOLTOS.codigos.repetido(normalizarCodigo(c.codigo)));
    }
    vistos.add(chave);
  }
  if (preenchidos.filter((c) => c.principal).length > 1) {
    erros.push(SOLTOS.codigos.doisPrincipais);
  }
  return erros;
}

/**
 * Distingue o leitor de código de barras da digitação pela velocidade das teclas: o leitor manda
 * todos os caracteres em poucos milissegundos e termina com Enter.
 */
export class DetectorDeLeitor {
  private marcas: number[] = [];

  /** Chame a cada tecla de texto, com o relógio em milissegundos. */
  tecla(agora: number): void {
    // Uma pausa grande começa uma nova leitura.
    if (this.marcas.length > 0 && agora - this.marcas[this.marcas.length - 1]! > 150) {
      this.marcas = [];
    }
    this.marcas.push(agora);
  }

  /** No Enter: a sequência foi tão rápida que só pode ser leitor? */
  foiLeitor(minimoDeTeclas = 4, mediaMaximaMs = 40): boolean {
    if (this.marcas.length < minimoDeTeclas) return false;
    const total = this.marcas[this.marcas.length - 1]! - this.marcas[0]!;
    return total / (this.marcas.length - 1) <= mediaMaximaMs;
  }

  reiniciar(): void {
    this.marcas = [];
  }
}
