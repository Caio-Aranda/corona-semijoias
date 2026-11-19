import { Aviso, Botao, RodapeDeAcoes } from '@corona/ui';
import { useState } from 'react';
import type { MaletaDoConsignado, ResultadoDaPeca } from '../../../shared/dados-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, novoId } from '../../chamadas';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import { FotoDoProduto, useConsulta } from './apoio';
import { EscolherPedido, LeitorDeCodigo } from './componentes';

type Achada = Extract<ResultadoDaPeca, { tipo: 'ACHADA' }>;
interface Linha {
  produtoId: string;
  remessaId: string;
  maleta: string;
  nome: string;
  fotoHash: string | null;
  quantidade: number;
  disponivel: number;
}

/**
 * Reposição (§23.4): acrescenta peças a um cliente com pedido em aberto. Pedido da semana de agora: as peças entram nele;
 * pedido de semana anterior: nasce um pedido novo só com as peças novas, com a mesma Dt. Cobrança.
 */
export function TelaReposicao({
  ponte,
  aoVoltar,
  aoAbrirPedido,
}: {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  aoAbrirPedido: (notaId: string) => void;
}) {
  const T = TEXTOS.consignado.reposicao;
  const [pedido, setPedido] = useState<{ id: string; numero: string; cliente: string } | null>(null);
  const [remessaId, setRemessaId] = useState('');
  const [linhas, setLinhas] = useState<Linha[]>([]);
  const [mensagem, setMensagem] = useState<{ tipo: 'sucesso' | 'atencao' | 'erro'; texto: string } | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const { dados: maletas } = useConsulta<MaletaDoConsignado[]>(ponte, { operacao: 'consignado.maletas' }, []);
  const emUso = (maletas ?? []).filter((m) => m.status === 'ENVIADA' || m.status === 'CONFERIDA');

  async function ler(codigo: string): Promise<void> {
    if (!remessaId) {
      setMensagem({ tipo: 'atencao', texto: TEXTOS.consignado.pedido.semMaletas });
      return;
    }
    const r = await chamar<ResultadoDaPeca>(ponte, { operacao: 'consignado.peca', remessaId, codigo });
    if (!r.ok) {
      setMensagem({ tipo: 'erro', texto: r.mensagem });
      return;
    }
    if (r.dados.tipo === 'FORA_DA_MALETA') {
      setMensagem({ tipo: 'atencao', texto: TEXTOS.consignado.pedido.foraDaMaleta });
      return;
    }
    if (r.dados.tipo === 'EM_OUTRA_MALETA') {
      setMensagem({ tipo: 'atencao', texto: TEXTOS.consignado.pedido.emOutraMaleta(r.dados.maleta) });
      return;
    }
    const peca: Achada = r.dados;
    const atual = linhas.find((l) => l.produtoId === peca.produtoId && l.remessaId === peca.remessaId);
    if ((atual?.quantidade ?? 0) + 1 > peca.disponivel) {
      setMensagem({ tipo: 'atencao', texto: TEXTOS.consignado.pedido.semSaldo(peca.disponivel) });
      return;
    }
    setMensagem({ tipo: 'sucesso', texto: TEXTOS.consignado.pedido.lida(peca.nome) });
    setLinhas((ls) =>
      atual
        ? ls.map((l) => (l === atual ? { ...l, quantidade: l.quantidade + 1 } : l))
        : [
            ...ls,
            {
              produtoId: peca.produtoId,
              remessaId: peca.remessaId,
              maleta: peca.maleta,
              nome: peca.nome,
              fotoHash: peca.fotoHash,
              quantidade: 1,
              disponivel: peca.disponivel,
            },
          ],
    );
  }

  async function registrar(): Promise<void> {
    if (!pedido) return;
    setOcupado(true);
    const r = await chamar<{ novaNota: boolean; nota: { numero: string } | null }>(ponte, {
      operacao: 'consignado.reposicao',
      reposicao: {
        id: novoId(),
        notaId: pedido.id,
        itens: linhas.map((l) => ({ produtoId: l.produtoId, remessaId: l.remessaId, quantidade: l.quantidade })),
      },
    });
    setOcupado(false);
    if (!r.ok) {
      setMensagem({ tipo: 'erro', texto: r.mensagem });
      return;
    }
    const numero = r.dados.nota?.numero ?? '';
    setMensagem({
      tipo: 'sucesso',
      texto: `${T.registrada} ${r.dados.novaNota ? T.novaNota(numero) : T.mudou(numero)}`,
    });
    setLinhas([]);
  }

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      <p className="vendas-texto">{T.ajuda}</p>
      <h2 className="vendas-subtitulo">{T.pedido}</h2>
      <EscolherPedido
        ponte={ponte}
        rotuloBusca={TEXTOS.consignado.escolherPedido.buscar}
        escolhido={pedido}
        aoEscolher={(n) => {
          setPedido({ id: n.notaId, numero: n.numero, cliente: n.cliente });
          setMensagem(null);
        }}
        aoTrocar={() => setPedido(null)}
      />
      {pedido ? (
        <>
          <div className="cs-campo">
            <label htmlFor="reposicao-maleta">{T.maleta}</label>
            <select id="reposicao-maleta" value={remessaId} onChange={(e) => setRemessaId(e.target.value)}>
              <option value="">{TEXTOS.consignado.pedido.escolherMaleta}</option>
              {emUso.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.codigo}
                </option>
              ))}
            </select>
          </div>
          <LeitorDeCodigo rotulo={T.lerCodigo} botao={T.lerAgora} aoLer={ler} desabilitado={!remessaId} />
          {mensagem ? <Aviso tipo={mensagem.tipo}>{mensagem.texto}</Aviso> : null}
          <table className="cs-tabela consignado-tabela">
            <caption>{T.legenda}</caption>
            <thead>
              <tr>
                <th scope="col">{T.colunas.produto}</th>
                <th scope="col">{T.colunas.maleta}</th>
                <th scope="col" className="cs-numero">{T.colunas.qtd}</th>
                <th scope="col">{T.colunas.acoes}</th>
              </tr>
            </thead>
            <tbody>
              {linhas.length === 0 ? (
                <tr>
                  <td colSpan={4}>{T.semPecas}</td>
                </tr>
              ) : (
                linhas.map((l) => (
                  <tr key={`${l.produtoId}|${l.remessaId}`}>
                    <td>
                      <span className="consignado-peca">
                        <FotoDoProduto ponte={ponte} hash={l.fotoHash} nome={l.nome} />
                        {l.nome}
                      </span>
                    </td>
                    <td>{l.maleta}</td>
                    <td className="cs-numero">{l.quantidade}</td>
                    <td>
                      <Botao
                        aria-label={TEXTOS.consignado.pedido.tirarUmaDe(l.nome)}
                        onClick={() =>
                          setLinhas((ls) =>
                            ls
                              .map((x) => (x === l ? { ...x, quantidade: x.quantidade - 1 } : x))
                              .filter((x) => x.quantidade > 0),
                          )
                        }
                      >
                        {TEXTOS.consignado.pedido.menos}
                      </Botao>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
          <RodapeDeAcoes
            cancelar={<Botao onClick={() => aoAbrirPedido(pedido.id)}>{TEXTOS.consignado.pedido.verNota}</Botao>}
            principal={
              <Botao variante="primario" grande disabled={linhas.length === 0 || ocupado} onClick={() => void registrar()}>
                {T.registrar}
              </Botao>
            }
          />
        </>
      ) : null}
    </PaginaDoVendedor>
  );
}
