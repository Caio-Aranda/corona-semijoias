import { TEXTOS } from '../textos';

/** Quem está com a peça em maleta, como o servidor mandou (só da dona). */
export interface VendedorComAPeca {
  nome: string;
  quantidade: number;
}

export interface SaldoDaLojaProps {
  /** Quantas há no estoque da loja. */
  estoque: number;
  /** Quantas estão nas maletas dos vendedores (maletas enviadas, conferidas ou em acerto). */
  emMaletas?: number;
  vendedores?: readonly VendedorComAPeca[];
}

/** Os nomes dos vendedores, até três; com mais gente, "e mais N". */
export function nomesDosVendedores(vendedores: readonly VendedorComAPeca[]): string {
  return TEXTOS.saldoDaLoja.nomes(vendedores.map((v) => v.nome));
}

/**
 * Estoque da loja sem confundir "acabou" com "está em maleta" (item 13): com saldo, só o número; com zero e peças em
 * maleta, "0 na loja" e o aviso "Está em maleta: 2 (Maria, João)"; com zero e nenhuma em maleta, "Acabou" em destaque.
 */
export function SaldoDaLoja({ estoque, emMaletas = 0, vendedores = [] }: SaldoDaLojaProps) {
  const T = TEXTOS.saldoDaLoja;
  if (estoque > 0) return <span>{estoque}</span>;
  if (emMaletas > 0) {
    return (
      <span>
        {T.zeroNaLoja}{' '}
        <span className="gestao-em-maleta">
          {T.emMaleta(emMaletas, nomesDosVendedores(vendedores))}
        </span>
      </span>
    );
  }
  return <strong className="gestao-acabando">{T.acabou}</strong>;
}
