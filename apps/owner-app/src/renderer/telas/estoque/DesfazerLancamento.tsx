import { Campo, JanelaDeConfirmacao } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { MENSAGENS, conferirMotivo } from '../../nucleo/estoque';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';

const T = TEXTOS_DE_ESTOQUE.desfazer;

export interface DesfazerLancamentoProps {
  ponte: PonteDoCorona;
  /** O lançamento que será desfeito. */
  lancamentoId: string;
  aoFeito: () => void;
  aoCancelar: () => void;
}

/**
 * Pede o motivo e desfaz o lançamento (um lançamento novo que cancela o antigo). O mesmo
 * identificador vale em toda tentativa: reenviar depois de uma falha de internet não desfaz duas vezes.
 */
export function DesfazerLancamento({
  ponte,
  lancamentoId,
  aoFeito,
  aoCancelar,
}: DesfazerLancamentoProps) {
  const [motivo, setMotivo] = useState('');
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const pedidoId = useRef(crypto.randomUUID());
  const motivoEnviado = useRef('');
  const trava = useRef(false);
  const conferido = conferirMotivo(motivo, MENSAGENS.motivoDeDesfazer);

  async function desfazer() {
    if (trava.current) return;
    if (!conferido.ok) {
      setTentou(true);
      return;
    }
    // Mesmo motivo = mesmo pedido (reenvio seguro). Motivo mudado = pedido novo.
    if (motivoEnviado.current !== '' && motivoEnviado.current !== conferido.valor) {
      pedidoId.current = crypto.randomUUID();
    }
    motivoEnviado.current = conferido.valor;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'estoque.desfazer', {
      id: lancamentoId,
      pedidoId: pedidoId.current,
      motivo: conferido.valor,
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    aoFeito();
  }

  return (
    <JanelaDeConfirmacao
      titulo={T.titulo}
      rotuloConfirmar={T.confirmar}
      rotuloCancelar={T.voltar}
      perigosa
      ocupado={ocupado}
      aoCancelar={aoCancelar}
      aoConfirmar={() => void desfazer()}
    >
      <>
        {T.texto}
        <Campo
          rotulo={T.motivo}
          value={motivo}
          maxLength={500}
          autoComplete="off"
          onChange={(e) => setMotivo(e.target.value)}
          {...(tentou && !conferido.ok ? { erro: conferido.erro } : {})}
        />
        {erro ? <AvisoDeErro erro={erro} /> : null}
      </>
    </JanelaDeConfirmacao>
  );
}
