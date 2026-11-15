import { Botao, Campo } from '@corona/ui';
import { useRef, useState } from 'react';
import { FORMAS_DO_FINANCEIRO } from '../../../shared/financeiro';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { CampoDeEscolha } from '../devolucoes/Apoio';
import { soReal } from './Valor';
import { erroDoFinanceiro } from './dados';
import type { ContaAPagar } from './contas-a-pagar';

const T = TEXTOS_DE_FINANCEIRO.contasAPagar.pagamento;
const FORMAS = TEXTOS_DE_FINANCEIRO.formas;

/** Registrar pagamento de uma parcela. O id do comando nasce com o formulário e vale para toda nova tentativa. */
export function PagarConta({
  ponte,
  conta,
  aoFeito,
  aoCancelar,
}: {
  ponte: PonteDoCorona;
  conta: ContaAPagar;
  aoFeito: () => void;
  aoCancelar: () => void;
}) {
  const comandoId = useRef(crypto.randomUUID());
  const [forma, setForma] = useState<string>('DINHEIRO');
  const [data, setData] = useState('');
  const [digitado, setDigitado] = useState('');
  const [valor, setValor] = useState<string | null>(null);
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  async function pagar() {
    setTentou(true);
    if (trava.current || valor === null) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'contasAPagar.pagar', {
      id: conta.id,
      comandoId: comandoId.current,
      forma,
      valor,
      ...(data ? { data } : {}),
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(erroDoFinanceiro(r.erro));
      return;
    }
    aoFeito();
  }

  return (
    <form
      className="gestao-formulario gestao-formulario--larga"
      onSubmit={(e) => {
        e.preventDefault();
        void pagar();
      }}
    >
      <h2 className="gestao-subtitulo">{T.titulo}</h2>
      <p className="gestao-texto">
        {conta.fornecedorNome},{' '}
        {TEXTOS_DE_FINANCEIRO.contasAPagar.parcela(conta.parcela, conta.totalParcelas)}
      </p>
      <p className="gestao-texto">{T.faltaPagar(soReal(conta.saldo))}</p>
      <CampoDeEscolha
        id="pagamento-forma"
        rotulo={T.forma}
        valor={forma}
        aoMudar={setForma}
        opcoes={FORMAS_DO_FINANCEIRO.map((f) => ({ valor: f, texto: FORMAS[f] ?? f }))}
      />
      <CampoDeDinheiro
        id="pagamento-valor"
        rotulo={T.valor}
        valor={digitado}
        {...(tentou && valor === null ? { erro: T.avisoValor } : {})}
        aoMudar={(d, v) => {
          setDigitado(d);
          setValor(v !== null && Number(v) > 0 ? v : null);
        }}
      />
      <Campo
        type="date"
        rotulo={T.data}
        ajuda={T.dataAjuda}
        value={data}
        onChange={(e) => setData(e.target.value)}
      />
      {erro ? <AvisoDeErro erro={erro} /> : null}
      <div className="gestao-acoes-da-linha">
        <Botao type="submit" variante="primario" disabled={ocupado}>
          {T.salvar}
        </Botao>
        <Botao onClick={aoCancelar} disabled={ocupado}>
          {T.cancelar}
        </Botao>
      </div>
    </form>
  );
}
