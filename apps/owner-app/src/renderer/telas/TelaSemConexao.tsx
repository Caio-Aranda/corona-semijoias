import { Aviso, Botao, CabecalhoDeMarca } from '@corona/ui';
import { useEffect, useRef } from 'react';
import { ESPERA_NOVA_TENTATIVA_MS } from '../nucleo/chamadas';
import { TEXTOS } from '../textos';

export interface TelaSemConexaoProps {
  aoTentarDeNovo: () => void;
  /** Só para testes. */
  esperaMs?: number;
}

/**
 * Há uma sessão guardada, mas não deu para renová-la (sem internet ou servidor fora do ar).
 * A pessoa não precisa entrar de novo: o aplicativo tenta sozinho de tempos em tempos.
 */
export function TelaSemConexao({
  aoTentarDeNovo,
  esperaMs = ESPERA_NOVA_TENTATIVA_MS,
}: TelaSemConexaoProps) {
  const repetir = useRef(aoTentarDeNovo);
  repetir.current = aoTentarDeNovo;
  useEffect(() => {
    const relogio = setInterval(() => repetir.current(), esperaMs);
    return () => clearInterval(relogio);
  }, [esperaMs]);

  return (
    <div className="gestao-pagina">
      <CabecalhoDeMarca nomeDoApp="Corona Gestão" />
      <main className="gestao-conteudo">
        <div className="gestao-cartao">
          <Aviso tipo="atencao">{TEXTOS.semConexao.texto}</Aviso>
          <p className="gestao-texto">{TEXTOS.semConexao.aviso}</p>
          <Botao variante="primario" grande onClick={aoTentarDeNovo}>
            Tentar de novo
          </Botao>
        </div>
      </main>
    </div>
  );
}
