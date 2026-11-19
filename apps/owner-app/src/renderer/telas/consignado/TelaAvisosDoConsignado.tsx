import { Botao, EstadoDaLista, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import { dataEHora } from '../sincronizacao/dados';
import { lerAvisos } from './dados';

const T = TEXTOS_DE_CONSIGNADO.avisos;

/**
 * Consignado → Avisos do consignado (ADR-0016, item 11): o que aconteceu no balcão e o servidor aceitou, mas a dona deve olhar.
 * Nada é recusado em silêncio. A dona só marca como visto.
 */
export function TelaAvisosDoConsignado({ ponte }: { ponte: PonteDoCorona }) {
  const [soNaoVistos, setSoNaoVistos] = useState(true);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const busca = useBusca(
    () => chamar(ponte, 'consignado.alertas', { soNaoVistos }),
    `consignado-avisos-${soNaoVistos}`,
  );
  const avisos = busca.dados ? lerAvisos(busca.dados) : [];

  async function marcar(id: string) {
    setErro(null);
    const r = await chamar(ponte, 'consignado.alertaVisto', { id });
    if (r.ok) busca.recarregar();
    else setErro(r.erro);
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-filtros">
        <label className="cs-campo-marcar">
          <input
            type="checkbox"
            checked={soNaoVistos}
            onChange={(e) => setSoNaoVistos(e.target.checked)}
          />{' '}
          {T.soNaoVistos}
        </label>
      </div>
      {erro ? <AvisoDeErro erro={erro} /> : null}
      <EstadoDaLista
        carregando={busca.carregando && !busca.dados}
        erro={busca.erro?.mensagem ?? null}
        textoCarregando={T.carregando}
        aoTentarDeNovo={busca.recarregar}
      >
        <Tabela
          legenda={T.legenda}
          colunas={[
            { chave: 'quando', titulo: T.colunas.quando },
            { chave: 'aviso', titulo: T.colunas.aviso },
            { chave: 'visto', titulo: T.colunas.visto },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={avisos.map((a) => {
            const texto = T.tipos[a.tipo] ?? T.tipoDesconhecido;
            return {
              quando: dataEHora(a.criadoEm),
              aviso: texto,
              visto: a.vistoEm ? T.visto(dataEHora(a.vistoEm)) : T.naoVisto,
              acoes: a.vistoEm ? null : (
                <Botao aria-label={T.marcarDe(texto)} onClick={() => void marcar(a.id)}>
                  {T.marcar}
                </Botao>
              ),
            };
          })}
          textoVazio={T.vazio}
        />
      </EstadoDaLista>
    </div>
  );
}
