import { useRef, type ReactNode } from 'react';
import type { OperacaoDaApi, PonteDoCorona } from '../../../shared/ponte';
import { chamar } from '../../nucleo/chamadas';
import { PedirMotivo } from '../maletas/PedirMotivo';
import { erroDoFinanceiro } from './dados';

export interface TextosDoEstorno {
  titulo: string;
  texto: string;
  motivo: string;
  motivoAviso: string;
  confirmar: string;
  voltar: string;
}

export interface EstornarComMotivoProps {
  ponte: PonteDoCorona;
  operacao: OperacaoDaApi;
  /** O que identifica o que será estornado (o nome da chave depende da operação). */
  alvo: Record<string, string>;
  textos: TextosDoEstorno;
  /** Campos a mais na janela (por exemplo, o novo vencimento) e o que eles mandam. */
  extra?: ReactNode;
  parametrosExtras?: () => Record<string, string>;
  aoFeito: () => void;
  aoCancelar: () => void;
}

/** Pede o motivo e estorna. O id do comando nasce quando a janela abre e vale para toda nova tentativa. */
export function EstornarComMotivo(p: EstornarComMotivoProps) {
  const comandoId = useRef(crypto.randomUUID());
  return (
    <PedirMotivo
      titulo={p.textos.titulo}
      texto={
        <>
          <p className="gestao-texto">{p.textos.texto}</p>
          {p.extra}
        </>
      }
      rotuloDoMotivo={p.textos.motivo}
      avisoDoMotivo={p.textos.motivoAviso}
      rotuloConfirmar={p.textos.confirmar}
      rotuloCancelar={p.textos.voltar}
      executar={async (motivo) => {
        const r = await chamar(p.ponte, p.operacao, {
          ...p.alvo,
          ...(p.parametrosExtras?.() ?? {}),
          comandoId: comandoId.current,
          motivo,
        });
        return r.ok ? r : { ok: false, erro: erroDoFinanceiro(r.erro) };
      }}
      aoFeito={p.aoFeito}
      aoCancelar={p.aoCancelar}
    />
  );
}
