import { Botao, EstadoDaLista, Tabela } from '@corona/ui';
import { useCallback, useEffect, useState } from 'react';
import type { Maleta } from '../../shared/dados';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, Dinheiro, useCotacao } from '../chamadas';
import { dataParaTela } from '../moedas';
import { TEXTOS } from '../textos';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaMinhaMaletaProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  aoConferir: (maleta: Maleta) => void;
}

/** Minha maleta: as maletas que estão com o vendedor, peça por peça, e o botão Conferir maleta. */
export function TelaMinhaMaleta({ ponte, aoVoltar, aoConferir }: TelaMinhaMaletaProps) {
  const { cotacao } = useCotacao(ponte);
  const [maletas, setMaletas] = useState<Maleta[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  const carregar = useCallback(async () => {
    setErro(null);
    setMaletas(null);
    const r = await chamar<Maleta[]>(ponte, { operacao: 'maletas.listar' });
    if (r.ok) setMaletas(r.dados);
    else setErro(r.mensagem);
  }, [ponte]);

  useEffect(() => {
    void carregar();
  }, [carregar]);

  const T = TEXTOS.maleta;
  return (
    <PaginaDoVendedor titulo={T.titulo} aoVoltar={aoVoltar}>
      <EstadoDaLista
        carregando={maletas === null && erro === null}
        erro={erro}
        textoCarregando={T.textoCarregando}
        aoTentarDeNovo={() => void carregar()}
        rotuloTentarDeNovo={TEXTOS.tentarDeNovo}
      >
        {maletas !== null && maletas.length === 0 ? (
          <p className="vendas-texto">{T.vazia}</p>
        ) : null}
        {(maletas ?? []).map((m) => (
          <section
            key={m.id}
            className="vendas-cartao vendas-cartao--larga"
            aria-label={`Maleta ${m.numero ?? ''}`}
          >
            <h2 className="vendas-subtitulo">
              {T.tipo[m.tipo] ?? 'Maleta'} {m.numero ?? ''} · {T.situacao[m.status] ?? m.status}
            </h2>
            <p className="vendas-texto">
              Enviada em {dataParaTela(m.enviadaEm)} · {m.pecasEmAberto} {T.pecas} ·{' '}
              <Dinheiro reais={m.valorEmAberto} cotacao={cotacao} />
            </p>
            <Tabela
              legenda={T.legenda}
              textoVazio={T.semPecas}
              colunas={[
                { chave: 'peca', titulo: T.colunas.peca },
                { chave: 'codigo', titulo: T.colunas.codigo },
                { chave: 'enviada', titulo: T.colunas.enviada, numerica: true },
                { chave: 'vendida', titulo: T.colunas.vendida, numerica: true },
                { chave: 'devolvida', titulo: T.colunas.devolvida, numerica: true },
                { chave: 'saldo', titulo: T.colunas.saldo, numerica: true },
                { chave: 'preco', titulo: T.colunas.preco, numerica: true },
              ]}
              linhas={m.itens.map((l) => ({
                peca: l.produto.nome,
                codigo: l.produto.codigo ?? '',
                enviada: l.enviada,
                vendida: l.vendida,
                devolvida: l.devolvida,
                saldo: l.saldo,
                preco: <Dinheiro reais={l.precoUnitario} cotacao={cotacao} />,
              }))}
            />
            {m.status === 'ENVIADA' ? (
              <Botao variante="primario" grande onClick={() => aoConferir(m)}>
                {T.conferir}
              </Botao>
            ) : null}
          </section>
        ))}
      </EstadoDaLista>
    </PaginaDoVendedor>
  );
}
