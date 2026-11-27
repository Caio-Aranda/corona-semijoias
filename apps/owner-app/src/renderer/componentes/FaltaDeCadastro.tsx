import { Aviso, Botao } from '@corona/ui';
import type { Rota } from '../nucleo/rotas';

export interface FaltaDeCadastroProps {
  /** O que falta e para que serve, em uma frase simples. */
  mensagem: string;
  /** O texto do botão ("Cadastrar semana"). */
  rotuloDoBotao: string;
  /** A tela onde se faz o cadastro que falta. */
  rota: Rota;
}

/** Vai para outra tela do menu (a estrutura ouve a barra de endereço). */
export function irParaATela(rota: Rota): void {
  window.location.hash = `/${rota}`;
}

/**
 * Aviso para a tela que depende de um cadastro feito antes (semana, representante, fornecedor...):
 * diz o que falta e tem o botão que leva direto para a tela do cadastro.
 */
export function FaltaDeCadastro({ mensagem, rotuloDoBotao, rota }: FaltaDeCadastroProps) {
  return (
    <div className="gestao-erro">
      <Aviso tipo="atencao">{mensagem}</Aviso>
      <Botao variante="primario" onClick={() => irParaATela(rota)}>
        {rotuloDoBotao}
      </Botao>
    </div>
  );
}
