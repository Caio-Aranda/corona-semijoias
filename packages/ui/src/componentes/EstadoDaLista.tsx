import type { ReactNode } from 'react';
import { Aviso } from './Aviso';
import { Botao } from './Botao';

export interface EstadoDaListaProps {
  carregando?: boolean;
  /** Mensagem de erro em português, dizendo o que fazer. */
  erro?: string | null;
  /** Texto de "carregando", ex.: "Buscando os usuários...". */
  textoCarregando: string;
  aoTentarDeNovo?: () => void;
  /** Rótulo do botão de nova tentativa (padrão: "Tentar de novo"). */
  rotuloTentarDeNovo?: string;
  /** O que mostrar quando não há erro nem carregamento (a lista, a tabela). */
  children?: ReactNode;
}

/** Os estados de toda lista: carregando, erro e o conteúdo. O "nada encontrado" é da própria lista. */
export function EstadoDaLista({
  carregando,
  erro,
  textoCarregando,
  aoTentarDeNovo,
  rotuloTentarDeNovo = 'Tentar de novo',
  children,
}: EstadoDaListaProps) {
  if (erro) {
    return (
      <div className="cs-estado-lista">
        <Aviso tipo="erro">{erro}</Aviso>
        {aoTentarDeNovo ? <Botao onClick={aoTentarDeNovo}>{rotuloTentarDeNovo}</Botao> : null}
      </div>
    );
  }
  if (carregando) {
    return (
      <p className="cs-estado-lista" role="status" aria-busy="true">
        {textoCarregando}
      </p>
    );
  }
  return <>{children}</>;
}
