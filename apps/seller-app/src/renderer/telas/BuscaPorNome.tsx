import { Botao, Campo } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { LinhaDaMaleta } from '../../shared/dados';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, useFoto } from '../chamadas';
import { TEXTOS } from '../textos';

export interface BuscaPorNomeProps {
  ponte: PonteDoCorona;
  maletaId: string;
  /** A pessoa escolheu uma peça da lista: a tela age como se tivesse lido o código dela. */
  aoEscolher: (linha: LinhaDaMaleta) => void;
}

/**
 * "Procurar pelo nome" (Vender e Conferir maleta): para quando a etiqueta não lê. Procura SÓ entre as peças da maleta do
 * vendedor (o servidor nunca mostra peça de fora) e mostra miniatura, nome, código e "Na maleta: N". Escolher uma peça
 * faz o mesmo que ler o código dela.
 */
export function BuscaPorNome({ ponte, maletaId, aoEscolher }: BuscaPorNomeProps) {
  const T = TEXTOS.buscaPorNome;
  const [aberta, setAberta] = useState(false);
  const [texto, setTexto] = useState('');
  const [achadas, setAchadas] = useState<LinhaDaMaleta[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [buscando, setBuscando] = useState(false);

  // A busca espera a pessoa parar de digitar.
  useEffect(() => {
    if (!aberta) return;
    const procurado = texto.trim();
    if (!procurado) {
      setAchadas(null);
      setErro(null);
      return;
    }
    let ativo = true;
    const espera = setTimeout(() => {
      setBuscando(true);
      void chamar<LinhaDaMaleta[]>(ponte, {
        operacao: 'maletas.buscarPeca',
        id: maletaId,
        texto: procurado,
      }).then((r) => {
        if (!ativo) return;
        setBuscando(false);
        if (r.ok) {
          setAchadas(Array.isArray(r.dados) ? r.dados : []);
          setErro(null);
        } else {
          setAchadas(null);
          setErro(r.mensagem);
        }
      });
    }, 250);
    return () => {
      ativo = false;
      clearTimeout(espera);
    };
  }, [ponte, maletaId, texto, aberta]);

  if (!aberta) {
    return (
      <Botao onClick={() => setAberta(true)} disabled={!maletaId}>
        {T.abrir}
      </Botao>
    );
  }
  return (
    <section className="vendas-cartao vendas-cartao--larga" aria-label={T.abrir}>
      <p className="vendas-texto">{T.ajuda}</p>
      <Campo
        rotulo={T.campo}
        value={texto}
        onChange={(e) => setTexto(e.target.value)}
        autoComplete="off"
        autoFocus
      />
      {buscando ? <p role="status">{T.buscando}</p> : null}
      {erro ? <p role="alert">{erro}</p> : null}
      {achadas !== null && achadas.length === 0 && !buscando ? (
        <p className="vendas-texto">{T.nenhuma}</p>
      ) : null}
      {achadas !== null && achadas.length > 0 ? (
        <ul className="vendas-lista" aria-label={T.legenda}>
          {achadas.map((l) => (
            <ItemDaBusca
              key={l.produto.id}
              ponte={ponte}
              linha={l}
              aoEscolher={() => {
                aoEscolher(l);
                setAberta(false);
                setTexto('');
                setAchadas(null);
              }}
            />
          ))}
        </ul>
      ) : null}
      <Botao onClick={() => setAberta(false)}>{T.fechar}</Botao>
    </section>
  );
}

function ItemDaBusca({
  ponte,
  linha,
  aoEscolher,
}: {
  ponte: PonteDoCorona;
  linha: LinhaDaMaleta;
  aoEscolher: () => void;
}) {
  const T = TEXTOS.buscaPorNome;
  const foto = useFoto(ponte, linha.produto.fotoHash, 'miniatura');
  return (
    <li className="vendas-busca-item">
      {foto ? (
        <img className="vendas-busca-item__foto" src={foto} alt={`Foto de ${linha.produto.nome}`} />
      ) : (
        <span className="vendas-busca-item__foto vendas-busca-item__foto--vazia" aria-hidden="true">
          {TEXTOS.comum.semFoto}
        </span>
      )}
      <span className="vendas-busca-item__texto">
        <strong>{linha.produto.nome}</strong>
        {linha.produto.codigo ? <span>{T.codigo(linha.produto.codigo)}</span> : null}
        <span>{T.naMaleta(linha.saldo)}</span>
      </span>
      <Botao aria-label={T.escolherDe(linha.produto.nome)} onClick={aoEscolher}>
        {T.escolher}
      </Botao>
    </li>
  );
}
