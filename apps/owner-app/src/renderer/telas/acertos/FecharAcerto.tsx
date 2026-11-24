import { JanelaDeConfirmacao } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { ValorDoAcerto } from './Valor';
import { erroDoAcerto, lerFicha, type FichaDoAcerto } from './dados';
import type { PreviaDoFechamento } from './previa';

const T = TEXTOS_DE_ACERTOS.fechar;
const R = TEXTOS_DE_ACERTOS.previa.totais;

export interface FecharAcertoProps {
  ponte: PonteDoCorona;
  acerto: FichaDoAcerto;
  previa: PreviaDoFechamento;
  /** O id do fechamento: vale até as decisões mudarem, e é o mesmo em toda nova tentativa. */
  idDoFechamento: string;
  /** As decisões e o pagamento, prontos para ir (nada de total, comissão nem percentual). */
  pedido: Record<string, unknown>;
  aoFechar: (ficha: FichaDoAcerto, jaEstavaFechado: boolean) => void;
  /** Os números mudaram ou apareceu algo que impede: a prévia precisa ser lida de novo. */
  aoPrecisarConferir: (erro: ErroDaTela) => void;
  aoVoltar: () => void;
}

const CODIGOS_PARA_CONFERIR = new Set([
  'FECHAMENTO_DESATUALIZADO',
  'SOBRA_NO_ACERTO',
  'REVISAO_PENDENTE_NO_ACERTO',
  'COTACAO_DO_ACERTO_MUDOU',
]);

/** Pergunta antes de fechar e manda o pedido. O botão desliga ao clicar; o mesmo id vale em toda nova tentativa. */
export function FecharAcerto({
  ponte,
  acerto,
  previa,
  idDoFechamento,
  pedido,
  aoFechar,
  aoPrecisarConferir,
  aoVoltar,
}: FecharAcertoProps) {
  const [fechando, setFechando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  async function fechar() {
    if (trava.current) return;
    trava.current = true;
    setFechando(true);
    setErro(null);
    const r = await chamar(ponte, 'acertos.fechar', {
      id: acerto.id,
      // O líquido da última prévia vai só para o sistema conferir: a tela não o calcula.
      pedido: {
        ...pedido,
        id: idDoFechamento,
        liquidoMostrado: previa.liquido,
        // A cotação que a prévia mostrou: se a dona lançou outra e há recebimento em Dólar ou Guarani, o sistema recusa.
        ...(previa.cotacaoId ? { cotacaoId: previa.cotacaoId } : {}),
      },
    });
    trava.current = false;
    setFechando(false);
    if (!r.ok) {
      const traduzido = r.erro.codigo ? T.erros[r.erro.codigo] : undefined;
      const mostrado: ErroDaTela =
        r.erro.tipo === 'sem-conexao'
          ? { ...r.erro, mensagem: T.semConexao }
          : traduzido
            ? { ...r.erro, mensagem: traduzido }
            : erroDoAcerto(r.erro);
      if (r.erro.codigo && CODIGOS_PARA_CONFERIR.has(r.erro.codigo)) {
        aoPrecisarConferir(mostrado);
        return;
      }
      setErro(mostrado);
      return;
    }
    const ficha = lerFicha(r.dados);
    if (!ficha) {
      setErro({ tipo: 'outro', mensagem: TEXTOS_DE_ACERTOS.ficha.naoAbriu, campos: {} });
      return;
    }
    aoFechar(ficha, r.jaRegistrado === true);
  }

  return (
    <JanelaDeConfirmacao
      titulo={T.titulo}
      rotuloConfirmar={fechando ? T.fechando : T.botao}
      rotuloCancelar={T.voltar}
      perigosa
      ocupado={fechando}
      aoCancelar={aoVoltar}
      aoConfirmar={() => void fechar()}
    >
      <>
        <p>{T.texto(acerto.vendedorNome)}</p>
        <dl className="gestao-totais">
          <div className="gestao-totais__linha">
            <dt>{R.liquido}</dt>
            <dd>
              <ValorDoAcerto valor={previa.liquido} />
            </dd>
          </div>
          <div className="gestao-totais__linha">
            <dt>{R.aPagar}</dt>
            <dd>
              <ValorDoAcerto valor={previa.aPagarNoAcerto} />
            </dd>
          </div>
        </dl>
        {erro ? <AvisoDeErro erro={erro} /> : null}
      </>
    </JanelaDeConfirmacao>
  );
}
