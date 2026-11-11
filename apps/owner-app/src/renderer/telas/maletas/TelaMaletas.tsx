import { Aviso, Botao } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar } from '../../nucleo/chamadas';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { DevolverPecas } from './DevolverPecas';
import { FichaDaMaleta } from './FichaDaMaleta';
import { GestaoDaMaleta } from './GestaoDaMaleta';
import { ListaDeMaletas } from './ListaDeMaletas';
import { MontagemDaMaleta } from './MontagemDaMaleta';
import { PedirMotivo } from './PedirMotivo';
import { ReporMaleta } from './ReporMaleta';
import { lerSaldoDaMaleta, type Maleta, type SaldoDaMaleta } from './dados';

const T = TEXTOS_DE_MALETAS;

type Passo =
  | { passo: 'lista' }
  | { passo: 'ficha'; id: string }
  | { passo: 'devolver'; maleta: Maleta; saldo: SaldoDaMaleta }
  | {
      passo: 'repor';
      vendedorId: string;
      vendedorNome: string;
      semanaId?: string;
      pracaId?: string;
      /** Para onde voltar com "Voltar". */
      deId?: string;
    }
  | { passo: 'montando'; maleta: Maleta }
  | { passo: 'enviada'; maleta: Maleta; jaEstava: boolean }
  | { passo: 'cancelarRascunho'; maleta: Maleta };

/** Maletas → Maletas: lista, ficha da maleta enviada, reposição, devolução e cancelamento. */
export function TelaMaletas({
  ponte,
  abrirId,
}: {
  ponte: PonteDoCorona;
  /** Vem de um relatório da maleta: já abre a ficha desta maleta. */
  abrirId?: string | null;
}) {
  const [passo, setPasso] = useState<Passo>(
    abrirId ? { passo: 'ficha', id: abrirId } : { passo: 'lista' },
  );
  const [volta, setVolta] = useState(0);
  const [aviso, setAviso] = useState<string | null>(null);
  const [erroAoAbrir, setErroAoAbrir] = useState<string | null>(null);
  const voltarParaLista = () => {
    setVolta((v) => v + 1);
    setPasso({ passo: 'lista' });
  };

  const abrir = (maleta: Maleta) => {
    setAviso(null);
    setPasso(
      maleta.status === 'RASCUNHO'
        ? { passo: 'montando', maleta }
        : { passo: 'ficha', id: maleta.id },
    );
  };

  async function prepararDevolucao(maleta: Maleta, saldo: SaldoDaMaleta) {
    // Busca de novo: o saldo da ficha pode ter mudado desde que foi aberta.
    const r = await chamar(ponte, 'maletas.saldo', { id: maleta.id });
    const lido = r.ok ? lerSaldoDaMaleta(r.dados) : null;
    if (!lido) {
      setErroAoAbrir(T.naoAbriu);
      setPasso({ passo: 'devolver', maleta, saldo });
      return;
    }
    setPasso({ passo: 'devolver', maleta, saldo: lido });
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.gerir.titulo}</h1>
      </div>
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      {erroAoAbrir ? <Aviso tipo="erro">{erroAoAbrir}</Aviso> : null}
      {passo.passo === 'lista' ? (
        <ListaDeMaletas
          ponte={ponte}
          chave={String(volta)}
          aoAbrir={abrir}
          aoRepor={(vendedorId, vendedorNome) =>
            setPasso({ passo: 'repor', vendedorId, vendedorNome })
          }
        />
      ) : null}
      {passo.passo === 'ficha' ? (
        <GestaoDaMaleta
          key={passo.id}
          ponte={ponte}
          id={passo.id}
          aoVoltar={voltarParaLista}
          aoRepor={(m) =>
            setPasso({
              passo: 'repor',
              vendedorId: m.vendedorId,
              vendedorNome: m.vendedorNome,
              ...(m.semanaId ? { semanaId: m.semanaId } : {}),
              ...(m.pracaId ? { pracaId: m.pracaId } : {}),
              deId: m.id,
            })
          }
          aoDevolver={(m, s) => void prepararDevolucao(m, s)}
        />
      ) : null}
      {passo.passo === 'devolver' ? (
        <DevolverPecas
          key={passo.maleta.id}
          ponte={ponte}
          maleta={passo.maleta}
          saldo={passo.saldo}
          aoVoltar={() => {
            setErroAoAbrir(null);
            setPasso({ passo: 'ficha', id: passo.maleta.id });
          }}
        />
      ) : null}
      {passo.passo === 'repor' ? (
        <ReporMaleta
          ponte={ponte}
          vendedorId={passo.vendedorId}
          vendedorNome={passo.vendedorNome}
          {...(passo.semanaId ? { semanaId: passo.semanaId } : {})}
          {...(passo.pracaId ? { pracaId: passo.pracaId } : {})}
          aoCriar={(maleta) => setPasso({ passo: 'montando', maleta })}
          aoVoltar={() =>
            passo.deId ? setPasso({ passo: 'ficha', id: passo.deId }) : voltarParaLista()
          }
        />
      ) : null}
      {passo.passo === 'montando' ? (
        <>
          <MontagemDaMaleta
            key={passo.maleta.id}
            ponte={ponte}
            inicial={passo.maleta}
            aoVoltar={voltarParaLista}
            aoEnviar={(maleta, jaEstava) => setPasso({ passo: 'enviada', maleta, jaEstava })}
          />
          <div className="gestao-acoes-da-linha">
            <Botao onClick={() => setPasso({ passo: 'cancelarRascunho', maleta: passo.maleta })}>
              {T.ficha.cancelar}
            </Botao>
          </div>
        </>
      ) : null}
      {passo.passo === 'cancelarRascunho' ? (
        <PedirMotivo
          titulo={T.cancelar.titulo}
          texto={<p className="gestao-texto">{T.cancelar.textoRascunho}</p>}
          rotuloDoMotivo={T.cancelar.motivo}
          avisoDoMotivo={T.cancelar.motivoAviso}
          rotuloConfirmar={T.cancelar.confirmar}
          rotuloCancelar={T.cancelar.voltar}
          executar={(motivo) => chamar(ponte, 'maletas.cancelar', { id: passo.maleta.id, motivo })}
          aoFeito={() => {
            setAviso(T.cancelar.feitoRascunho);
            voltarParaLista();
          }}
          aoCancelar={() => setPasso({ passo: 'montando', maleta: passo.maleta })}
        />
      ) : null}
      {passo.passo === 'enviada' ? (
        <FichaDaMaleta
          ponte={ponte}
          maleta={passo.maleta}
          recemEnviada
          jaEstavaEnviada={passo.jaEstava}
          aoMontarOutra={voltarParaLista}
        />
      ) : null}
    </div>
  );
}
