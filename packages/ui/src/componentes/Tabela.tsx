import type { ReactNode } from 'react';

export interface ColunaDaTabela {
  chave: string;
  titulo: string;
  /** Alinha à direita (valores e quantidades). */
  numerica?: boolean;
}

export interface TabelaProps {
  /** Descreve o que a tabela mostra; também ajuda leitores de tela. */
  legenda: string;
  colunas: readonly ColunaDaTabela[];
  linhas: readonly Record<string, ReactNode>[];
  /** Texto quando não há linhas, ex.: "Nenhuma peça nesta maleta." */
  textoVazio?: string;
}

/** Tabela de leitura rápida: texto escuro, linhas altas, sem enfeite. */
export function Tabela({
  legenda,
  colunas,
  linhas,
  textoVazio = 'Nada para mostrar.',
}: TabelaProps) {
  return (
    <table className="cs-tabela">
      <caption>{legenda}</caption>
      <thead>
        <tr>
          {colunas.map((c) => (
            <th key={c.chave} scope="col" className={c.numerica ? 'cs-numero' : undefined}>
              {c.titulo}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {linhas.length === 0 ? (
          <tr>
            <td colSpan={colunas.length}>{textoVazio}</td>
          </tr>
        ) : (
          linhas.map((linha, i) => (
            <tr key={i}>
              {colunas.map((c) => (
                <td key={c.chave} className={c.numerica ? 'cs-numero' : undefined}>
                  {linha[c.chave]}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}
