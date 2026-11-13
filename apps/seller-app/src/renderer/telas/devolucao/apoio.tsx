import type { PecaDaMaleta, PecaParaDevolver, PreviaDaDevolucao } from '../../../shared/dados';
import type { EstadoDaPeca, PecaQueVolta } from '../../../shared/operacoes';
import type { PonteDoCorona } from '../../../shared/ponte';
import { useFoto } from '../../chamadas';
import { centavosDeTexto, formatarReais } from '../../moedas';
import { TEXTOS } from '../../textos';

/** "2026-11-28" (ou um instante ISO) → "28/11". */
export function diaEMes(dia: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(dia);
  return m ? `${m[3]}/${m[2]}` : dia;
}

/** Texto da API ("50.00") → "R$ 50,00"; o que não for dinheiro aparece como veio. */
export function reais(texto: string): string {
  const c = centavosDeTexto(texto);
  return c === null ? texto : formatarReais(c);
}

/** Centavos como texto decimal com ponto ("50.00"). */
export function textoDeCentavos(c: bigint): string {
  const negativo = c < 0n;
  const abs = negativo ? -c : c;
  return `${negativo ? '-' : ''}${abs / 100n}.${(abs % 100n).toString().padStart(2, '0')}`;
}

/** A miniatura da peça; sem foto (ou foto que não abre), o espaço "Sem foto" e nada trava. */
export function FotoPequena({ ponte, produto }: { ponte: PonteDoCorona; produto: PecaDaMaleta }) {
  const foto = useFoto(ponte, produto.fotoHash, 'miniatura');
  return foto ? (
    <img className="vendas-busca-item__foto" src={foto} alt={`Foto de ${produto.nome}`} />
  ) : (
    <span className="vendas-busca-item__foto vendas-busca-item__foto--vazia" aria-hidden="true">
      Sem foto
    </span>
  );
}

/** O que a pessoa marcou em cada peça da venda (quantas voltam e o estado). */
export interface Marcacao {
  quantidade: string;
  estado: EstadoDaPeca;
}

/** As peças marcadas, no formato da ponte (só peça, quantidade e estado; nunca valor). */
export function pecasMarcadas(marcadas: Record<string, Marcacao>): PecaQueVolta[] {
  return Object.entries(marcadas)
    .map(([produtoId, m]) => ({
      produtoId,
      quantidade: Number.parseInt(m.quantidade, 10),
      estado: m.estado,
    }))
    .filter((p) => Number.isInteger(p.quantidade) && p.quantidade > 0);
}

/** A tabela das peças da venda: Vendidas, Já devolvidas, Ainda podem voltar, Quantas voltam e o Estado da peça. */
export function PecasQueVoltam({
  ponte,
  pecas,
  marcadas,
  destinos,
  defeitoObrigatorio,
  aoMudar,
}: {
  ponte: PonteDoCorona;
  pecas: readonly PecaParaDevolver[];
  marcadas: Record<string, Marcacao>;
  destinos?: PreviaDaDevolucao['destinos'];
  defeitoObrigatorio: boolean;
  aoMudar: (produtoId: string, m: Marcacao) => void;
}) {
  const T = TEXTOS.devolucao;
  return (
    <table className="cs-tabela">
      <caption>{T.pecasDaVenda}</caption>
      <thead>
        <tr>
          <th scope="col">{T.colunas.peca}</th>
          <th scope="col">{T.colunas.vendidas}</th>
          <th scope="col">{T.colunas.jaDevolvidas}</th>
          <th scope="col">{T.colunas.podemVoltar}</th>
          <th scope="col">{T.colunas.quantas}</th>
          <th scope="col">{T.colunas.estado}</th>
          {destinos ? <th scope="col">{T.colunas.destino}</th> : null}
        </tr>
      </thead>
      <tbody>
        {pecas.map((p) => {
          const m = marcadas[p.produto.id] ?? { quantidade: '', estado: 'BOA' };
          const estado: EstadoDaPeca = defeitoObrigatorio ? 'DEFEITO' : m.estado;
          const destino = destinos?.find(
            (d) => d.produtoId === p.produto.id && d.estado === estado,
          )?.destino;
          return (
            <tr key={p.produto.id}>
              <td>
                <span className="vendas-busca-item">
                  <FotoPequena ponte={ponte} produto={p.produto} />
                  <span>{p.produto.nome}</span>
                </span>
              </td>
              <td>{p.vendidas}</td>
              <td>{p.jaDevolvidas}</td>
              <td>{p.podemVoltar}</td>
              <td>
                <input
                  className="vendas-desconto"
                  aria-label={T.quantasDe(p.produto.nome)}
                  inputMode="numeric"
                  value={m.quantidade}
                  disabled={p.podemVoltar <= 0}
                  onChange={(e) =>
                    aoMudar(p.produto.id, { ...m, quantidade: e.target.value.replace(/\D/g, '') })
                  }
                />
              </td>
              <td>
                <select
                  aria-label={T.estadoDe(p.produto.nome)}
                  value={estado}
                  disabled={defeitoObrigatorio}
                  onChange={(e) =>
                    aoMudar(p.produto.id, { ...m, estado: e.target.value as EstadoDaPeca })
                  }
                >
                  <option value="BOA">{T.estados['BOA']}</option>
                  <option value="DEFEITO">{T.estados['DEFEITO']}</option>
                </select>
              </td>
              {destinos ? <td>{destino ? (T.destinos[destino] ?? '') : ''}</td> : null}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
