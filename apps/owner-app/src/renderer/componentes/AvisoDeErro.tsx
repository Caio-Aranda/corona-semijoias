import { Aviso, Botao } from '@corona/ui';
import type { ErroDaTela } from '../nucleo/chamadas';
import { TEXTOS } from '../textos';

export interface AvisoDeErroProps {
  erro: ErroDaTela;
  /** Para o erro de "outra pessoa alterou": busca o cadastro de novo. */
  aoAtualizar?: () => void;
  /** Para falta de internet e outros erros que passam ao repetir. */
  aoTentarDeNovo?: () => void;
  /**
   * Campos recusados que a própria tela já mostra junto do campo (não repetir o motivo aqui);
   * 'todos' quando a tela mostra todos. Os outros motivos aparecem sob a frase geral.
   */
  camposJaMostrados?: readonly string[] | 'todos';
}

/** Mostra o erro de uma chamada com o texto do servidor e o botão que resolve (sem código nem termo técnico). */
export function AvisoDeErro({
  erro,
  aoAtualizar,
  aoTentarDeNovo,
  camposJaMostrados = [],
}: AvisoDeErroProps) {
  const tipo = erro.tipo === 'sem-conexao' || erro.tipo === 'versao' ? 'atencao' : 'erro';
  // O motivo de verdade da recusa: o texto de cada campo que a tela não mostra (em espanhol, como o servidor manda).
  const motivos =
    camposJaMostrados === 'todos'
      ? []
      : [
          ...new Set(
            Object.entries(erro.campos)
              .filter(
                ([campo, m]) => m && m !== erro.mensagem && !camposJaMostrados.includes(campo),
              )
              .map(([, m]) => m),
          ),
        ];
  return (
    <div className="gestao-erro">
      <Aviso tipo={tipo}>
        {motivos.length > 0 ? <p>{erro.mensagem}</p> : erro.mensagem}
        {motivos.length === 1 ? <p>{motivos[0]}</p> : null}
        {motivos.length > 1 ? (
          <ul>
            {motivos.map((m) => (
              <li key={m}>{m}</li>
            ))}
          </ul>
        ) : null}
      </Aviso>
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
