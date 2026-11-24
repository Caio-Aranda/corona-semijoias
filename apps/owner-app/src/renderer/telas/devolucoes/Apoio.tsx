import { JanelaDeConfirmacao, ValorTresMoedas } from '@corona/ui';
import { useRef, useState, type ReactNode } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela, type Retorno } from '../../nucleo/chamadas';
import {
  centavosDeTexto,
  formatarDolar,
  formatarGuarani,
  formatarReais,
} from '../../nucleo/moedas';
import { TEXTOS_DE_DEVOLUCOES } from '../../textos-devolucoes';
import { lerNomesDosVendedores, type ValorDoLancamento } from './dados';

const T = TEXTOS_DE_DEVOLUCOES;

/** "12.50" → "R$ 12,50"; sem valor, em branco. */
export function reais(texto: string | null): string {
  const c = texto === null ? null : centavosDeTexto(texto);
  return c === null ? '' : formatarReais(c);
}

/** Real em destaque e, quando o servidor calculou pela cotação do dia do lançamento, Dólar e Guarani (ADR-0006). */
export function ValorDoLancamentoNaTela({ valor }: { valor: ValorDoLancamento }) {
  const real = reais(valor.real);
  if (real === '') return null;
  const centavosDolar = valor.dolar === null ? null : centavosDeTexto(valor.dolar);
  const guarani = valor.guarani === null ? null : /^-?\d+/.exec(valor.guarani)?.[0];
  return (
    <ValorTresMoedas
      real={real}
      {...(centavosDolar === null ? {} : { dolar: formatarDolar(centavosDolar) })}
      {...(guarani ? { guarani: formatarGuarani(BigInt(guarani)) } : {})}
    />
  );
}

/** Um campo de escolha com rótulo (o mesmo padrão das outras telas). */
export function CampoDeEscolha({
  id,
  rotulo,
  valor,
  aoMudar,
  opcoes,
  vazio,
}: {
  id: string;
  rotulo: string;
  valor: string;
  aoMudar: (v: string) => void;
  opcoes: readonly { valor: string; texto: string }[];
  /** Primeira opção sem valor (ex.: "Todos os representantes"). */
  vazio?: string;
}) {
  return (
    <div className="cs-campo">
      <label htmlFor={id}>{rotulo}</label>
      <select id={id} value={valor} onChange={(e) => aoMudar(e.target.value)}>
        {vazio !== undefined ? <option value="">{vazio}</option> : null}
        {opcoes.map((o) => (
          <option key={o.valor} value={o.valor}>
            {o.texto}
          </option>
        ))}
      </select>
    </div>
  );
}

/** Os vendedores ativos: o nome de cada id (as listas só trazem o id) e as opções para escolher. */
export function useVendedores(ponte: PonteDoCorona) {
  const busca = useBusca(() => chamar(ponte, 'vendedores.escolha'), 'devolucoes-vendedores');
  const todos = busca.dados ? lerNomesDosVendedores(busca.dados) : [];
  return {
    opcoes: todos.map((v) => ({ valor: v.id, texto: v.nome })),
    nomeDe: (id: string) => todos.find((v) => v.id === id)?.nome ?? T.vendedorDesligado,
  };
}

/** Pergunta antes de uma ação sem volta e mostra a recusa do servidor dentro da janela. */
export function ConfirmarAcao({
  titulo,
  rotuloConfirmar,
  rotuloCancelar = T.voltar,
  perigosa = false,
  executar,
  aoFeito,
  aoCancelar,
  children,
}: {
  titulo: string;
  rotuloConfirmar: string;
  rotuloCancelar?: string;
  perigosa?: boolean;
  executar: () => Promise<Retorno<unknown>>;
  aoFeito: (dados: unknown) => void;
  aoCancelar: () => void;
  children: ReactNode;
}) {
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  async function confirmar() {
    if (trava.current) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await executar();
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    aoFeito(r.dados);
  }

  return (
    <JanelaDeConfirmacao
      titulo={titulo}
      rotuloConfirmar={rotuloConfirmar}
      rotuloCancelar={rotuloCancelar}
      perigosa={perigosa}
      ocupado={ocupado}
      aoCancelar={aoCancelar}
      aoConfirmar={() => void confirmar()}
    >
      <>
        {children}
        {erro ? <AvisoDeErro erro={erro} /> : null}
      </>
    </JanelaDeConfirmacao>
  );
}

/** Uma frase só, embaixo das listas de valores (glossário, parte 11). */
export function RodapeDaCotacao() {
  return <p className="gestao-texto">{T.rodapeDaCotacao}</p>;
}

/** Um identificador novo por janela aberta: o reenvio da mesma ação leva o mesmo id (o servidor responde 200). */
export function novoId(): string {
  return crypto.randomUUID();
}
