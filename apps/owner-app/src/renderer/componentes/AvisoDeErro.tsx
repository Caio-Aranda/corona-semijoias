import { Aviso, Botao } from '@corona/ui';
import type { ErroDaTela } from '../nucleo/chamadas';
import { TEXTOS } from '../textos';

export interface AvisoDeErroProps {
  erro: ErroDaTela;
  /** Para o erro de "outra pessoa alterou": busca o cadastro de novo. */
  aoAtualizar?: () => void;
  /** Para falta de internet e outros erros que passam ao repetir. */
  aoTentarDeNovo?: () => void;
}

/** Mostra o erro de uma chamada com o texto do servidor e o botão que resolve (sem código nem termo técnico). */
export function AvisoDeErro({ erro, aoAtualizar, aoTentarDeNovo }: AvisoDeErroProps) {
  const tipo = erro.tipo === 'sem-conexao' || erro.tipo === 'versao' ? 'atencao' : 'erro';
  return (
    <div className="gestao-erro">
      <Aviso tipo={tipo}>{erro.mensagem}</Aviso>
      {erro.tipo === 'sem-conexao' ? (
        <p className="gestao-texto">{TEXTOS.semConexao.aviso}</p>
      ) : null}
      {erro.tipo === 'versao' && aoAtualizar ? (
        <Botao onClick={aoAtualizar}>{TEXTOS.lista.atualizar}</Botao>
      ) : null}
      {erro.tipo === 'sem-conexao' && aoTentarDeNovo ? (
        <Botao onClick={aoTentarDeNovo}>{TEXTOS.lista.tentarDeNovo}</Botao>
      ) : null}
    </div>
  );
}
