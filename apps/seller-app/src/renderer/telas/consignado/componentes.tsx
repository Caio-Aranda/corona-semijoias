import { Botao } from '@corona/ui';
import { useEffect, useRef, useState } from 'react';
import type { LinhaDeNotaNaBusca } from '../../../shared/dados-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar } from '../../chamadas';
import { Campo, EstadoDaLista } from '../../componentes-do-vendedor';
import { TEXTOS } from '../../textos';
import { classeDaSituacao, dia, reais, situacaoNaTela } from './apoio';

/** O campo em que se lê o código (o leitor "digita" e dá Enter) ou se digita à mão. Limpa e volta o foco depois de cada leitura. */
export function LeitorDeCodigo({
  rotulo,
  botao,
  aoLer,
  desabilitado = false,
}: {
  rotulo: string;
  botao: string;
  aoLer: (codigo: string) => void | Promise<void>;
  desabilitado?: boolean;
}) {
  const [codigo, setCodigo] = useState('');
  const forma = useRef<HTMLFormElement>(null);
  return (
    <form
      ref={forma}
      className="vendas-leitor"
      onSubmit={(e) => {
        e.preventDefault();
        const lido = codigo.trim();
        if (!lido) return;
        setCodigo('');
        void Promise.resolve(aoLer(lido)).finally(() => forma.current?.querySelector('input')?.focus());
      }}
    >
      <Campo
        rotulo={rotulo}
        value={codigo}
        autoComplete="off"
        disabled={desabilitado}
        onChange={(e) => setCodigo(e.target.value)}
      />
      <Botao type="submit" variante="primario" disabled={desabilitado || codigo.trim() === ''}>
        {botao}
      </Botao>
    </form>
  );
}

/** Escolhe um pedido em aberto: por nome do cliente, número da nota ou código da maleta. */
export function EscolherPedido({
  ponte,
  rotuloBusca,
  escolhido,
  aoEscolher,
  aoTrocar,
  filtro,
}: {
  ponte: PonteDoCorona;
  rotuloBusca: string;
  escolhido: { id: string; numero: string; cliente: string } | null;
  aoEscolher: (n: LinhaDeNotaNaBusca) => void;
  aoTrocar: () => void;
  /** Mostra só os pedidos que passam neste filtro (a Reposição fica de fora do que já foi acertado, por exemplo). */
  filtro?: (n: LinhaDeNotaNaBusca) => boolean;
}) {
  const T = TEXTOS.consignado.escolherPedido;
  const [texto, setTexto] = useState('');
  const [notas, setNotas] = useState<LinhaDeNotaNaBusca[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    if (escolhido) return;
    let ativo = true;
    const espera = setTimeout(() => {
      void chamar<LinhaDeNotaNaBusca[]>(ponte, {
        operacao: 'consignado.notas',
        texto: texto.trim(),
        soEmAberto: true,
      }).then((r) => {
        if (!ativo) return;
        if (r.ok) {
          setNotas(r.dados);
          setErro(null);
        } else setErro(r.mensagem);
      });
    }, 200);
    return () => {
      ativo = false;
      clearTimeout(espera);
    };
  }, [ponte, texto, escolhido]);

  if (escolhido) {
    return (
      <div className="consignado-escolhido">
        <p className="vendas-texto">
          <strong>{T.escolhido(escolhido.numero, escolhido.cliente)}</strong>
        </p>
        <Botao onClick={aoTrocar}>{T.trocar}</Botao>
      </div>
    );
  }
  const lista = (notas ?? []).filter((n) => (filtro ? filtro(n) : true));
  return (
    <div className="consignado-escolher">
      <Campo
        rotulo={rotuloBusca}
        value={texto}
        autoComplete="off"
        onChange={(e) => setTexto(e.target.value)}
      />
      <EstadoDaLista
        carregando={notas === null && !erro}
        erro={erro}
        textoCarregando={TEXTOS.consignado.carregando}
      >
        {lista.length === 0 ? (
          <p className="vendas-texto">{T.nenhum}</p>
        ) : (
          <table className="cs-tabela consignado-tabela">
            <caption>{TEXTOS.consignado.cobranca.legenda}</caption>
            <thead>
              <tr>
                <th scope="col">{TEXTOS.consignado.cobranca.colunas.nota}</th>
                <th scope="col">{TEXTOS.consignado.cobranca.colunas.cliente}</th>
                <th scope="col">{TEXTOS.consignado.cobranca.colunas.cobranca}</th>
                <th scope="col" className="cs-numero">{TEXTOS.consignado.cobranca.colunas.dx}</th>
                <th scope="col">{TEXTOS.consignado.cobranca.colunas.maletas}</th>
                <th scope="col">{TEXTOS.consignado.cobranca.colunas.situacao}</th>
                <th scope="col" />
              </tr>
            </thead>
            <tbody>
              {lista.map((n) => (
                <tr key={n.notaId} className={classeDaSituacao(n.situacao)}>
                  <td>{n.numero}</td>
                  <td>{n.cliente}</td>
                  <td>{dia(n.dataCobranca)}</td>
                  <td className="cs-numero">{reais(n.valorDx)}</td>
                  <td>{n.maletas.join(', ')}</td>
                  <td>{situacaoNaTela(n.situacao)}</td>
                  <td>
                    <Botao onClick={() => aoEscolher(n)}>{T.escolher}</Botao>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </EstadoDaLista>
    </div>
  );
}
