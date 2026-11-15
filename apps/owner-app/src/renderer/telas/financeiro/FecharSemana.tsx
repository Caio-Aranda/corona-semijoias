import { JanelaDeConfirmacao } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { erroDoFinanceiro } from './dados';

const T = TEXTOS_DE_FINANCEIRO.semana.dialogo;

/**
 * Pergunta e fecha a semana. O id do comando nasce quando a janela abre e vale para toda nova tentativa
 * (sem internet, a pessoa aperta de novo e o servidor reconhece o mesmo pedido).
 */
export function FecharSemana({
  ponte,
  semanaId,
  numero,
  aoFeito,
  aoCancelar,
}: {
  ponte: PonteDoCorona;
  semanaId: string;
  numero: string;
  aoFeito: (jaEstavaFechada: boolean) => void;
  aoCancelar: () => void;
}) {
  const comandoId = useRef(crypto.randomUUID());
  const trava = useRef(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);

  async function fechar() {
    if (trava.current) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'semanas.fechar', { id: semanaId, comandoId: comandoId.current });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(erroDoFinanceiro(r.erro));
      return;
    }
    aoFeito(r.jaRegistrado === true);
  }

  return (
    <JanelaDeConfirmacao
      titulo={T.titulo}
      rotuloConfirmar={T.confirmar}
      rotuloCancelar={T.voltar}
      perigosa
      ocupado={ocupado}
      aoCancelar={aoCancelar}
      aoConfirmar={() => void fechar()}
    >
      <>
        <p className="gestao-texto">{T.texto(numero)}</p>
        {erro ? <AvisoDeErro erro={erro} /> : null}
      </>
    </JanelaDeConfirmacao>
  );
}
