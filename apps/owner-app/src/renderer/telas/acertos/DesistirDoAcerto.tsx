import { useRef } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { PedirMotivo } from '../maletas/PedirMotivo';
import { erroDoAcerto } from './dados';

const T = TEXTOS_DE_ACERTOS.desistir;

export interface DesistirDoAcertoProps {
  ponte: PonteDoCorona;
  acertoId: string;
  aoFeito: () => void;
  aoCancelar: () => void;
}

/** Desistir de um acerto em andamento: o motivo é obrigatório e o id do comando vale até o servidor responder. */
export function DesistirDoAcerto({ ponte, acertoId, aoFeito, aoCancelar }: DesistirDoAcertoProps) {
  const comandoId = useRef(crypto.randomUUID());
  return (
    <PedirMotivo
      titulo={T.titulo}
      texto={<p className="gestao-texto">{T.texto}</p>}
      rotuloDoMotivo={T.motivo}
      avisoDoMotivo={T.motivoAviso}
      rotuloConfirmar={T.confirmar}
      rotuloCancelar={T.voltar}
      executar={async (motivo) => {
        const r = await chamar(ponte, 'acertos.desistir', {
          id: acertoId,
          comandoId: comandoId.current,
          motivo,
        });
        return r.ok ? r : { ok: false, erro: erroDoAcerto(r.erro) };
      }}
      aoFeito={aoFeito}
      aoCancelar={aoCancelar}
    />
  );
}
