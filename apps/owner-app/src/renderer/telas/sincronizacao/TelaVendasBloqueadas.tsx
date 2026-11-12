import { Aviso, Botao, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_SINCRONIZACAO } from '../../textos-sincronizacao';
import { PedirMotivo } from '../maletas/PedirMotivo';
import {
  dataEHora,
  lerResultadoDaAprovacao,
  lerVendasGuardadas,
  type VendaGuardada,
} from './dados';

const T = TEXTOS_DE_SINCRONIZACAO.vendas;
const TIPOS = TEXTOS_DE_SINCRONIZACAO.envios.tipos;

/** Vendas de computador bloqueado: a dona aprova (passa pelas mesmas regras do envio) ou descarta com motivo. */
export function TelaVendasBloqueadas({ ponte }: { ponte: PonteDoCorona }) {
  const busca = useBusca(() => chamar(ponte, 'sincronizacao.quarentena'), 'vendas-bloqueadas');
  const [aprovando, setAprovando] = useState<VendaGuardada | null>(null);
  const [descartando, setDescartando] = useState<VendaGuardada | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [resultado, setResultado] = useState<{ tipo: 'sucesso' | 'atencao'; texto: string } | null>(
    null,
  );
  const trava = useRef(false);
  const vendas = busca.dados ? lerVendasGuardadas(busca.dados) : [];

  async function aprovar() {
    if (!aprovando || trava.current) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'sincronizacao.aprovarQuarentena', { id: aprovando.id });
    trava.current = false;
    setOcupado(false);
    setAprovando(null);
    if (!r.ok) {
      setErro(r.erro);
    } else {
      const lido = lerResultadoDaAprovacao(r.dados);
      const deuCerto = lido.resultado === 'ACEITO' || lido.resultado === 'JA_RECEBIDO';
      setResultado(
        deuCerto
          ? { tipo: 'sucesso', texto: T.aprovado }
          : { tipo: 'atencao', texto: T.naoAprovado(lido.mensagem) },
      );
    }
    busca.recarregar();
  }

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
      {resultado ? <Aviso tipo={resultado.tipo}>{resultado.texto}</Aviso> : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {erro ? <AvisoDeErro erro={erro} aoAtualizar={busca.recarregar} /> : null}
      <Tabela
        legenda={T.legenda}
        textoVazio={T.vazio}
        colunas={[
          { chave: 'oQue', titulo: T.colunas.oQue },
          { chave: 'vendedor', titulo: T.colunas.vendedor },
          { chave: 'quando', titulo: T.colunas.quando },
          { chave: 'acoes', titulo: T.colunas.acoes },
        ]}
        linhas={vendas.map((v) => ({
          oQue: TIPOS[v.tipo] ?? v.tipo,
          vendedor: v.vendedorNome,
          quando: dataEHora(v.criadoEmLocal ?? v.recebidoEm),
          acoes: (
            <div className="gestao-acoes-da-linha">
              <Botao aria-label={T.aprovarDe(v.vendedorNome)} onClick={() => setAprovando(v)}>
                {T.aprovar}
              </Botao>
              <Botao aria-label={T.descartarDe(v.vendedorNome)} onClick={() => setDescartando(v)}>
                {T.descartar}
              </Botao>
            </div>
          ),
        }))}
      />
      {aprovando ? (
        <JanelaDeConfirmacao
          titulo={T.confirmarAprovar.titulo}
          rotuloConfirmar={ocupado ? T.aprovando : T.confirmarAprovar.aprovar}
          rotuloCancelar={T.confirmarAprovar.cancelar}
          ocupado={ocupado}
          aoCancelar={() => setAprovando(null)}
          aoConfirmar={() => void aprovar()}
        >
          {T.confirmarAprovar.texto}
        </JanelaDeConfirmacao>
      ) : null}
      {descartando ? (
        <PedirMotivo
          titulo={T.confirmarDescarte.titulo}
          texto={T.confirmarDescarte.texto}
          rotuloDoMotivo={T.confirmarDescarte.rotuloDoMotivo}
          avisoDoMotivo={T.confirmarDescarte.avisoDoMotivo}
          rotuloConfirmar={T.confirmarDescarte.descartar}
          rotuloCancelar={T.confirmarDescarte.cancelar}
          executar={(motivo) =>
            chamar(ponte, 'sincronizacao.descartarQuarentena', { id: descartando.id, motivo })
          }
          aoFeito={() => {
            setDescartando(null);
            setResultado(null);
            busca.recarregar();
          }}
          aoCancelar={() => setDescartando(null)}
        />
      ) : null}
    </div>
  );
}
