import { Botao } from '@corona/ui';
import type { ResultadoDaPesquisa } from '../../../shared/dados-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { EstadoDaLista } from '../../componentes-do-vendedor';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import { classeDaSituacao, dia, FotoDoCliente, reais, situacaoNaTela, useConsulta } from './apoio';

/** Pesquisar (§23.4): por nome do cliente, número da nota ou código da maleta. */
export function TelaPesquisa({
  ponte,
  texto,
  aoVoltar,
  aoAbrirPedido,
  aoAbrirCliente,
}: {
  ponte: PonteDoCorona;
  texto: string;
  aoVoltar: () => void;
  aoAbrirPedido: (notaId: string) => void;
  aoAbrirCliente: (id: string) => void;
}) {
  const T = TEXTOS.consignado.pesquisa;
  const { dados, erro, recarregar } = useConsulta<ResultadoDaPesquisa>(
    ponte,
    { operacao: 'consignado.pesquisar', texto },
    [texto],
  );
  const vazio = dados !== null && dados.notas.length === 0 && dados.clientes.length === 0 && dados.maletas.length === 0;
  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      <p className="vendas-texto">{T.resultadoPara(texto)}</p>
      <EstadoDaLista
        carregando={dados === null && !erro}
        erro={erro}
        textoCarregando={TEXTOS.consignado.carregando}
        aoTentarDeNovo={() => void recarregar()}
      >
        {vazio ? <p className="vendas-texto">{T.nada}</p> : null}
        {dados && dados.notas.length > 0 ? (
          <section aria-label={T.pedidos}>
            <h2 className="vendas-subtitulo">{T.pedidos}</h2>
            <table className="cs-tabela consignado-tabela">
              <caption>{T.pedidoLegenda}</caption>
              <thead>
                <tr>
                  <th scope="col">{T.pedidoColunas.nota}</th>
                  <th scope="col">{T.pedidoColunas.cliente}</th>
                  <th scope="col">{T.pedidoColunas.cobranca}</th>
                  <th scope="col" className="cs-numero">{T.pedidoColunas.dx}</th>
                  <th scope="col">{T.pedidoColunas.situacao}</th>
                  <th scope="col" />
                </tr>
              </thead>
              <tbody>
                {dados.notas.map((n) => (
                  <tr key={n.notaId} className={classeDaSituacao(n.situacao)}>
                    <td>{n.numero}</td>
                    <td>{n.cliente}</td>
                    <td>{dia(n.dataCobranca)}</td>
                    <td className="cs-numero">{reais(n.valorDx)}</td>
                    <td>{situacaoNaTela(n.situacao)}</td>
                    <td>
                      <Botao onClick={() => aoAbrirPedido(n.notaId)}>{T.abrir}</Botao>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        ) : null}
        {dados && dados.clientes.length > 0 ? (
          <section aria-label={T.clientes}>
            <h2 className="vendas-subtitulo">{T.clientes}</h2>
            <ul className="vendas-lista consignado-clientes-lista">
              {dados.clientes.map((c) => (
                <li key={c.id}>
                  <FotoDoCliente ponte={ponte} hash={c.fotoHash} rotulo={c.nome} pequena />
                  <span>{c.nome}</span>
                  <Botao onClick={() => aoAbrirCliente(c.id)}>{T.abrir}</Botao>
                </li>
              ))}
            </ul>
          </section>
        ) : null}
        {dados && dados.maletas.length > 0 ? (
          <section aria-label={T.maletas}>
            <h2 className="vendas-subtitulo">{T.maletas}</h2>
            <ul className="vendas-lista">
              {dados.maletas.map((m) => (
                <li key={m.id}>{m.codigo}</li>
              ))}
            </ul>
          </section>
        ) : null}
      </EstadoDaLista>
    </PaginaDoVendedor>
  );
}
