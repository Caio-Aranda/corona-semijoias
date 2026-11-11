import { Aviso } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { AvisoDeAtualizacao, PonteDoCorona } from '../../shared/ponte';
import { TEXTOS } from '../textos';

/**
 * Faixa no alto da janela quando uma versão nova já foi baixada (atualização automática, ADR-0009).
 * Não tem botão nem pede nada: a versão entra sozinha quando a pessoa fecha o programa.
 */
export function FaixaDeAtualizacao({ ponte }: { ponte: PonteDoCorona }) {
  const [aviso, setAviso] = useState<AvisoDeAtualizacao>({ estado: 'nenhuma' });

  useEffect(() => ponte.aoMudarAAtualizacao(setAviso), [ponte]);

  if (aviso.estado !== 'pronta') return null;
  return (
    <div className="gestao-faixa-de-atualizacao">
      <Aviso>{TEXTOS.atualizacao.pronta}</Aviso>
    </div>
  );
}
