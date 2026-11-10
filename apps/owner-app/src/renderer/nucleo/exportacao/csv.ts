import type { Celula, Grade } from './grade';

/**
 * Texto que o Excel poderia entender como fórmula (começa com = + - @) ganha um apóstrofo na frente.
 * Número não passa por aqui. Vale para o CSV; no .xlsx o texto é gravado como texto e não vira fórmula.
 */
export function neutralizarFormula(texto: string): string {
  return /^[=+\-@\t\r]/.test(texto) ? `'${texto}` : texto;
}

/** Marca que faz o Excel ler o arquivo como UTF-8 (acentos certos). */
const MARCA_DE_UTF8 = String.fromCharCode(0xfeff);

function campo(c: Celula): string {
  // Excel em português lê "1234,56" como número; sem separador de milhar.
  const bruto = c.numero ? c.v.replace('.', ',') : neutralizarFormula(c.v);
  return /[;"\n\r]/.test(bruto) ? `"${bruto.replace(/"/g, '""')}"` : bruto;
}

/** CSV para o Excel brasileiro: UTF-8 com marca, ponto e vírgula entre as colunas, vírgula nos decimais. */
export function gerarCsv(grade: Grade): string {
  const linhas: string[] = [];
  for (const l of grade.cabecalho) linhas.push(campo({ v: l }));
  linhas.push('');
  linhas.push(grade.titulos.map((t) => campo({ v: t })).join(';'));
  for (const l of grade.linhas) linhas.push(l.map(campo).join(';'));
  if (grade.rodape.length > 0) {
    linhas.push('');
    for (const l of grade.rodape) linhas.push(l.map(campo).join(';'));
  }
  return `${MARCA_DE_UTF8}${linhas.join('\r\n')}\r\n`;
}
