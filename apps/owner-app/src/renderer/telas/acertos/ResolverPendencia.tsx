import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { PedirMotivo } from '../maletas/PedirMotivo';
import { erroDoAcerto } from './dados';

const T = TEXTOS_DE_ACERTOS.pendencias.dialogo;

export interface ResolverPendenciaProps {
  ponte: PonteDoCorona;
  pendenciaId: string;
  /** "A peça apareceu" só existe para peça que faltou. */
  ehFalta: boolean;
  aoFeito: () => void;
  aoCancelar: () => void;
}

/** A loja assume ou a peça apareceu, sempre com o motivo; o id do comando vale até o servidor responder. */
export function ResolverPendencia({
  ponte,
  pendenciaId,
  ehFalta,
  aoFeito,
  aoCancelar,
}: ResolverPendenciaProps) {
  const [acao, setAcao] = useState<'LOJA_ASSUME' | 'ENCONTRADA'>('LOJA_ASSUME');
  const comandoId = useRef(crypto.randomUUID());
  return (
    <PedirMotivo
      titulo={T.titulo}
      texto={
        <>
          <p className="gestao-texto">{T.texto}</p>
          {(
            [['LOJA_ASSUME', T.loja], ...(ehFalta ? [['ENCONTRADA', T.apareceu]] : [])] as [
              'LOJA_ASSUME' | 'ENCONTRADA',
              string,
            ][]
          ).map(([valor, rotulo]) => (
            <label key={valor} className="gestao-opcao">
              <input
                type="radio"
                name="como-resolver-a-pendencia"
                checked={acao === valor}
                onChange={() => setAcao(valor)}
              />{' '}
              {rotulo}
            </label>
          ))}
        </>
      }
      rotuloDoMotivo={T.motivo}
      avisoDoMotivo={T.motivoAviso}
      rotuloConfirmar={T.confirmar}
      rotuloCancelar={T.voltar}
      executar={async (motivo) => {
        const r = await chamar(ponte, 'pendencias.resolver', {
          id: pendenciaId,
          comandoId: comandoId.current,
          acao,
          motivo,
        });
        return r.ok ? r : { ok: false, erro: erroDoAcerto(r.erro) };
      }}
      aoFeito={aoFeito}
      aoCancelar={aoCancelar}
    />
  );
}
