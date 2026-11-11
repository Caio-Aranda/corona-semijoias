import { Campo, JanelaDeConfirmacao } from '@corona/ui';
import { useRef, useState, type ReactNode } from 'react';
import { MAXIMO_DO_MOTIVO_DA_MALETA } from '../../../shared/maletas';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import type { ErroDaTela, Retorno } from '../../nucleo/chamadas';

export interface PedirMotivoProps {
  titulo: string;
  texto: ReactNode;
  rotuloDoMotivo: string;
  avisoDoMotivo: string;
  rotuloConfirmar: string;
  rotuloCancelar: string;
  /** Texto extra mostrado junto de um erro do servidor (ex.: sugerir devolver as peças). */
  dicaNoErro?: string;
  /** Faz a chamada com o motivo já aparado. */
  executar: (motivo: string) => Promise<Retorno<unknown>>;
  aoFeito: () => void;
  aoCancelar: () => void;
}

/** Janela que pede o motivo (obrigatório) antes de uma ação sem volta e mostra a recusa do servidor. */
export function PedirMotivo(p: PedirMotivoProps) {
  const [motivo, setMotivo] = useState('');
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);
  const aparado = motivo.trim();

  async function confirmar() {
    if (trava.current) return;
    if (aparado === '') {
      setTentou(true);
      return;
    }
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await p.executar(aparado);
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    p.aoFeito();
  }

  return (
    <JanelaDeConfirmacao
      titulo={p.titulo}
      rotuloConfirmar={p.rotuloConfirmar}
      rotuloCancelar={p.rotuloCancelar}
      perigosa
      ocupado={ocupado}
      aoCancelar={p.aoCancelar}
      aoConfirmar={() => void confirmar()}
    >
      <>
        {p.texto}
        <Campo
          rotulo={p.rotuloDoMotivo}
          value={motivo}
          maxLength={MAXIMO_DO_MOTIVO_DA_MALETA}
          autoComplete="off"
          onChange={(e) => setMotivo(e.target.value)}
          {...(tentou && aparado === '' ? { erro: p.avisoDoMotivo } : {})}
        />
        {erro ? <AvisoDeErro erro={erro} /> : null}
        {erro && p.dicaNoErro ? <p className="gestao-texto">{p.dicaNoErro}</p> : null}
      </>
    </JanelaDeConfirmacao>
  );
}
