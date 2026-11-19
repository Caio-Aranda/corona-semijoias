import type { MaletaDoConsignado } from '../../../shared/dados-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { EstadoDaLista } from '../../componentes-do-vendedor';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import { dia, FotoDoProduto, useConsulta } from './apoio';
import { BotaoDeRelatorio } from './Relatorio';

/**
 * Maletas (§23.6): o saldo por peça, por eventos: disponível = enviada − com clientes − vendida pelos clientes − devolvida à
 * loja. "Com clientes" sobe na saída, na Reposição e na entrada de troca, e desce na devolução do acerto.
 */
export function TelaMaletas({ ponte, aoVoltar }: { ponte: PonteDoCorona; aoVoltar: () => void }) {
  const T = TEXTOS.consignado.maletas;
  const {
    dados: maletas,
    erro,
    recarregar,
  } = useConsulta<MaletaDoConsignado[]>(ponte, { operacao: 'consignado.maletas' }, []);
  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      <EstadoDaLista
        carregando={maletas === null && !erro}
        erro={erro}
        textoCarregando={TEXTOS.consignado.carregando}
        aoTentarDeNovo={() => void recarregar()}
      >
        {(maletas ?? []).length === 0 ? <p className="vendas-texto">{T.nenhuma}</p> : null}
        {(maletas ?? []).map((m) => (
          <section key={m.id} className="consignado-maleta" aria-label={T.legenda(m.codigo)}>
            <h2 className="vendas-subtitulo">
              {TEXTOS.consignado.maleta} {m.codigo}
            </h2>
            <p className="vendas-texto">
              {T.enviadaEm(dia(m.enviadaEm))} ·{' '}
              {T.resumo(m.totalEnviada, m.totalComClientes, m.totalVendida, m.totalDisponivel)}
            </p>
            <BotaoDeRelatorio
              ponte={ponte}
              pedido={{ tipo: 'SITUACAO_DA_MALETA', remessaId: m.id }}
              rotulo={TEXTOS.consignado.relatorios.abrir.situacaoDaMaleta}
            />
            <table className="cs-tabela consignado-tabela">
              <caption>{T.legenda(m.codigo)}</caption>
              <thead>
                <tr>
                  <th scope="col">{T.colunas.produto}</th>
                  <th scope="col" className="cs-numero">
                    {T.colunas.enviada}
                  </th>
                  <th scope="col" className="cs-numero">
                    {T.colunas.com}
                  </th>
                  <th scope="col" className="cs-numero">
                    {T.colunas.vendida}
                  </th>
                  <th scope="col" className="cs-numero">
                    {T.colunas.loja}
                  </th>
                  <th scope="col" className="cs-numero">
                    {T.colunas.disp}
                  </th>
                </tr>
              </thead>
              <tbody>
                {m.pecas.map((p) => (
                  <tr key={p.produtoId}>
                    <td>
                      <span className="consignado-peca">
                        <FotoDoProduto ponte={ponte} hash={p.fotoHash} nome={p.nome} />
                        {p.codigo ? `${p.codigo} · ` : ''}
                        {p.nome}
                      </span>
                    </td>
                    <td className="cs-numero">{p.enviada}</td>
                    <td className="cs-numero">{p.comClientes}</td>
                    <td className="cs-numero">{p.vendida}</td>
                    <td className="cs-numero">{p.devolvidaALoja}</td>
                    <td className="cs-numero">{p.disponivel}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        ))}
      </EstadoDaLista>
    </PaginaDoVendedor>
  );
}
