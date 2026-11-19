import { Aviso, Botao, RodapeDeAcoes } from '@corona/ui';
import { useState } from 'react';
import type {
  DetalheDaNota,
  MaletaDoConsignado,
  ResultadoDaPeca,
} from '../../../shared/dados-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, novoId } from '../../chamadas';
import { JanelaDeConfirmacao } from '../../componentes-do-vendedor';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import { FotoDoProduto, useConsulta } from './apoio';
import { EscolherPedido, LeitorDeCodigo } from './componentes';

type Achada = Extract<ResultadoDaPeca, { tipo: 'ACHADA' }>;

/** Troca (§23.4): troca uma peça que está com o cliente por outra, sem acerto. A nota guarda o histórico. */
export function TelaTroca({ ponte, aoVoltar }: { ponte: PonteDoCorona; aoVoltar: () => void }) {
  const T = TEXTOS.consignado.troca;
  const [pedido, setPedido] = useState<{ id: string; numero: string; cliente: string } | null>(
    null,
  );
  const [volta, setVolta] = useState('');
  const [remessaId, setRemessaId] = useState('');
  const [entra, setEntra] = useState<Achada | null>(null);
  const [mensagem, setMensagem] = useState<{
    tipo: 'sucesso' | 'atencao' | 'erro';
    texto: string;
  } | null>(null);
  const [confirmando, setConfirmando] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const { dados: nota, recarregar } = useConsulta<DetalheDaNota>(
    ponte,
    pedido ? { operacao: 'consignado.nota', id: pedido.id } : null,
    [pedido?.id],
  );
  const { dados: maletas } = useConsulta<MaletaDoConsignado[]>(
    ponte,
    { operacao: 'consignado.maletas' },
    [],
  );
  const emUso = (maletas ?? []).filter((m) => m.status === 'ENVIADA' || m.status === 'CONFERIDA');
  const linhaQueVolta = nota?.linhas.find((l) => `${l.produtoId}|${l.remessaId}` === volta) ?? null;

  async function ler(codigo: string): Promise<void> {
    if (!remessaId) {
      setMensagem({ tipo: 'atencao', texto: TEXTOS.consignado.pedido.semMaletas });
      return;
    }
    const r = await chamar<ResultadoDaPeca>(ponte, {
      operacao: 'consignado.peca',
      remessaId,
      codigo,
    });
    if (!r.ok) setMensagem({ tipo: 'erro', texto: r.mensagem });
    else if (r.dados.tipo === 'FORA_DA_MALETA')
      setMensagem({ tipo: 'atencao', texto: TEXTOS.consignado.pedido.foraDaMaleta });
    else if (r.dados.tipo === 'EM_OUTRA_MALETA')
      setMensagem({
        tipo: 'atencao',
        texto: TEXTOS.consignado.pedido.emOutraMaleta(r.dados.maleta),
      });
    else if (r.dados.disponivel < 1)
      setMensagem({ tipo: 'atencao', texto: TEXTOS.consignado.pedido.semSaldo(0) });
    else {
      setEntra(r.dados);
      setMensagem({ tipo: 'sucesso', texto: TEXTOS.consignado.pedido.lida(r.dados.nome) });
    }
  }

  async function trocar(): Promise<void> {
    if (!pedido || !linhaQueVolta || !entra) return;
    setOcupado(true);
    const r = await chamar(ponte, {
      operacao: 'consignado.trocaNaNota',
      troca: {
        id: novoId(),
        notaId: pedido.id,
        volta: { produtoId: linhaQueVolta.produtoId, remessaId: linhaQueVolta.remessaId },
        entra: { produtoId: entra.produtoId, remessaId: entra.remessaId },
      },
    });
    setOcupado(false);
    setConfirmando(false);
    if (r.ok) {
      setMensagem({ tipo: 'sucesso', texto: T.trocada });
      setEntra(null);
      setVolta('');
      await recarregar();
    } else setMensagem({ tipo: 'erro', texto: r.mensagem });
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
          setVolta('');
          setEntra(null);
        }}
        aoTrocar={() => setPedido(null)}
      />
      {pedido && nota ? (
        <>
          <div className="cs-campo">
            <label htmlFor="troca-volta">{T.queVolta}</label>
            <select id="troca-volta" value={volta} onChange={(e) => setVolta(e.target.value)}>
              <option value="">{T.escolherQueVolta}</option>
              {nota.linhas.map((l) => (
                <option
                  key={`${l.produtoId}|${l.remessaId}`}
                  value={`${l.produtoId}|${l.remessaId}`}
                >
                  {l.nome} ({l.maleta}) · {l.dx}
                </option>
              ))}
            </select>
          </div>
          <h2 className="vendas-subtitulo">{T.queEntra}</h2>
          <div className="cs-campo">
            <label htmlFor="troca-maleta">{T.maleta}</label>
            <select
              id="troca-maleta"
              value={remessaId}
              onChange={(e) => setRemessaId(e.target.value)}
            >
              <option value="">{TEXTOS.consignado.pedido.escolherMaleta}</option>
              {emUso.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.codigo}
                </option>
              ))}
            </select>
          </div>
          <LeitorDeCodigo
            rotulo={T.lerCodigo}
            botao={T.lerAgora}
            aoLer={ler}
            desabilitado={!remessaId}
          />
          {entra ? (
            <p className="vendas-texto consignado-peca">
              <FotoDoProduto ponte={ponte} hash={entra.fotoHash} nome={entra.nome} />
              <strong>{entra.nome}</strong> ({entra.maleta})
            </p>
          ) : (
            <p className="vendas-texto">{T.nenhumaEscolhida}</p>
          )}
          {mensagem ? <Aviso tipo={mensagem.tipo}>{mensagem.texto}</Aviso> : null}
          <RodapeDeAcoes
            principal={
              <Botao
                variante="primario"
                grande
                disabled={!linhaQueVolta || !entra || ocupado}
                onClick={() => setConfirmando(true)}
              >
                {T.registrar}
              </Botao>
            }
          />
        </>
      ) : null}
      {confirmando && linhaQueVolta && entra ? (
        <JanelaDeConfirmacao
          titulo={T.confirmarTitulo}
          rotuloConfirmar={T.confirmarBotao}
          ocupado={ocupado}
          aoConfirmar={() => void trocar()}
          aoCancelar={() => setConfirmando(false)}
        >
          {T.confirmarTexto(linhaQueVolta.nome, entra.nome)}
        </JanelaDeConfirmacao>
      ) : null}
    </PaginaDoVendedor>
  );
}
